package com.example.argusac.history;

import com.example.argusac.compat.CompatBoundingBox;
import org.bukkit.Location;

/**
 * 实体历史碰撞箱与位置快照 (HistoricalBox)
 * 记录特定时间戳下实体的三维轴对齐包围盒 (AABB) 及视角高度，用于网络延迟回溯补偿与 Netty 线程无锁检测
 */
public class HistoricalBox {

    private final CompatBoundingBox box;
    private final Location centerLocation;
    private final long timestamp;
    private final double eyeHeight;
    private final boolean dead;

    public HistoricalBox(CompatBoundingBox box, Location centerLocation, long timestamp) {
        this(box, centerLocation, timestamp, 1.62, false);
    }

    public HistoricalBox(CompatBoundingBox box, Location centerLocation, long timestamp, double eyeHeight, boolean dead) {
        this.box = box;
        this.centerLocation = centerLocation;
        this.timestamp = timestamp;
        this.eyeHeight = eyeHeight;
        this.dead = dead;
    }

    public CompatBoundingBox getBox() {
        return box;
    }

    public Location getCenterLocation() {
        return centerLocation;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public double getEyeHeight() {
        return eyeHeight;
    }

    public boolean isDead() {
        return dead;
    }

    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - timestamp > maxAgeMs;
    }
}
