package com.example.argusac.check.world;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
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

    public FastBreakCheck(ArgusACPlugin plugin) {
        super(plugin, "FastBreak", CheckType.WORLD);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        this.timeLeniency = getChecksConfig().getDouble("checks." + name + ".time-leniency", 0.20);
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
        float hardness = VersionAdapterFactory.getAdapter().getBlockHardness(block);
        if (hardness < 1.5f) {
            // 极低硬度方块 (花草、火把、泥土等) 允许秒破，不作过严苛检测
            return;
        }

        long now = System.currentTimeMillis();
        long digStart = data.getDiggingStartTime();
        long lastBreakTime = data.getLastBreakTimestamp();
        long referenceStart = digStart > 0 ? digStart : lastBreakTime;
        if (lastBreakTime > referenceStart) {
            referenceStart = lastBreakTime;
        }
        long elapsed = (referenceStart > 0 && now >= referenceStart) ? (now - referenceStart) : 5000L;
        data.setLastBreakTimestamp(now);
        data.setDiggingStartTime(now);

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 挖掘疲劳检测：结合挖掘疲劳等级、工具效率附魔 (Efficiency) 与急迫效果 (Haste) 动态计算合理耗时底线，
        // 避免持有效率 V 镐在轻度挖掘疲劳 I 下挖掘普通矿石 (原版仅需 ~450ms) 被一刀切误判
        if (adapter.hasPotionEffect(player, CompatPotionType.MINING_FATIGUE) && hardness > 2.0f) {
            int fatigueAmp = adapter.getPotionAmplifier(player, CompatPotionType.MINING_FATIGUE);
            int efficiency = adapter.getEfficiencyLevel(player);
            boolean hasHaste = adapter.hasPotionEffect(player, CompatPotionType.HASTE);
            long fatigueThreshold = calculateMiningFatigueThresholdMs(fatigueAmp, efficiency, hasHaste, timeLeniency);
            if (elapsed < fatigueThreshold) {
                flag(player, 2, String.format("FastBreak under Mining Fatigue block=%s, elapsed=%dms, min=%dms",
                        mat, elapsed, fatigueThreshold));
                event.setCancelled(true);
                return;
            }
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

    /**
     * 计算挖掘疲劳 (Mining Fatigue) 下的最小允许挖掘毫秒阈值
     * 综合考虑挖掘疲劳等级、效率附魔等级、急迫效果及配置的宽容度比例 (timeLeniency)
     */
    public static long calculateMiningFatigueThresholdMs(int fatigueAmplifier, int efficiencyLevel,
                                                         boolean hasHaste, double timeLeniency) {
        if (fatigueAmplifier < 0) {
            return 0L;
        }
        long baseMs;
        if (fatigueAmplifier == 0) {
            // 挖掘疲劳 I (速度倍率 0.3x)：效率 V 钻石/下界合金镐挖石头/矿石原版约需 350~500ms
            baseMs = (efficiencyLevel >= 4) ? 250L : (efficiencyLevel >= 1 ? 450L : 850L);
        } else if (fatigueAmplifier == 1) {
            // 挖掘疲劳 II (速度倍率 0.09x)
            baseMs = (efficiencyLevel >= 4) ? 750L : 1400L;
        } else {
            // 挖掘疲劳 III+ (远古守卫者，速度倍率 <= 0.0027x)
            baseMs = 2500L;
        }
        if (hasHaste) {
            baseMs = Math.round(baseMs * 0.70);
        }
        double clampedLeniency = Math.max(0.0, Math.min(0.60, timeLeniency));
        return Math.max(150L, Math.round(baseMs * (1.0 - clampedLeniency)));
    }
}
