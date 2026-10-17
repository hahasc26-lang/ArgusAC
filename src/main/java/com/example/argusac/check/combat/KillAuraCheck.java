package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 杀戮光环检测与主动防御响应机制 (KillAuraCheck)
 * 具备健全合理的实战处置手段：
 * 1. 取消非法攻击交互与伤害判定 (Cancel Damage)
 * 2. 武器蓄力充能清零 (Reset Attack Cooldown)
 * 3. 连续违规激活战斗抑制/缴械惩罚 (Combat Throttle / Disarm)
 * 4. 盲区背身攻击 (Blindspot)、瞬间自瞄偏转 (Snap) 与极速目标切换全链路拦截
 */
public class KillAuraCheck extends AbstractCheck {

    private double maxAngle;
    private boolean raytraceBlocks;
    private long targetSwitchMs;
    private boolean resetCooldown;
    private boolean combatThrottleEnabled;
    private long combatThrottleMs;
    private int combatThrottleMinVl;
    private boolean blindspotCheck;
    private boolean snapCheck;
    private double snapAngleThreshold;

    public KillAuraCheck(ArgusACPlugin plugin) {
        super(plugin, "KillAura", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxAngle = plugin.getConfig().getDouble(path + "max-angle", 68.0);
        this.raytraceBlocks = plugin.getConfig().getBoolean(path + "raytrace-blocks", true);
        this.targetSwitchMs = plugin.getConfig().getLong(path + "target-switch-ms", 80L);
        this.resetCooldown = plugin.getConfig().getBoolean(path + "reset-cooldown", true);
        this.combatThrottleEnabled = plugin.getConfig().getBoolean(path + "combat-throttle-enabled", true);
        this.combatThrottleMs = plugin.getConfig().getLong(path + "combat-throttle-ms", 1500L);
        this.combatThrottleMinVl = plugin.getConfig().getInt(path + "combat-throttle-min-vl", 3);
        this.blindspotCheck = plugin.getConfig().getBoolean(path + "blindspot-check", true);
        this.snapCheck = plugin.getConfig().getBoolean(path + "snap-check", true);
        this.snapAngleThreshold = plugin.getConfig().getDouble(path + "snap-angle-threshold", 45.0);
    }

    public void handleAttack(Player attacker, Entity victim, EntityDamageByEntityEvent event) {
        if (!enabled || attacker == null || !(victim instanceof LivingEntity livingVictim)) {
            return;
        }

        // 死亡状态攻击与攻击自身漏洞拦截
        if (attacker.isDead() || livingVictim.isDead()) {
            event.setCancelled(true);
            flag(attacker, 3, "Attack while dead");
            return;
        }
        if (attacker.equals(livingVictim)) {
            event.setCancelled(true);
            flag(attacker, 3, "Self damage packet exploit");
            return;
        }

        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(attacker);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 核心反应手段 1：处于战斗抑制期 (Combat Throttle) 时的绝对拦截
        if (data.isCombatThrottled()) {
            event.setCancelled(true);
            if (resetCooldown) {
                adapter.resetAttackCooldown(attacker);
            }
            adapter.sendActionBar(attacker, "§c[ArgusAC] 战斗异常，近战攻击能力已被暂时抑制！");
            return;
        }

        Location eyeLoc = attacker.getEyeLocation();
        int ping = Math.max(0, adapter.getPing(attacker));

        // 基于网络往返延迟回溯受害者在攻击者屏幕上的历史真实坐标 (GrimAC Lag Compensated Position)
        Location victimLoc = (plugin.getHistoryTracker() != null)
                ? plugin.getHistoryTracker().getCompensatedLocation(livingVictim, ping)
                : livingVictim.getLocation();
        Location victimEye = victimLoc.clone().add(0, livingVictim.getEyeHeight(), 0);

        if (eyeLoc.getWorld() != victimEye.getWorld()) {
            return;
        }

        Vector eyeDirection = eyeLoc.getDirection();
        Vector toTarget = victimEye.toVector().subtract(eyeLoc.toVector());
        if (toTarget.lengthSquared() < 0.01) {
            return;
        }

        double distance = eyeLoc.distance(victimEye);
        double angle = MathUtil.getAngleBetweenVectors(eyeDirection, toTarget);

        // 1. 盲区与背身反向攻击拦截 (Blindspot Check: 完全背对或偏离超过 90 度发包攻击)
        if (blindspotCheck && distance > 0.8 && angle > 90.0) {
            applyMitigation(attacker, data, event, 3,
                    String.format("Blindspot Attack angle=%.1f deg (>90.0) (Backtracked)", angle));
            return;
        }

        // 2. 视角夹角检测 (Angle Check，基于回溯坐标对齐)
        // 仅在距离大于 1.0 格时检测极限脱节夹角 (贴脸时因碰撞箱宽大正常挥刀夹角变化剧烈)
        if (distance > 1.0 && angle > maxAngle) {
            applyMitigation(attacker, data, event, 2,
                    String.format("Impossible Angle angle=%.1f deg, max=%.1f (Backtracked)", angle, maxAngle));
            return;
        }

        // 3. 准星光线脱靶命中检测 (Silent Aim / Aimbot RayTrace Miss)
        if (distance > 1.2 && angle > 55.0) {
            double distanceToCompensatedBox = (plugin.getHistoryTracker() != null)
                    ? plugin.getHistoryTracker().getCompensatedMinDistance(eyeLoc, livingVictim, ping)
                    : adapter.getEntityBoundingBox(livingVictim).distanceTo(eyeLoc);

            if (distanceToCompensatedBox > distance + 0.8) {
                applyMitigation(attacker, data, event, 2,
                        String.format("Aimbot Ray Miss angle=%.1f, ping=%d (Backtracked)", angle, ping));
                return;
            }
        }

        // 4. 隔墙穿透攻击检测 (RayTrace Blocks，跨版本安全适配)
        if (raytraceBlocks && distance > 1.2) {
            if (adapter.isRayOccluded(eyeLoc, victimEye)) {
                applyMitigation(attacker, data, event, 2, "Attack Through Wall");
                return;
            }
        }

        // 5. 准星瞬间锁头突变检测 (Sudden Angle Snap)
        if (snapCheck && distance > 1.0) {
            float lastYaw = data.getLastLookYaw();
            float currentYaw = attacker.getLocation().getYaw();
            float yawDelta = Math.abs(currentYaw - lastYaw);
            if (yawDelta > 180.0f) yawDelta = 360.0f - yawDelta;

            // 攻击瞬间产生大跨度偏转且立刻命中
            if (yawDelta > snapAngleThreshold && angle < 25.0) {
                applyMitigation(attacker, data, event, 2,
                        String.format("Aimbot Snap deltaYaw=%.1f, angle=%.1f", yawDelta, angle));
                return;
            }
        }

        // 6. 极速切换不同目标检测 (Multi-Target Switch)
        long now = System.currentTimeMillis();
        UUID lastTarget = data.getLastAttackedTarget();
        long lastAttack = data.getLastAttackTime();

        if (lastTarget != null && !lastTarget.equals(victim.getUniqueId())) {
            long switchInterval = now - lastAttack;
            if (switchInterval < targetSwitchMs) {
                // 关键修复：极速切换目标同样必须取消攻击事件并重置冷却，阻止秒杀群殴外挂
                applyMitigation(attacker, data, event, 2,
                        String.format("Rapid Switch interval=%dms", switchInterval));
                return;
            }
        }

        data.setLastAttackedTarget(victim.getUniqueId());
        data.setLastAttackTime(now);
    }

    /**
     * 实施合理的反作弊实战反应与防御惩戒
     */
    private void applyMitigation(Player attacker, PlayerData data, EntityDamageByEntityEvent event, int vl, String reason) {
        // 1. 取消当前伤害与交互
        event.setCancelled(true);

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 2. 核心反应手段：重置攻击者武器充能冷却 (将蓄力攻击伤害削减归零)
        if (resetCooldown) {
            adapter.resetAttackCooldown(attacker);
        }

        // 3. 标记违规
        flag(attacker, vl, reason);

        // 4. 核心反应手段：达到阈值时施加战斗抑制 (Combat Throttle)，短时间封印近战输出
        int currentVl = data.getVl(name);
        if (combatThrottleEnabled && currentVl >= combatThrottleMinVl) {
            data.setCombatThrottled(combatThrottleMs);
            adapter.sendActionBar(attacker, "§c[ArgusAC] 检测到异常战斗行为，近战攻击已被暂时抑制！");
            adapter.playSound(attacker, attacker.getLocation(), "BLOCK_ANVIL_LAND", 0.8f, 1.6f);
        }
    }
}

