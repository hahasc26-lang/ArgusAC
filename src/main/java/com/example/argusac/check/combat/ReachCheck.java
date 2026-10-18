package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * 攻击距离检测 (ReachCheck)
 * 检测超出合法范围的超距攻击，综合考虑玩家延迟补偿与目标碰撞箱边缘
 */
public class ReachCheck extends AbstractCheck {

    private double maxReach;
    private double maxPingReach;

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

    public void handleAttack(Player attacker, Entity victim, EntityDamageByEntityEvent event) {
        if (!enabled || attacker == null || !(victim instanceof LivingEntity)) {
            return;
        }

        // 创造模式合法攻击距离可达 5.0，豁免
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(attacker);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        com.example.argusac.compat.VersionAdapter adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        Location eyeLocation = attacker.getEyeLocation();
        if (eyeLocation.getWorld() != victim.getWorld()) {
            return;
        }

        int ping = Math.max(0, adapter.getPing(attacker));

        // 1. 基于实体历史碰撞盒回溯的延迟补偿物理距离计算 (GrimAC-Grade Lag Compensated Reach)
        double distanceToBox;
        if (plugin.getHistoryTracker() != null) {
            distanceToBox = plugin.getHistoryTracker().getCompensatedMinDistance(eyeLocation, victim, ping);
        } else {
            com.example.argusac.compat.CompatBoundingBox box = adapter.getEntityBoundingBox(victim);
            distanceToBox = box.distanceTo(eyeLocation);
        }

        // 若历史回溯追踪器正常运转，严格收拢在真实物理极限；若未启用则使用带 Ping 上限的降级兜底
        double dynamicLimit = (plugin.getHistoryTracker() != null)
                ? maxReach
                : Math.min(maxReach + Math.min(0.40, (ping / 100.0) * 0.08), maxPingReach);

        if (distanceToBox > dynamicLimit) {
            // 超距攻击由 CombatMitigationHandler 进行统一即时纯静默拦截
            plugin.getCombatMitigationHandler().mitigateDamage(
                    event,
                    attacker,
                    victim,
                    this,
                    1,
                    String.format("dist=%.3f, limit=%.2f, ping=%d (Backtracked)", distanceToBox, dynamicLimit, ping)
            );
        }
    }
}
