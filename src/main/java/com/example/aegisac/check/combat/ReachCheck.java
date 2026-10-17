package com.example.aegisac.check.combat;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.CheckType;
import com.example.aegisac.check.AbstractCheck;
import com.example.aegisac.data.PlayerData;
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

    public ReachCheck(AegisACPlugin plugin) {
        super(plugin, "Reach", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxReach = plugin.getConfig().getDouble(path + "max-reach", 3.10);
        this.maxPingReach = plugin.getConfig().getDouble(path + "max-ping-reach", 3.50);
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

        Location eyeLocation = attacker.getEyeLocation();
        if (eyeLocation.getWorld() != victim.getWorld()) {
            return;
        }

        // 精确计算视线起点到目标跨版本碰撞箱 (CompatBoundingBox) 最短物理距离
        com.example.aegisac.compat.CompatBoundingBox box = com.example.aegisac.compat.VersionAdapterFactory.getAdapter().getEntityBoundingBox(victim);
        double distanceToBox = box.distanceTo(eyeLocation);

        int ping = Math.max(0, com.example.aegisac.compat.VersionAdapterFactory.getAdapter().getPing(attacker));
        // 延迟补偿：每 100ms 延迟放宽约 0.08 格
        double dynamicLimit = maxReach + Math.min(0.40, (ping / 100.0) * 0.08);
        dynamicLimit = Math.min(dynamicLimit, maxPingReach);

        if (distanceToBox > dynamicLimit) {
            // 超距攻击直接取消本次伤害事件生效
            event.setCancelled(true);
            flag(attacker, 1, String.format("dist=%.2f, limit=%.2f, ping=%d", distanceToBox, dynamicLimit, ping));
        }
    }
}
