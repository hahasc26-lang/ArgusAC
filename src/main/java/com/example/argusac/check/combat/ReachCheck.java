package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.combat.CombatVerdict;
import com.example.argusac.compat.CompatBoundingBox;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * 攻击距离检测 (ReachCheck)
 * 检测超出合法范围的超距攻击，综合考虑玩家延迟补偿与目标碰撞箱边缘。
 * 支持在 Netty 进包阶段直接进行无锁裁决，并输出分级处置结论（低确信微量超出仅静默 Drop，高确信明显作弊累加 VL）。
 */
public class ReachCheck extends AbstractCheck {

    private double maxReach;
    private double maxPingReach;

    // 低确信/微量超出缓冲裕度（在此区间内仅底层静默 Drop 攻击包，不增加 VL，防止网络抖动误伤）
    private static final double LOW_CONFIDENCE_BUFFER = 0.25;

    public ReachCheck(ArgusACPlugin plugin) {
        super(plugin, "Reach", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxReach = getChecksConfig().getDouble(path + "max-reach", 3.10);
        this.maxPingReach = getChecksConfig().getDouble(path + "max-ping-reach", 3.50);
    }

    /**
     * 核心数据包层/事件层通用超距评估函数 (Netty 线程安全)
     */
    public CombatVerdict evaluateAttack(Player attacker, PlayerData data, Entity victim) {
        if (!enabled || attacker == null || !(victim instanceof LivingEntity)) {
            return CombatVerdict.pass();
        }

        // 创造/旁观模式合法攻击距离放宽或豁免
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return CombatVerdict.pass();
        }

        if (data == null || data.isInGracePeriod()) {
            return CombatVerdict.pass();
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        Location eyeLocation = resolveAttackerEyeLocation(attacker, data);
        if (eyeLocation == null || eyeLocation.getWorld() != victim.getWorld()) {
            return CombatVerdict.pass();
        }

        int ping = Math.max(0, adapter.getPing(attacker));
        double eyeHeight = 1.62;
        try {
            eyeHeight = attacker.getEyeHeight();
        } catch (Throwable ignored) {
        }

        // 核心缺陷修复：检查玩家自身刚受到外部伤害/击退的时序窗口
        // 当玩家受到箭矢、爆炸或近战击退时，服务端位移领先于客户端发包确认，容易产生 0.5~2.0+ 格的位移脱节
        long now = System.currentTimeMillis();
        long timeSinceLastDamage = now - data.getLastDamageTime();
        boolean hasCombatKnockback = data.hasRecentVelocity(1500L)
                || (timeSinceLastDamage < Math.max(1200L, ping + 600L))
                || data.hasAnyLegalVelocity();

        // 处于受击强击退且网络尚未确认完成的短周期窗口（Ping + 350ms）内直接放行，避免误判
        if (timeSinceLastDamage < Math.max(450L, ping + 350L) && hasCombatKnockback) {
            return CombatVerdict.pass();
        }

        // 1. 基于实体历史碰撞盒回溯与攻击者多候选眼部位置的延迟补偿物理距离计算
        java.util.List<Location> candidateEyes = data.getCandidateEyeLocations(attacker.getWorld(), eyeHeight,
                attacker.getLocation());
        if (candidateEyes.isEmpty()) {
            candidateEyes.add(eyeLocation);
        }

        double distanceToBox = Double.MAX_VALUE;
        for (Location candEye : candidateEyes) {
            if (candEye == null || candEye.getWorld() != victim.getWorld())
                continue;
            double dist;
            if (plugin.getHistoryTracker() != null) {
                dist = plugin.getHistoryTracker().getCompensatedMinDistance(candEye, victim, ping);
            } else {
                CompatBoundingBox box = adapter.getEntityBoundingBox(victim);
                dist = box.distanceTo(candEye);
            }
            if (dist < distanceToBox) {
                distanceToBox = dist;
            }
        }

        if (distanceToBox == Double.MAX_VALUE) {
            return CombatVerdict.pass();
        }

        // 动态基础上限计算
        boolean hasTrackedHistory = (plugin.getHistoryTracker() != null
                && plugin.getHistoryTracker().hasSufficientHistory(victim.getUniqueId(), ping));
        double dynamicLimit = hasTrackedHistory
                ? (maxReach + 0.15 + Math.min(0.25, (ping / 400.0) * 0.20))
                : Math.min(maxReach + 0.20 + Math.min(0.45, (ping / 100.0) * 0.10), maxPingReach + 0.15);

        // 击退与切槽动态补偿放宽
        boolean underKnockback = hasCombatKnockback;
        boolean recentSlotSwitch = data.hasRecentSlotSwitch(Math.max(300L, ping + 150L));

        if (underKnockback) {
            // 远程箭矢与近战击退位移补偿：根据受击后经过的时间平滑衰减，充分吸收 1.5~2.5 格的强击退脱节
            double decay = Math.max(0.0, 1.0 - (timeSinceLastDamage / 1500.0));
            dynamicLimit += 0.85 + (1.20 * decay) + Math.min(0.35, (ping / 200.0) * 0.20);
        } else if (recentSlotSwitch) {
            dynamicLimit += 0.15;
        }

        if (distanceToBox > dynamicLimit) {
            double excess = distanceToBox - dynamicLimit;
            String detail = String.format("dist=%.3f, limit=%.2f, excess=%.3f, ping=%d (Backtracked)",
                    distanceToBox, dynamicLimit, excess, ping);

            // 分级处置策略：如果刚受过击退，增加低确信保护缓冲裕度 (扩大至 0.85格)
            double effectiveLowBuffer = underKnockback ? 0.85 : (recentSlotSwitch ? 0.30 : LOW_CONFIDENCE_BUFFER);
            if (excess <= effectiveLowBuffer) {
                return underKnockback ? CombatVerdict.pass() : CombatVerdict.lowConfidenceDrop(this, detail);
            }

            // 高确信/明显超距：按超出幅度计算 VL 步进 (在击退期步进限制为 1，杜绝瞬间爆表)
            int vlStep = underKnockback ? 1 : (excess > 0.80 ? 3 : (excess > 0.45 ? 2 : 1));
            return CombatVerdict.highConfidence(this, vlStep, detail);
        }

        return CombatVerdict.pass();
    }

    /**
     * Bukkit 实体受伤事件兜底入口 (Fallback)
     */
    public void handleAttack(Player attacker, Entity victim, EntityDamageByEntityEvent event) {
        if (!enabled || attacker == null) {
            return;
        }

        // 严格过滤非直接近战物理打击（如弓箭、三叉戟、雪球、投掷药水等远程抛射物，以及荆棘反伤）
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }

        // 如果伤害来源实体与攻击者不是同一个对象（例如直系箭矢事件中 damager 为 Arrow 实体）
        if (event.getDamager() != attacker) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(attacker);
        CombatVerdict verdict = evaluateAttack(attacker, data, victim);
        if (verdict.isBlocked() && plugin.getCombatMitigationHandler() != null) {
            plugin.getCombatMitigationHandler().applyFallbackVerdict(event, attacker, victim, verdict);
        }
    }

    /**
     * 跨线程安全获取攻击者最新眼睛位置 (优先使用 Netty 数据包级实时位置与视角)
     */
    private Location resolveAttackerEyeLocation(Player attacker, PlayerData data) {
        double eyeHeight = 1.62;
        try {
            eyeHeight = attacker.getEyeHeight();
        } catch (Throwable ignored) {
        }
        if (data != null && attacker.getWorld() != null) {
            Location packetEye = data.getLatestPacketEyeLocation(attacker.getWorld(), eyeHeight);
            if (packetEye != null) {
                return packetEye;
            }
        }
        if (org.bukkit.Bukkit.isPrimaryThread()) {
            return attacker.getEyeLocation();
        }
        Location loc = attacker.getLocation();
        if (loc != null && loc.getWorld() != null) {
            return new Location(loc.getWorld(), loc.getX(), loc.getY() + eyeHeight, loc.getZ(), loc.getYaw(),
                    loc.getPitch());
        }
        return attacker.getEyeLocation();
    }
}