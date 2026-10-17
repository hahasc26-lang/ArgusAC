package com.example.argusac.history;

import com.example.argusac.compat.CompatBoundingBox;
import org.bukkit.Location;

/**
 * 实体历史碰撞箱与位置快照 (HistoricalBox)
 * 记录特定时间戳下实体的三维轴对齐包围盒 (AABB)，用于网络延迟回溯补偿
 */
public class HistoricalBox {

    private final CompatBoundingBox box;
    private final Location centerLocation;
    private final long timestamp;

    public HistoricalBox(CompatBoundingBox box, Location centerLocation, long timestamp) {
        this.box = box;
        this.centerLocation = centerLocation;
        this.timestamp = timestamp;
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

    public boolean isExpired(long maxAgeMs) {
        return System.currentTimeMillis() - timestamp > maxAgeMs;
    }
}
