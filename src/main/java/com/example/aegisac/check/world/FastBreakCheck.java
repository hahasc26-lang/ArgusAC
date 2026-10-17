package com.example.aegisac.check.world;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.CheckType;
import com.example.aegisac.check.AbstractCheck;
import com.example.aegisac.data.PlayerData;
import com.example.aegisac.compat.CompatPotionType;
import com.example.aegisac.compat.VersionAdapter;
import com.example.aegisac.compat.VersionAdapterFactory;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * 快速破坏检测 (FastBreakCheck)
 * 检测玩家非法缩短挖掘高硬度方块所需理论耗时的作弊行为
 */
public class FastBreakCheck extends AbstractCheck {

    private double timeLeniency;

    public FastBreakCheck(AegisACPlugin plugin) {
        super(plugin, "FastBreak", CheckType.WORLD);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        this.timeLeniency = plugin.getConfig().getDouble("checks." + name + ".time-leniency", 0.20);
    }

    public void handleBreak(Player player, BlockBreakEvent event) {
        if (!enabled || player == null) {
            return;
        }

        // 创造模式瞬破方块，豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        Block block = event.getBlock();
        Material mat = block.getType();

        // 1. 挖掘超距检测 (Block Break Reach)：生存基准 4.5 格，含对角线半径(0.86)与后退网络延迟，阈值设为 6.20
        Location eye = player.getEyeLocation();
        Location blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
        if (eye.getWorld() == blockCenter.getWorld()) {
            double breakDist = eye.distance(blockCenter);
            if (breakDist > 6.20) {
                flag(player, 2, String.format("FarBreak dist=%.2f, max=6.20", breakDist));
                event.setCancelled(true);
                return;
            }
        }

        // 极高硬度方块 (如黑曜石、哭泣黑曜石、远古残骸、深板岩等)
        float hardness = block.getType().getHardness();
        if (hardness < 1.5f) {
            // 极低硬度方块 (花草、火把、泥土等) 允许秒破，不作过严苛检测
            return;
        }

        long now = System.currentTimeMillis();
        long lastBreakTime = data.getLastBreakTimestamp();
        long elapsed = (lastBreakTime > 0) ? (now - lastBreakTime) : 5000L;
        data.setLastBreakTimestamp(now);

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 挖掘疲劳检测：如果带有挖掘疲劳效果，几乎不可能以正常速度破坏方块
        boolean hasFatigue = adapter.hasPotionEffect(player, CompatPotionType.MINING_FATIGUE);
        // 应用宽容系数：阈值 * (1 + timeLeniency)
        long fatigueThreshold = (long) (1000L * (1.0 + timeLeniency));
        if (hasFatigue && hardness > 2.0f && elapsed < fatigueThreshold) {
            flag(player, 2, String.format("FastBreak under Mining Fatigue block=%s, elapsed=%dms", mat, elapsed));
            event.setCancelled(true);
            return;
        }

        // 黑曜石 (硬度 50.0)，即便是最佳工具+效率V也需要约 1.5 秒以上
        String matName = mat.name();
        if (mat == Material.OBSIDIAN || "CRYING_OBSIDIAN".equals(matName) || "ANCIENT_DEBRIS".equals(matName)) {
            long obsidianThreshold = (long) (800L * (1.0 - timeLeniency));
            if (elapsed < obsidianThreshold && !adapter.hasPotionEffect(player, CompatPotionType.HASTE)) {
                flag(player, 3, String.format("Instant obsidian break block=%s, elapsed=%dms", mat, elapsed));
                event.setCancelled(true);
            }
        }
    }
}
