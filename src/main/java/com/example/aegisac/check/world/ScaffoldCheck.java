package com.example.aegisac.check.world;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.CheckType;
import com.example.aegisac.check.AbstractCheck;
import com.example.aegisac.data.PlayerData;
import com.example.aegisac.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 自动搭桥检测 (ScaffoldCheck)
 * 检测玩家非法向脚下空中倒退自动铺路、无潜行疾跑搭桥与异常放置角度
 */
public class ScaffoldCheck extends AbstractCheck {

    private double minPitch;
    private long fastPlacementDelayMs;

    public ScaffoldCheck(AegisACPlugin plugin) {
        super(plugin, "Scaffold", CheckType.WORLD);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.minPitch = plugin.getConfig().getDouble(path + "min-pitch", 70.0);
        this.fastPlacementDelayMs = plugin.getConfig().getLong(path + "fast-placement-delay-ms", 120L);
    }

    public void handlePlace(Player player, BlockPlaceEvent event) {
        if (!enabled || player == null) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        Block placed = event.getBlockPlaced();
        Location playerLoc = player.getLocation();
        float pitch = playerLoc.getPitch();

        // 1. 放置超距检测 (FarPlace)：生存模式倒退铺路放方块最大中心距离放宽至 6.20 格
        Location eye = player.getEyeLocation();
        Location blockCenter = placed.getLocation().add(0.5, 0.5, 0.5);
        if (eye.getWorld() == blockCenter.getWorld() && eye.distance(blockCenter) > 6.20) {
            event.setCancelled(true);
            flag(player, 2, String.format("FarPlace dist=%.2f, max=6.20", eye.distance(blockCenter)));
            return;
        }

        // 检查放置的方块是否位于玩家正脚下下方
        boolean placedBelowFeet = (placed.getY() == playerLoc.getBlockY() - 1);

        // 2. 异常放置角度 (平视甚至仰头但能放置正脚下方块)
        if (placedBelowFeet && pitch < (float) minPitch) {
            event.setCancelled(true);
            flag(player, 2, String.format("Impossible pitch scaffold pitch=%.1f, min=%.1f", pitch, minPitch));
            return;
        }

        // 3. 疾跑状态下连续凌空向脚下铺路 (正常情况下疾跑向后搭桥极难不潜行)
        long now = System.currentTimeMillis();
        long lastPlace = data.getLastPlaceTime();
        long interval = now - lastPlace;
        data.setLastPlaceTime(now);

        double horizontalSpeed = MathUtil.getHorizontalDistance(data.getFromLocation(), data.getToLocation());
        if (placedBelowFeet && player.isSprinting() && !player.isSneaking() && horizontalSpeed > 0.15) {
            if (interval < fastPlacementDelayMs) {
                event.setCancelled(true);
                flag(player, 1, String.format("Sprinting Scaffold interval=%dms, pitch=%.1f", interval, pitch));
            }
        }
    }
}
