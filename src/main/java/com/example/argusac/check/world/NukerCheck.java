package com.example.argusac.check.world;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.Deque;

/**
 * 范围/群破坏检测 (NukerCheck)
 * 检测单 tick 内破坏多个方块，或 1 秒内异常高频连破方块的炸矿/全域清理作弊
 */
public class NukerCheck extends AbstractCheck {

    private int maxBreaksPerTick;
    private int maxBreaksPerSecond;

    public NukerCheck(ArgusACPlugin plugin) {
        super(plugin, "Nuker", CheckType.WORLD);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxBreaksPerTick = getChecksConfig().getInt(path + "max-breaks-per-tick", 2);
        this.maxBreaksPerSecond = getChecksConfig().getInt(path + "max-breaks-per-second", 15);
    }

    public void handleBreak(Player player, BlockBreakEvent event) {
        if (!enabled || player == null) {
            return;
        }

        // 创造模式挖掘豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        long currentTick;
        try {
            currentTick = Bukkit.getCurrentTick();
        } catch (Throwable t) {
            currentTick = System.currentTimeMillis() / 50L;
        }
        long lastTick = data.getLastBreakTick();

        if (currentTick == lastTick) {
            data.setBreaksThisTick(data.getBreaksThisTick() + 1);
        } else {
            data.setLastBreakTick(currentTick);
            data.setBreaksThisTick(1);
        }

        // 1. 同一游戏刻 (Tick) 内并发破坏多个非零硬度实体方块 (排除双层花草门床自然联动与瞬破网络轻微堆包)
        float hardness = com.example.argusac.compat.VersionAdapterFactory.getAdapter().getBlockHardness(event.getBlock());
        int allowedBreaksThisTick = (hardness <= 0.6f) ? Math.max(maxBreaksPerTick, 3) : maxBreaksPerTick;
        if (hardness > 0.0f && data.getBreaksThisTick() > allowedBreaksThisTick) {
            event.setCancelled(true);
            flag(player, 2, String.format("Multi-break in single tick count=%d (limit=%d)", data.getBreaksThisTick(), allowedBreaksThisTick));
            return;
        }

        // 2. 1秒内累计破坏方块数
        long now = System.currentTimeMillis();
        Deque<Long> breakQueue = data.getBreakTimestamps();
        breakQueue.addLast(now);
        while (!breakQueue.isEmpty() && now - breakQueue.peekFirst() > 1000L) {
            breakQueue.pollFirst();
        }

        // 急迫效果加成 (Haste I/II 信标下允许合法快速连破)
        int effectiveLimit = maxBreaksPerSecond;
        if (com.example.argusac.compat.VersionAdapterFactory.getAdapter().hasPotionEffect(player, com.example.argusac.compat.CompatPotionType.HASTE)) {
            effectiveLimit += 10;
        }

        if (breakQueue.size() > effectiveLimit) {
            event.setCancelled(true);
            flag(player, 1, String.format("High Break CPS count=%d/s, limit=%d", breakQueue.size(), effectiveLimit));
        }
    }
}
