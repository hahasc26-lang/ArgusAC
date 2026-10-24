package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.combat.CombatVerdict;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import com.example.argusac.history.HistoricalBox;
import com.example.argusac.util.MathUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 杀戮光环检测 (KillAuraCheck)
 * 采用多维度算法矩阵检测异常攻击视角、盲区发包、穿墙透打与自瞄突变。
 * 全面支持 Netty 进包阶段 (PacketPlayInUseEntity) 即时初筛裁决与分级处置策略。
 */
public class KillAuraCheck extends AbstractCheck {

    private double maxAngle;
    private boolean raytraceBlocks;
    private long targetSwitchMs;
    private boolean blindspotCheck;
    private boolean snapCheck;
    private double snapAngleThreshold;

    // 视角夹角微量超出缓冲度（在此区间内判定为低确信仅静默 Drop，不累加严重 VL）
    private static final double ANGLE_LOW_CONFIDENCE_MARGIN = 6.0;

    public KillAuraCheck(ArgusACPlugin plugin) {
        super(plugin, "KillAura", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxAngle = getChecksConfig().getDouble(path + "max-angle", 68.0);
        this.raytraceBlocks = getChecksConfig().getBoolean(path + "raytrace-blocks", true);
        this.targetSwitchMs = getChecksConfig().getLong(path + "target-switch-ms", 80L);
        this.blindspotCheck = getChecksConfig().getBoolean(path + "blindspot-check", true);
        this.snapCheck = getChecksConfig().getBoolean(path + "snap-check", true);
        this.snapAngleThreshold = getChecksConfig().getDouble(path + "snap-angle-threshold", 45.0);
    }

    /**
     * 核心数据包层/事件层通用 KillAura 合规性裁决 (Netty 线程安全)
     */
    public CombatVerdict evaluateAttack(Player attacker, PlayerData data, Entity victim) {
        if (!enabled || attacker == null || !(victim instanceof LivingEntity livingVictim)) {
            return CombatVerdict.pass();
        }

        HistoricalBox victimSnap = (plugin.getHistoryTracker() != null)
                ? plugin.getHistoryTracker().getLatestSnapshot(livingVictim.getUniqueId())
                : null;
        boolean victimDead = (victimSnap != null) ? victimSnap.isDead() : livingVictim.isDead();

        // 死亡状态攻击与攻击自身漏洞拦截 (高确信)
        if (attacker.isDead() || victimDead) {
            return CombatVerdict.highConfidence(this, 3, "Attack while dead");
        }
        if (attacker.equals(livingVictim)) {
            return CombatVerdict.highConfidence(this, 3, "Self damage packet exploit");
        }

        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return CombatVerdict.pass();
        }

        if (data == null || data.isInGracePeriod()) {
            return CombatVerdict.pass();
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        Location eyeLoc = resolveAttackerEyeLocation(attacker, data);
        int ping = Math.max(0, adapter.getPing(attacker));

        // 基于网络往返延迟回溯受害者在攻击者屏幕上的历史真实坐标 (GrimAC Lag Compensated Position)
        Location victimLoc = (plugin.getHistoryTracker() != null)
                ? plugin.getHistoryTracker().getCompensatedLocation(livingVictim, ping)
                : livingVictim.getLocation();
        if (eyeLoc == null || victimLoc == null || eyeLoc.getWorld() != victimLoc.getWorld()) {
            return CombatVerdict.pass();
        }

        double victimEyeHeight = (victimSnap != null) ? victimSnap.getEyeHeight() : 1.62;
        if (victimSnap == null && Bukkit.isPrimaryThread()) {
            try {
                victimEyeHeight = livingVictim.getEyeHeight();
            } catch (Throwable ignored) {
            }
        }
        Location victimEye = victimLoc.clone().add(0, victimEyeHeight, 0);

        Vector eyeDirection = eyeLoc.getDirection();
        Vector toTarget = victimEye.toVector().subtract(eyeLoc.toVector());
        if (toTarget.lengthSquared() < 0.01) {
            return CombatVerdict.pass();
        }

        double distance = eyeLoc.distance(victimEye);
        double angle = MathUtil.getAngleBetweenVectors(eyeDirection, toTarget);

        // 1. 盲区与背身反向攻击拦截 (Blindspot Check: 完全背对或偏离超过 90 度发包攻击 -> 高确信明显作弊)
        if (blindspotCheck && distance > 0.8 && angle > 90.0) {
            return CombatVerdict.highConfidence(this, 3,
                    String.format("Blindspot Attack angle=%.1f deg (>90.0) (Backtracked)", angle));
        }

        // 2. 视角夹角检测 (Angle Check，基于回溯坐标对齐，支持分级处置)
        if (distance > 1.0 && angle > maxAngle) {
            double angleExcess = angle - maxAngle;
            String detail = String.format("Impossible Angle angle=%.1f deg, max=%.1f (Backtracked)", angle, maxAngle);
            if (angleExcess <= ANGLE_LOW_CONFIDENCE_MARGIN) {
                return CombatVerdict.lowConfidenceDrop(this, detail);
            }
            return CombatVerdict.highConfidence(this, 2, detail);
        }

        // 3. 准星光线脱靶命中检测 (Silent Aim / Aimbot RayTrace Miss)
        if (distance > 1.2 && angle > 65.0) {
            double distanceToCompensatedBox = (plugin.getHistoryTracker() != null)
                    ? plugin.getHistoryTracker().getCompensatedMinDistance(eyeLoc, livingVictim, ping)
                    : adapter.getEntityBoundingBox(livingVictim).distanceTo(eyeLoc);

            if (distanceToCompensatedBox > distance + 1.2) {
                return CombatVerdict.highConfidence(this, 2,
                        String.format("Aimbot Ray Miss angle=%.1f, ping=%d (Backtracked)", angle, ping));
            }
        }

        // 4. 隔墙穿透攻击检测 (RayTrace Blocks，跨版本安全适配)
        if (raytraceBlocks && distance > 1.2) {
            try {
                if (adapter.isRayOccluded(eyeLoc, victimEye)) {
                    return CombatVerdict.highConfidence(this, 2, "Attack Through Wall");
                }
            } catch (Throwable ignored) {
            }
        }

        // 5. 准星瞬间锁头突变检测 (Sudden Angle Snap)
        if (snapCheck && distance > 1.0) {
            float lastYaw = data.getLastLookYaw();
            float currentYaw = eyeLoc.getYaw();
            float yawDelta = Math.abs(currentYaw - lastYaw);
            if (yawDelta > 180.0f) yawDelta = 360.0f - yawDelta;

            // 攻击瞬间产生大跨度偏转且立刻命中：区分人类单次快速拉枪甩枪 (Flick Shot) 与自瞄程序
            if (yawDelta > snapAngleThreshold && angle < 25.0) {
                long snapNow = System.currentTimeMillis();
                int snaps = data.recordSnap(snapNow);
                // 仅对连续两次以上异常吸附或极端非人自瞄突变 (>110度且精准直指) 执行高确信拦截
                if (snaps >= 2 || (yawDelta > 110.0f && angle < 12.0)) {
                    return CombatVerdict.highConfidence(this, 2,
                            String.format("Aimbot Snap deltaYaw=%.1f, angle=%.1f, count=%d", yawDelta, angle, snaps));
                }
            } else {
                data.resetSnap();
            }
        }

        // 6. 极速切换不同目标检测 (Multi-Target Switch)
        long now = System.currentTimeMillis();
        UUID lastTarget = data.getLastAttackedTarget();
        long lastAttack = data.getLastAttackTime();

        if (lastTarget != null && !lastTarget.equals(victim.getUniqueId())) {
            long switchInterval = now - lastAttack;
            if (switchInterval < targetSwitchMs) {
                String detail = String.format("Rapid Switch interval=%dms", switchInterval);
                // 若仅比阈值快不到 15ms，视作微量超出仅静默丢弃；若极速多目标切换则高确信计分
                if (targetSwitchMs - switchInterval <= 15L) {
                    return CombatVerdict.lowConfidenceDrop(this, detail);
                }
                return CombatVerdict.highConfidence(this, 2, detail);
            }
        }

        data.setLastAttackedTarget(victim.getUniqueId());
        data.setLastAttackTime(now);
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

    private Location resolveAttackerEyeLocation(Player attacker, PlayerData data) {
        double eyeHeight = 1.62;
        try {
            eyeHeight = attacker.getEyeHeight();
        } catch (Throwable ignored) {
        }
        if (data != null && data.getPhysicsState() != null && data.getPhysicsState().hasLastPosition() && attacker.getWorld() != null) {
            var ps = data.getPhysicsState();
            return new Location(attacker.getWorld(), ps.getLastX(), ps.getLastY() + eyeHeight, ps.getLastZ(),
                    ps.getLastYaw(), ps.getLastPitch());
        }
        if (data != null && data.getToLocation() != null) {
            return data.getToLocation().clone().add(0, eyeHeight, 0);
        }
        return attacker.getEyeLocation();
    }
}
