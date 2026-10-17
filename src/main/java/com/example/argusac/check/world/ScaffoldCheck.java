package com.example.argusac.check.world;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * 自动搭桥检测 (ScaffoldCheck)
 * 检测异常放置距离与稳定接地状态下的异常放置角度
 */
public class ScaffoldCheck extends AbstractCheck {

    private double minPitch;

    public ScaffoldCheck(ArgusACPlugin plugin) {
        super(plugin, "Scaffold", CheckType.WORLD);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.minPitch = plugin.getConfig().getDouble(path + "min-pitch", 60.0);
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

        // 核心修复：检查放置的方块是否确实位于玩家正脚底下方
        // 必须同时校验 Y 坐标与 X/Z 水平投影距离，避免玩家在脚下一层的前方/侧方合法放置方块时被误判 Scaffold
        double dx = (placed.getX() + 0.5) - playerLoc.getX();
        double dz = (placed.getZ() + 0.5) - playerLoc.getZ();
        double hDistSq = dx * dx + dz * dz;
        boolean placedDirectlyUnderFeet = (placed.getY() == playerLoc.getBlockY() - 1) && (hDistSq <= 0.65 * 0.65);

        if (!placedDirectlyUnderFeet) {
            return;
        }

        Location from = data.getFromLocation();
        Location to = data.getToLocation();
        boolean verticallyStable = from == null || to == null
                || from.getWorld() != to.getWorld()
                || Math.abs(to.getY() - from.getY()) <= 0.08;

        // 跳跃和台阶上升过程中经常需要在脚下搭方块
        if (!data.isOnGround() || !verticallyStable) {
            return;
        }

        // 2. 异常放置角度 (平视甚至仰头却能直接凭空在正脚底下放置方块)
        if (pitch < (float) minPitch) {
            flag(player, 2, String.format("Impossible pitch scaffold pitch=%.1f, min=%.1f", pitch, minPitch));
        }
    }
}
