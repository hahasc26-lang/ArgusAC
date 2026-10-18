package com.example.argusac.data;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 玩家鞘翅滑翔运行时状态数据模型 (ElytraData)
 * 专门追踪滑翔生命周期、物理运动学参数、烟花/激流/外部推力时效窗口与反作弊状态机。
 */
public class ElytraData {

    // 滑翔状态与生命周期
    private boolean gliding = false;
    private long glideStartTime = 0L;
    private int glideTicks = 0;
    private boolean elytraEquipped = false;
    private boolean elytraBroken = false;
    private long lastChestplateCheckTime = 0L;
    private long lastArmorSwapTime = 0L;
    private int armorSwapCount = 0;

    // 上一 Tick 运动学状态
    private Location lastLocation = null;
    private Vector lastVelocity = new Vector(0, 0, 0);
    private double lastHorizontalSpeed = 0.0;
    private double lastDeltaY = 0.0;
    private float lastPitch = 0.0f;

    // 烟花推进时效追踪
    private long lastFireworkTime = 0L;
    private Location lastFireworkLocation = null;
    private int consecutiveHighSpeedTicks = 0;

    // 激流三叉戟与外部速度源时效
    private long lastRiptideTime = 0L;
    private long lastExternalVelocityTime = 0L;

    // 滑翔状态切换追踪 (GlideFlip & GlideState)
    private long lastToggleTime = 0L;
    private Location lastToggleLocation = null;
    private final Deque<Long> toggleTimestamps = new ArrayDeque<>(8);

    // 物理子项连续异常计数器
    private int ascendTicks = 0;
    private int hoverTicks = 0;
    private int consecutiveLevelTicks = 0;
    private double peakDiveSpeed = 0.0;

    // 滑翔移动 Tick 速率追踪 (Timer)
    private long lastMoveTimestamp = 0L;
    private final Deque<Long> moveIntervals = new ArrayDeque<>(20);

    // 免摔滥用追踪 (ElytraLowY / ElytraNoFall)
    private float fallDistanceBeforeGlide = 0.0f;
    private long glideStartTimeForNoFall = 0L;
    private int consecutiveLowYToggles = 0;
    private long lastLowYToggleTime = 0L;

    public ElytraData() {
    }

    /**
     * 重置所有滑翔临时状态（例如在回退成功、玩家死亡、换界、传送或正常落地时调用）
     */
    public void resetGlideState() {
        this.gliding = false;
        this.glideTicks = 0;
        this.ascendTicks = 0;
        this.hoverTicks = 0;
        this.consecutiveLevelTicks = 0;
        this.peakDiveSpeed = 0.0;
        this.consecutiveHighSpeedTicks = 0;
        this.lastHorizontalSpeed = 0.0;
        this.lastDeltaY = 0.0;
        this.moveIntervals.clear();
        this.lastMoveTimestamp = 0L;
        this.fallDistanceBeforeGlide = 0.0f;
    }

    /**
     * 完全重置所有数据（用于玩家加入、重生或数据清理）
     */
    public void resetAll() {
        resetGlideState();
        this.glideStartTime = 0L;
        this.elytraEquipped = false;
        this.elytraBroken = false;
        this.lastChestplateCheckTime = 0L;
        this.lastLocation = null;
        this.lastVelocity = new Vector(0, 0, 0);
        this.lastFireworkTime = 0L;
        this.lastFireworkLocation = null;
        this.lastRiptideTime = 0L;
        this.lastExternalVelocityTime = 0L;
        this.lastToggleTime = 0L;
        this.lastToggleLocation = null;
        this.toggleTimestamps.clear();
        this.consecutiveLowYToggles = 0;
        this.lastLowYToggleTime = 0L;
    }

    /**
     * 清空 Timer 滑动采样队列（在触发告警或状态重置后冲刷，避免连续雪崩式重复误判）
     */
    public void clearMoveIntervals() {
        this.moveIntervals.clear();
    }

    /**
     * 清空 Flip 切换时间戳队列
     */
    public void clearToggleTimestamps() {
        this.toggleTimestamps.clear();
    }

