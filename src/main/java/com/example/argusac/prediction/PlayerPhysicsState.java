package com.example.argusac.prediction;

import org.bukkit.Location;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 玩家确定性物理仿真运行时状态 (PlayerPhysicsState)
 * 维护每个客户端 tick 的动量向量、上一跳跃状态、残差缓冲与服务端发出的击退队列
 */
public class PlayerPhysicsState {

    private final AtomicInteger velocityIdCounter = new AtomicInteger(0);

    // 上一 tick 计算拟合出的物理速度向量
    private double prevVx = 0.0;
    private double prevVy = 0.0;
    private double prevVz = 0.0;

    // 当前 tick 拟合速度
    private double currentVx = 0.0;
    private double currentVy = 0.0;
    private double currentVz = 0.0;

    // 上一包位置与朝向
    private double lastX = 0.0;
    private double lastY = 0.0;
    private double lastZ = 0.0;
    private float lastYaw = 0.0f;
    private float lastPitch = 0.0f;
    private boolean hasLastPosition = false;

    // 地面与跳跃状态
    private boolean wasOnGround = true;
    private boolean jumpedLastTick = false;
    private int ticksSinceJump = 999;

    // 残差累积缓冲区 (仿 GrimAC buffer 机制)
    private double horizontalBuffer = 0.0;
    private double verticalBuffer = 0.0;

    // 待处理击退队列
    private final Deque<PendingVelocity> pendingVelocities = new ConcurrentLinkedDeque<>();

    public PlayerPhysicsState() {}

    /**
     * 重置物理仿真状态（在传送、复活或登录时调用，防止动量跳变产生误报）
     */
    public synchronized void reset(double x, double y, double z, float yaw, float pitch) {
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastYaw = yaw;
        this.lastPitch = pitch;
        this.hasLastPosition = true;
        this.prevVx = 0.0;
        this.prevVy = 0.0;
        this.prevVz = 0.0;
        this.currentVx = 0.0;
        this.currentVy = 0.0;
        this.currentVz = 0.0;
        this.wasOnGround = true;
        this.jumpedLastTick = false;
        this.ticksSinceJump = 999;
        this.horizontalBuffer = 0.0;
        this.verticalBuffer = 0.0;
        this.pendingVelocities.clear();
        clearPredictionCache();
    }

    public synchronized void reset(Location loc) {
        if (loc != null) {
            reset(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        }
    }

    /**
     * 压入服务端下发给客户端的合法击退动量
     */
    public void addPendingVelocity(double x, double y, double z) {
        int id = velocityIdCounter.incrementAndGet();
        // 击退有效期赋予 1200ms (考虑高延迟波动网络)
        pendingVelocities.addLast(new PendingVelocity(id, x, y, z, 1200L));
    }

    /**
     * 清理过期或已被消费的击退动量
     */
    public void cleanExpiredVelocities() {
        pendingVelocities.removeIf(v -> v.isExpired() || v.isConsumed());
    }

    /**
     * 强行清空待处理击退队列 (用于传送与回退重置)
     */
    public void clearPendingVelocities() {
        this.pendingVelocities.clear();
    }

    public Deque<PendingVelocity> getPendingVelocities() {
        return pendingVelocities;
    }

    // ==================== Getter & Setter ====================

    public double getPrevVx() {
        return prevVx;
    }

    public void setPrevVx(double prevVx) {
        this.prevVx = prevVx;
    }

    public double getPrevVy() {
        return prevVy;
    }

    public void setPrevVy(double prevVy) {
        this.prevVy = prevVy;
    }

    public double getPrevVz() {
        return prevVz;
    }

    public void setPrevVz(double prevVz) {
        this.prevVz = prevVz;
    }

    public double getCurrentVx() {
        return currentVx;
    }

    public void setCurrentVx(double currentVx) {
        this.currentVx = currentVx;
    }

    public double getCurrentVy() {
        return currentVy;
    }

    public void setCurrentVy(double currentVy) {
        this.currentVy = currentVy;
    }

    public double getCurrentVz() {
        return currentVz;
    }

    public void setCurrentVz(double currentVz) {
        this.currentVz = currentVz;
    }

    public double getLastX() {
        return lastX;
    }

    public void setLastX(double lastX) {
        this.lastX = lastX;
    }

    public double getLastY() {
        return lastY;
    }

    public void setLastY(double lastY) {
        this.lastY = lastY;
    }

    public double getLastZ() {
        return lastZ;
    }

    public void setLastZ(double lastZ) {
        this.lastZ = lastZ;
    }

    public float getLastYaw() {
        return lastYaw;
    }

    public void setLastYaw(float lastYaw) {
        this.lastYaw = lastYaw;
    }

    public float getLastPitch() {
        return lastPitch;
    }

    public void setLastPitch(float lastPitch) {
        this.lastPitch = lastPitch;
    }

    public boolean hasLastPosition() {
        return hasLastPosition;
    }

    public void setHasLastPosition(boolean hasLastPosition) {
        this.hasLastPosition = hasLastPosition;
    }

    public boolean wasOnGround() {
        return wasOnGround;
    }

    public void setWasOnGround(boolean wasOnGround) {
        this.wasOnGround = wasOnGround;
    }

    public boolean isJumpedLastTick() {
        return jumpedLastTick;
    }

    public void setJumpedLastTick(boolean jumpedLastTick) {
        this.jumpedLastTick = jumpedLastTick;
    }

    public int getTicksSinceJump() {
        return ticksSinceJump;
    }

    public void setTicksSinceJump(int ticksSinceJump) {
        this.ticksSinceJump = ticksSinceJump;
    }

    public void incrementTicksSinceJump() {
        if (this.ticksSinceJump < 999) {
            this.ticksSinceJump++;
        }
    }

    public double getHorizontalBuffer() {
        return horizontalBuffer;
    }

    public void setHorizontalBuffer(double horizontalBuffer) {
        this.horizontalBuffer = horizontalBuffer;
    }

    public double getVerticalBuffer() {
        return verticalBuffer;
    }

    public void setVerticalBuffer(double verticalBuffer) {
        this.verticalBuffer = verticalBuffer;
    }

    // ==================== 单移动周期预测缓存 ====================

    private PredictionResult cachedPrediction = null;
    private double cachedToX = 0.0;
    private double cachedToY = 0.0;
    private double cachedToZ = 0.0;
    private float cachedYaw = 0.0f;
    private float cachedPitch = 0.0f;

    public boolean hasCachedPrediction(double toX, double toY, double toZ, float yaw, float pitch) {
        return cachedPrediction != null
                && Double.compare(cachedToX, toX) == 0
                && Double.compare(cachedToY, toY) == 0
                && Double.compare(cachedToZ, toZ) == 0
                && Float.compare(cachedYaw, yaw) == 0
                && Float.compare(cachedPitch, pitch) == 0;
    }

    public PredictionResult getCachedPrediction() {
        return cachedPrediction;
    }

    public void cachePrediction(double toX, double toY, double toZ, float yaw, float pitch, PredictionResult result) {
        this.cachedPrediction = result;
        this.cachedToX = toX;
        this.cachedToY = toY;
        this.cachedToZ = toZ;
        this.cachedYaw = yaw;
        this.cachedPitch = pitch;
    }

    public void clearPredictionCache() {
        this.cachedPrediction = null;
    }
}
