package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.example.argusac.util.MathUtil;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 杀戮光环检测 (KillAuraCheck)
 * 检测攻击脱离视角角度 (Angle)、快速多目标切换 (Switch) 以及隔墙打击 (RayTrace)
 */
public class KillAuraCheck extends AbstractCheck {

    private double maxAngle;
    private boolean raytraceBlocks;
    private long targetSwitchMs;

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
    }

    public void handleAttack(Player attacker, Entity victim, EntityDamageByEntityEvent event) {
        if (!enabled || attacker == null || !(victim instanceof LivingEntity livingVictim)) {
            return;
        }

        // 死亡状态攻击与攻击自身漏洞拦截
        if (attacker.isDead() || livingVictim.isDead()) {
            flag(attacker, 3, "Attack while dead");
            event.setCancelled(true);
            return;
        }
        if (attacker.equals(livingVictim)) {
            flag(attacker, 3, "Self damage packet exploit");
            event.setCancelled(true);
            return;
        }

        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(attacker);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        Location eyeLoc = attacker.getEyeLocation();
        Location victimEye = livingVictim.getEyeLocation();

        if (eyeLoc.getWorld() != victimEye.getWorld()) {
            return;
        }

        // 1. 视角夹角检测 (Angle Check)
        Vector eyeDirection = eyeLoc.getDirection();
        Vector toTarget = victimEye.toVector().subtract(eyeLoc.toVector());
        if (toTarget.lengthSquared() < 0.01) {
            return;
        }

        double distance = eyeLoc.distance(victimEye);
        double angle = MathUtil.getAngleBetweenVectors(eyeDirection, toTarget);

        // 仅在距离大于 1.0 格时检测极限脱节夹角 (贴脸时因碰撞箱宽大正常挥刀夹角变化剧烈)
        if (distance > 1.0 && angle > maxAngle) {
            event.setCancelled(true);
            flag(attacker, 2, String.format("Impossible Angle angle=%.1f deg, max=%.1f", angle, maxAngle));
            return;
        }

        // 2. 准星光线脱靶命中检测 (Silent Aim / Aimbot RayTrace Miss)
        int ping = Math.max(0, com.example.argusac.compat.VersionAdapterFactory.getAdapter().getPing(attacker));
        double pingExpansion = Math.min(0.50, (ping / 100.0) * 0.12);
        if (distance > 1.2 && angle > 55.0) {
            com.example.argusac.compat.CompatBoundingBox expandedBox = com.example.argusac.compat.VersionAdapterFactory.getAdapter()
                    .getEntityBoundingBox(livingVictim)
                    .expand(0.40 + pingExpansion);
            if (expandedBox.distanceTo(eyeLoc) > distance + 1.0) {
                event.setCancelled(true);
                flag(attacker, 2, String.format("Aimbot Ray Miss angle=%.1f, ping=%d", angle, ping));
                return;
            }
        }

        // 3. 隔墙穿透攻击检测 (RayTrace Blocks)
        if (raytraceBlocks) {
            if (distance > 1.2) {
                RayTraceResult rayResult = eyeLoc.getWorld().rayTraceBlocks(
                        eyeLoc,
                        toTarget.clone().normalize(),
                        distance - 0.5,
                        FluidCollisionMode.NEVER,
                        true
                );
                if (rayResult != null && rayResult.getHitBlock() != null && rayResult.getHitBlock().getType().isOccluding()) {
                    event.setCancelled(true);
                    flag(attacker, 2, "Attack Through Wall: " + rayResult.getHitBlock().getType());
                    return;
                }
            }
        }

        // 4. 极速切换不同目标检测 (Multi-Target Switch)
        long now = System.currentTimeMillis();
        UUID lastTarget = data.getLastAttackedTarget();
        long lastAttack = data.getLastAttackTime();

        if (lastTarget != null && !lastTarget.equals(victim.getUniqueId())) {
            long switchInterval = now - lastAttack;
            if (switchInterval < targetSwitchMs) {
                flag(attacker, 1, String.format("Rapid Switch interval=%dms", switchInterval));
            }
        }

        data.setLastAttackedTarget(victim.getUniqueId());
        data.setLastAttackTime(now);
    }
}
