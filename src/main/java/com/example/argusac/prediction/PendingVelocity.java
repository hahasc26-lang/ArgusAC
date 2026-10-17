package com.example.argusac.prediction;

/**
 * 待处理的服务端下发击退/速度快照 (PendingVelocity)
 * 用于解决网络往返延迟 (RTT) 导致客户端尚未生效击退时的物理模拟偏差
 */
public class PendingVelocity {

    private final int id;
    private final double x;
    private final double y;
    private final double z;
    private final long createTime;
    private final long expiryTime;
    private boolean consumed = false;

    public PendingVelocity(int id, double x, double y, double z, long durationMs) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.createTime = System.currentTimeMillis();
        this.expiryTime = this.createTime + durationMs;
    }

    public int getId() {
        return id;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getExpiryTime() {
        return expiryTime;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTime;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public void setConsumed(boolean consumed) {
        this.consumed = consumed;
    }
}
