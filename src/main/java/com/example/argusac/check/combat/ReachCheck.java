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

        // 创造模式合法攻击距离可达 5.0，豁免
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

        // 1. 基于实体历史碰撞盒回溯的延迟补偿物理距离计算 (GrimAC-Grade Lag Compensated Reach)
        double distanceToBox;
        if (plugin.getHistoryTracker() != null) {
            distanceToBox = plugin.getHistoryTracker().getCompensatedMinDistance(eyeLocation, victim, ping);
        } else {
            CompatBoundingBox box = adapter.getEntityBoundingBox(victim);
            distanceToBox = box.distanceTo(eyeLocation);
        }

        if (distanceToBox == Double.MAX_VALUE) {
            return CombatVerdict.pass();
        }

        // 若受害者存在历史快照，使用回溯物理极限加上网络延迟与服务端 Tick 插值容差；若为怪物或无快照，应用网络延迟 Ping 补偿
        boolean hasTrackedHistory = (plugin.getHistoryTracker() != null && plugin.getHistoryTracker().hasHistory(victim.getUniqueId()));
        double dynamicLimit = hasTrackedHistory
                ? (maxReach + 0.10 + Math.min(0.20, (ping / 500.0) * 0.15))
                : Math.min(maxReach + Math.min(0.40, (ping / 100.0) * 0.08), maxPingReach);

        if (distanceToBox > dynamicLimit) {
            double excess = distanceToBox - dynamicLimit;
            String detail = String.format("dist=%.3f, limit=%.2f, excess=%.3f, ping=%d (Backtracked)",
                    distanceToBox, dynamicLimit, excess, ping);

            // 分级处置策略：微量超出 (excess <= 0.25) 仅在底层静默丢弃攻击包，不累加 VL
            if (excess <= LOW_CONFIDENCE_BUFFER) {
                return CombatVerdict.lowConfidenceDrop(this, detail);
            }

            // 高确信/明显超距：按超出幅度计算 VL 步进 (1 ~ 3)
            int vlStep = excess > 0.80 ? 3 : (excess > 0.45 ? 2 : 1);
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
            return new Location(loc.getWorld(), loc.getX(), loc.getY() + eyeHeight, loc.getZ(), loc.getYaw(), loc.getPitch());
        }
        return attacker.getEyeLocation();
    }
}
