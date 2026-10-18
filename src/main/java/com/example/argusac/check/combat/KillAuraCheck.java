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
 * 杀戮光环检测 (KillAuraCheck)
 * 采用多维度算法矩阵检测异常攻击视角、盲区发包、穿墙透打与自瞄突变，
 * 由 CombatMitigationHandler 执行即时纯静默伤害与交互阻断，取消非原版侵入式缴械。
 */
public class KillAuraCheck extends AbstractCheck {

    private double maxAngle;
    private boolean raytraceBlocks;
    private long targetSwitchMs;
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
        this.maxAngle = getChecksConfig().getDouble(path + "max-angle", 68.0);
        this.raytraceBlocks = getChecksConfig().getBoolean(path + "raytrace-blocks", true);
        this.targetSwitchMs = getChecksConfig().getLong(path + "target-switch-ms", 80L);
        this.blindspotCheck = getChecksConfig().getBoolean(path + "blindspot-check", true);
        this.snapCheck = getChecksConfig().getBoolean(path + "snap-check", true);
        this.snapAngleThreshold = getChecksConfig().getDouble(path + "snap-angle-threshold", 45.0);
    }

    public void handleAttack(Player attacker, Entity victim, EntityDamageByEntityEvent event) {
        if (!enabled || attacker == null || !(victim instanceof LivingEntity livingVictim)) {
            return;
        }

        // 死亡状态攻击与攻击自身漏洞拦截
        if (attacker.isDead() || livingVictim.isDead()) {
            plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 3, "Attack while dead");
            return;
        }
        if (attacker.equals(livingVictim)) {
            plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 3, "Self damage packet exploit");
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
            plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 3,
                    String.format("Blindspot Attack angle=%.1f deg (>90.0) (Backtracked)", angle));
            return;
        }

        // 2. 视角夹角检测 (Angle Check，基于回溯坐标对齐)
        // 仅在距离大于 1.0 格时检测极限脱节夹角 (贴脸时因碰撞箱宽大正常挥刀夹角变化剧烈)
        if (distance > 1.0 && angle > maxAngle) {
            plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 2,
                    String.format("Impossible Angle angle=%.1f deg, max=%.1f (Backtracked)", angle, maxAngle));
            return;
        }

        // 3. 准星光线脱靶命中检测 (Silent Aim / Aimbot RayTrace Miss)
        if (distance > 1.2 && angle > 65.0) {
            double distanceToCompensatedBox = (plugin.getHistoryTracker() != null)
                    ? plugin.getHistoryTracker().getCompensatedMinDistance(eyeLoc, livingVictim, ping)
                    : adapter.getEntityBoundingBox(livingVictim).distanceTo(eyeLoc);

            if (distanceToCompensatedBox > distance + 1.2) {
                plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 2,
                        String.format("Aimbot Ray Miss angle=%.1f, ping=%d (Backtracked)", angle, ping));
                return;
            }
        }

        // 4. 隔墙穿透攻击检测 (RayTrace Blocks，跨版本安全适配)
        if (raytraceBlocks && distance > 1.2) {
            if (adapter.isRayOccluded(eyeLoc, victimEye)) {
                plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 2, "Attack Through Wall");
                return;
            }
        }

        // 5. 准星瞬间锁头突变检测 (Sudden Angle Snap)
        if (snapCheck && distance > 1.0) {
            float lastYaw = data.getLastLookYaw();
            float currentYaw = attacker.getLocation().getYaw();
            float yawDelta = Math.abs(currentYaw - lastYaw);
            if (yawDelta > 180.0f) yawDelta = 360.0f - yawDelta;

            // 攻击瞬间产生大跨度偏转且立刻命中：区分人类单次快速拉枪甩枪 (Flick Shot) 与自瞄程序
            if (yawDelta > snapAngleThreshold && angle < 25.0) {
                long snapNow = System.currentTimeMillis();
                int snaps = data.recordSnap(snapNow);
                // 仅对连续两次以上异常吸附或极端非人自瞄突变 (>110度且精准直指) 执行拦截
                if (snaps >= 2 || (yawDelta > 110.0f && angle < 12.0)) {
                    plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 2,
                            String.format("Aimbot Snap deltaYaw=%.1f, angle=%.1f, count=%d", yawDelta, angle, snaps));
                    return;
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
                plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, livingVictim, this, 2,
                        String.format("Rapid Switch interval=%dms", switchInterval));
                return;
            }
        }

        data.setLastAttackedTarget(victim.getUniqueId());
        data.setLastAttackTime(now);
    }
}
