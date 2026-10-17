package com.example.aegisac.compat.impl;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * 轻量级主线程 Tick 采样器：在缺乏 Bukkit.getTPS() 的纯 Spigot 服务端上作为零开销兜底
 */
public final class TickMonitor {

    private static final int HISTORY_TICKS = 600; // 记录最近 30 秒 (600 ticks)
    private static final long[] TICK_DURATIONS = new long[HISTORY_TICKS];
    private static int tickIndex = 0;
    private static long lastTickTime = System.currentTimeMillis();
    private static boolean running = false;

    private TickMonitor() {}

    public static synchronized void start(Plugin plugin) {
        if (running) return;
        running = true;
        lastTickTime = System.currentTimeMillis();

        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long elapsed = now - lastTickTime;
                lastTickTime = now;

                TICK_DURATIONS[tickIndex % HISTORY_TICKS] = elapsed;
                tickIndex++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public static double getEstimatedTps() {
        int count = Math.min(tickIndex, HISTORY_TICKS);
        if (count == 0) return 20.0;

        long totalElapsed = 0;
        for (int i = 0; i < count; i++) {
            totalElapsed += TICK_DURATIONS[i];
        }

        if (totalElapsed <= 0) return 20.0;
        double avgTickMs = (double) totalElapsed / count;
        double tps = 1000.0 / Math.max(50.0, avgTickMs);
        return Math.min(20.0, Math.max(0.0, tps));
    }
}
