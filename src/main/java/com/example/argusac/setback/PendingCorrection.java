package com.example.argusac.setback;

import org.bukkit.Location;

import java.util.UUID;

/**
 * 待同步网络校正数据模型 (PendingCorrection)
 * 记录服务端下发给客户端的回退目标坐标、有效时限与单调递增序列号
 */
public class PendingCorrection {

    private final double targetX;
    private final double targetY;
    private final double targetZ;
    private final UUID worldUid;
    private final long expireTime;
    private final long sequenceId;

    public PendingCorrection(double targetX, double targetY, double targetZ, UUID worldUid, long expireTime, long sequenceId) {
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.worldUid = worldUid;
        this.expireTime = expireTime;
        this.sequenceId = sequenceId;
    }

    public long getSequenceId() {
        return sequenceId;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expireTime;
    }

    public boolean matchesCorrection(Location loc) {
        if (loc == null || loc.getWorld() == null || isExpired()) {
            return false;
        }
        if (!loc.getWorld().getUID().equals(worldUid)) {
            return false;
        }
        double dx = loc.getX() - targetX;
        double dy = loc.getY() - targetY;
        double dz = loc.getZ() - targetZ;
        return (dx * dx + dy * dy + dz * dz) <= 0.000025; // 5 mm 协议浮点公差
    }

    public double getTargetX() {
        return targetX;
    }

    public double getTargetY() {
        return targetY;
    }

    public double getTargetZ() {
        return targetZ;
    }

    public UUID getWorldUid() {
        return worldUid;
    }

    public long getExpireTime() {
        return expireTime;
    }
}