    /**
     * 记录一次临地开关滑翔操作，并返回近期窗口内的连续违规次数
     */
    public int recordLowYToggle(long windowMs) {
        long now = System.currentTimeMillis();
        if (now - lastLowYToggleTime < windowMs) {
            consecutiveLowYToggles++;
        } else {
            consecutiveLowYToggles = 1;
        }
        lastLowYToggleTime = now;
        return consecutiveLowYToggles;
    }

    public void resetLowYToggles() {
        this.consecutiveLowYToggles = 0;
    }

    /**
     * 记录一次滑翔状态切换事件时间戳
     */
    public void recordToggle(long timestamp, Location location) {
        this.lastToggleTime = timestamp;
        if (location != null) {
            this.lastToggleLocation = location.clone();
        }
        this.toggleTimestamps.addLast(timestamp);
        // 清理超过 2 秒的旧记录
        while (!this.toggleTimestamps.isEmpty() && timestamp - this.toggleTimestamps.peekFirst() > 2000L) {
            this.toggleTimestamps.pollFirst();
        }
    }

    /**
     * 获取指定时间窗口（毫秒）内的滑翔状态切换次数
     */
    public int getToggleCountInWindow(long windowMs) {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Long ts : toggleTimestamps) {
            if (now - ts <= windowMs) {
                count++;
            }
        }
        return count;
    }

    /**
     * 记录一次移动间隔用于 Timer 滑动窗口
     */
    public void recordMoveInterval(long interval) {
        if (interval > 0 && interval < 1000) {
            moveIntervals.addLast(interval);
            while (moveIntervals.size() > 20) {
                moveIntervals.pollFirst();
            }
        }
    }

    /**
     * 计算滑动窗口内的平均 Move 间隔（毫秒）
     */
    public double getAverageMoveInterval() {
        if (moveIntervals.isEmpty()) {
            return 50.0;
        }
        long sum = 0;
        for (Long interval : moveIntervals) {
            sum += interval;
        }
        return (double) sum / moveIntervals.size();
    }

    public int getMoveIntervalSampleSize() {
        return moveIntervals.size();
    }

    // ==================== Getters & Setters ====================

    public boolean isGliding() {
        return gliding;
    }

    public void setGliding(boolean gliding) {
        this.gliding = gliding;
    }

    public long getGlideStartTime() {
        return glideStartTime;
    }

    public void setGlideStartTime(long glideStartTime) {
        this.glideStartTime = glideStartTime;
    }

    public int getGlideTicks() {
        return glideTicks;
    }

    public void setGlideTicks(int glideTicks) {
        this.glideTicks = glideTicks;
    }

    public void incrementGlideTicks() {
        this.glideTicks++;
    }

    public boolean isElytraEquipped() {
        return elytraEquipped;
    }

    public void setElytraEquipped(boolean elytraEquipped) {
        this.elytraEquipped = elytraEquipped;
    }

    public boolean isElytraBroken() {
        return elytraBroken;
    }

    public void setElytraBroken(boolean elytraBroken) {
        this.elytraBroken = elytraBroken;
    }

    public long getLastChestplateCheckTime() {
        return lastChestplateCheckTime;
    }

    public void setLastChestplateCheckTime(long lastChestplateCheckTime) {
        this.lastChestplateCheckTime = lastChestplateCheckTime;
    }

    public Location getLastLocation() {
        return lastLocation;
    }

    public void setLastLocation(Location lastLocation) {
        this.lastLocation = lastLocation;
    }

    public Vector getLastVelocity() {
        return lastVelocity;
    }

    public void setLastVelocity(Vector lastVelocity) {
        this.lastVelocity = lastVelocity;
    }

    public double getLastHorizontalSpeed() {
        return lastHorizontalSpeed;
    }

    public void setLastHorizontalSpeed(double lastHorizontalSpeed) {
        this.lastHorizontalSpeed = lastHorizontalSpeed;
    }

    public double getLastDeltaY() {
        return lastDeltaY;
    }

    public void setLastDeltaY(double lastDeltaY) {
        this.lastDeltaY = lastDeltaY;
    }

    public float getLastPitch() {
        return lastPitch;
    }

    public void setLastPitch(float lastPitch) {
        this.lastPitch = lastPitch;
    }

    public long getLastFireworkTime() {
        return lastFireworkTime;
    }

    public void setLastFireworkTime(long lastFireworkTime) {
        this.lastFireworkTime = lastFireworkTime;
    }

    public Location getLastFireworkLocation() {
        return lastFireworkLocation;
    }

    public void setLastFireworkLocation(Location lastFireworkLocation) {
        this.lastFireworkLocation = lastFireworkLocation;
    }

    public int getConsecutiveHighSpeedTicks() {
        return consecutiveHighSpeedTicks;
    }

    public void setConsecutiveHighSpeedTicks(int consecutiveHighSpeedTicks) {
        this.consecutiveHighSpeedTicks = consecutiveHighSpeedTicks;
    }

    public void incrementConsecutiveHighSpeedTicks() {
        this.consecutiveHighSpeedTicks++;
    }

    public long getLastRiptideTime() {
        return lastRiptideTime;
    }

    public void setLastRiptideTime(long lastRiptideTime) {
        this.lastRiptideTime = lastRiptideTime;
    }

    public long getLastExternalVelocityTime() {
        return lastExternalVelocityTime;
    }

    public void setLastExternalVelocityTime(long lastExternalVelocityTime) {
        this.lastExternalVelocityTime = lastExternalVelocityTime;
    }

    public long getLastToggleTime() {
        return lastToggleTime;
    }

    public void setLastToggleTime(long lastToggleTime) {
        this.lastToggleTime = lastToggleTime;
    }

    public Location getLastToggleLocation() {
        return lastToggleLocation;
    }

    public void setLastToggleLocation(Location lastToggleLocation) {
        this.lastToggleLocation = lastToggleLocation;
    }

    public int getAscendTicks() {
        return ascendTicks;
    }

    public void setAscendTicks(int ascendTicks) {
        this.ascendTicks = ascendTicks;
    }

    public void incrementAscendTicks() {
        this.ascendTicks++;
    }

    public int getHoverTicks() {
        return hoverTicks;
    }

    public void setHoverTicks(int hoverTicks) {
        this.hoverTicks = hoverTicks;
    }

    public void incrementHoverTicks() {
        this.hoverTicks++;
    }

    public long getLastMoveTimestamp() {
        return lastMoveTimestamp;
    }

    public void setLastMoveTimestamp(long lastMoveTimestamp) {
        this.lastMoveTimestamp = lastMoveTimestamp;
    }

    public float getFallDistanceBeforeGlide() {
        return fallDistanceBeforeGlide;
    }

    public void setFallDistanceBeforeGlide(float fallDistanceBeforeGlide) {
        this.fallDistanceBeforeGlide = fallDistanceBeforeGlide;
    }

    public long getGlideStartTimeForNoFall() {
        return glideStartTimeForNoFall;
    }

    public void setGlideStartTimeForNoFall(long glideStartTimeForNoFall) {
        this.glideStartTimeForNoFall = glideStartTimeForNoFall;
    }

    public long getLastArmorSwapTime() {
        return lastArmorSwapTime;
    }

    public void setLastArmorSwapTime(long lastArmorSwapTime) {
        this.lastArmorSwapTime = lastArmorSwapTime;
    }

    public int getArmorSwapCount() {
        return armorSwapCount;
    }

    public void setArmorSwapCount(int armorSwapCount) {
        this.armorSwapCount = armorSwapCount;
    }

    public int recordArmorSwap(long now) {
        if (now - this.lastArmorSwapTime < 1500L) {
            this.armorSwapCount++;
        } else {
            this.armorSwapCount = 1;
        }
        this.lastArmorSwapTime = now;
        return this.armorSwapCount;
    }

    public int getConsecutiveLevelTicks() {
        return consecutiveLevelTicks;
    }

    public void setConsecutiveLevelTicks(int consecutiveLevelTicks) {
        this.consecutiveLevelTicks = consecutiveLevelTicks;
    }

    public void incrementConsecutiveLevelTicks() {
        this.consecutiveLevelTicks++;
    }

    public double getPeakDiveSpeed() {
        return peakDiveSpeed;
    }

    public void setPeakDiveSpeed(double peakDiveSpeed) {
        this.peakDiveSpeed = peakDiveSpeed;
    }
}
