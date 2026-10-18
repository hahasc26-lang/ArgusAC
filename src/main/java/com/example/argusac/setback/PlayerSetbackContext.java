package com.example.argusac.setback;

import com.example.argusac.log.model.SetbackFailureLog;
import com.example.argusac.log.model.ViolationLog;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.model.SetbackOverride;
import com.example.argusac.util.MathUtil;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集中化玩家回退运行时状态上下文模型 (PlayerSetbackContext)
 * 记录单名玩家的历史合法位移快照、锁定锚点、网络校正确认时钟与回退违规日志。
 */
public class PlayerSetbackContext {

    private volatile LegitimateLocation lastLegitimateLocation;
    private volatile LegitimateLocation previousLegitimateLocation;
    private volatile LegitimateLocation lastGroundLocation;
    private volatile SetbackOverride override = SetbackOverride.DEFAULT;
    private volatile long lastSetbackTimestamp = 0L;
    private volatile long immunityUntil = 0L;
    private volatile long lastNotifyTimestamp = 0L;

    // 客户端传送序列与流水号确认追踪 (GrimAC-grade Teleport In-Flight & ACK Check)
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private volatile long activeSequenceId = 0L;
    private volatile boolean teleportInFlight = false;
    private volatile int inFlightTeleportId = 0;
    private volatile long teleportInFlightTimestamp = 0L;
    private volatile Location teleportInFlightTarget = null;

    // 短时验证移动快照队列
    private final Deque<LegitimateLocation> snapshotHistory = new ConcurrentLinkedDeque<>();

    // 按检测名称细分的冷却时间戳映射
    private final Map<String, Long> lastSetbackByCheck = new ConcurrentHashMap<>();

    // 待确认网络坐标校正
    private volatile PendingCorrection pendingCorrection = null;

    // 持续当帧强行原位锁定锚点状态追踪 (Locked Anchor State)
    private volatile Location lockedAnchorLocation = null;
    private volatile long lockedAnchorTimestamp = 0L;
    private volatile int consecutiveCancellationViolations = 0;
    private volatile long lastCancellationViolationTime = 0L;

    private final Deque<ViolationLog> logs = new ConcurrentLinkedDeque<>();
    private final Deque<SetbackFailureLog> failureLogs = new ConcurrentLinkedDeque<>();
    private final ActiveHoverTracker hoverTracker = new ActiveHoverTracker();

    public long getNextSequenceId() {
        return sequenceCounter.incrementAndGet();
    }

    public long getActiveSequenceId() {
        return activeSequenceId;
    }

    public void setActiveSequenceId(long id) {
        this.activeSequenceId = id;
    }

    /**
     * 标记进入处于纠正状态 (Teleport In-Flight)
     * 发出回退包后生成特定的 Teleport ID，并在客户端正式回传确认包前锁定状态
     */
    public void markTeleportInFlight(int teleportId, Location target) {
        this.teleportInFlight = true;
        this.inFlightTeleportId = teleportId;
        this.teleportInFlightTimestamp = System.currentTimeMillis();
        this.teleportInFlightTarget = (target != null) ? target.clone() : null;
    }

    /**
     * 判断是否仍处于传送传输中状态 (带 1500ms 异常超期保护)
     */
    public boolean isTeleportInFlight() {
        if (!teleportInFlight) {
            return false;
        }
        if (System.currentTimeMillis() - teleportInFlightTimestamp > 1500L) {
            this.teleportInFlight = false;
            return false;
        }
        return true;
    }

    /**
     * 收到客户端 ACK 确认 (Teleport Confirm / AcceptTeleportationPacket) 或坐标吻合，解除 In-Flight
     */
    public void confirmTeleport(int teleportId) {
        if (!teleportInFlight) {
            return;
        }
        if (this.inFlightTeleportId <= 0 || teleportId <= 0 || this.inFlightTeleportId == teleportId) {
            this.teleportInFlight = false;
            this.inFlightTeleportId = 0;
            clearPendingCorrection();
            clearLockedAnchor();
        }
    }

    public Location getTeleportInFlightTarget() {
        return teleportInFlightTarget;
    }

    public int getInFlightTeleportId() {
        return inFlightTeleportId;
    }

    public ActiveHoverTracker getHoverTracker() {
        return hoverTracker;
    }

    public void resetActiveHoverTracking() {
        hoverTracker.reset();
    }

    public void update(LegitimateLocation location, int maxCapacity, long maxAgeMs) {
        this.previousLegitimateLocation = this.lastLegitimateLocation;
        this.lastLegitimateLocation = location;
        if (location != null && location.isOnGround()) {
            this.lastGroundLocation = location;
        }

        snapshotHistory.addFirst(location);
        long now = System.currentTimeMillis();
        while (snapshotHistory.size() > maxCapacity) {
            snapshotHistory.removeLast();
        }
        while (!snapshotHistory.isEmpty() && (now - snapshotHistory.peekLast().getTimestamp() > maxAgeMs * 2)) {
            snapshotHistory.removeLast();
        }
    }

    public void update(LegitimateLocation location) {
        update(location, 30, 2500L);
    }

    public void setLastLegitimateLocation(LegitimateLocation location) {
        this.lastLegitimateLocation = location;
        if (location != null) {
            snapshotHistory.addFirst(location);
            if (location.isOnGround()) {
                this.lastGroundLocation = location;
            }
        }
    }

    /**
     * 从快照队列检索同世界、时效有效、距离有界且处于稳固地面的最优合法快照
     * 对齐 Grim 机制：地面稳定态强制策略 (Ground-Only Invariant)，回退目标必须是稳固地面，
     * 绝不在常规移动检测中返回空中快照，彻底消灭空中悬浮漏洞的根源！
     */
    public LegitimateLocation findBoundedLegitimateLocation(UUID worldUid, Location currentLoc, double maxDistance, long maxAgeMs) {
        if (worldUid == null || currentLoc == null) {
            return null;
        }
        long now = System.currentTimeMillis();

        // 1. 优先从历史快照队列中检索近期的地面稳定态快照
        for (LegitimateLocation snap : snapshotHistory) {
            if (snap.getWorldUid() == null || !snap.getWorldUid().equals(worldUid)) {
                continue;
            }
            if (now - snap.getTimestamp() > maxAgeMs) {
                continue;
            }
            Location snapLoc = snap.toLocation();
            if (snapLoc == null) {
                continue;
            }
            double dist = MathUtil.getHorizontalDistance(snapLoc, currentLoc);
            if (dist > maxDistance) {
                continue;
            }
            // 核心法则：常规移动回退目标必须处于地面方块之上，绝不采纳半空快照
            if (snap.isPassable() && snap.isOnGround()) {
                return snap;
            }
        }

        // 2. 根治空中悬浮：若近期快照无地面记录（例如长时间滞空/飞天），
        // 只要同世界且可通行，强制拉回历史最后合法的地面位置 (LastGroundLocation)，彻底剥夺空中悬停空间
        if (lastGroundLocation != null && lastGroundLocation.getWorldUid() != null
                && lastGroundLocation.getWorldUid().equals(worldUid)
                && lastGroundLocation.isPassable()) {
            return lastGroundLocation;
        }

        // 3. 队列与历史记录皆无地面快照时，返回 null，交由外层执行垂直向下探测安全地面 (findSafeGroundBelow)
        return null;
    }

    /**
     * 安全降级：在同世界且限定距离内查找最近的可通行快照 (优先地面)
     */
    public LegitimateLocation findNearestPassableLocation(UUID worldUid, Location currentLoc, double maxDistance) {
        if (worldUid == null || currentLoc == null) {
            return null;
        }
        LegitimateLocation best = null;
        double bestDist = Double.MAX_VALUE;

        for (LegitimateLocation snap : snapshotHistory) {
            if (snap.getWorldUid() == null || !snap.getWorldUid().equals(worldUid)) {
                continue;
            }
            Location snapLoc = snap.toLocation();
            if (snapLoc == null) {
                continue;
            }
            double dist = MathUtil.getHorizontalDistance(snapLoc, currentLoc);
            if (dist <= maxDistance && snap.isPassable()) {
                // 地面快照为最高优先级
                if (snap.isOnGround()) {
                    if (best == null || !best.isOnGround() || dist < bestDist) {
                        best = snap;
                        bestDist = dist;
                    }
                } else if (best == null && dist < bestDist) {
                    best = snap;
                    bestDist = dist;
                }
            }
        }
        return best;
    }

    public void reset(Location loc, boolean inVehicle, UUID vehicleUid) {
        LegitimateLocation reset = LegitimateLocation.from(loc, new Vector(0, 0, 0), true, inVehicle, vehicleUid);
        this.lastLegitimateLocation = reset;
        this.previousLegitimateLocation = reset;
        this.lastGroundLocation = reset;
        this.snapshotHistory.clear();
        if (reset != null) {
            this.snapshotHistory.addFirst(reset);
        }
        this.pendingCorrection = null;
        resetActiveHoverTracking();
    }

    public void resetPreviousOnly() {
        this.previousLegitimateLocation = null;
    }

    public void clear() {
        this.lastLegitimateLocation = null;
        this.previousLegitimateLocation = null;
        this.lastGroundLocation = null;
        this.snapshotHistory.clear();
        this.pendingCorrection = null;
        resetActiveHoverTracking();
    }

    public boolean isImmune() {
        // 对齐 GrimAC：彻底禁用移动自免疫免检，作弊被阻断后下一帧若继续作弊当场继续拦截
        return false;
    }

    public LegitimateLocation getLastLegitimateLocation() {
        return lastLegitimateLocation;
    }

    public LegitimateLocation getLastGroundLocation() {
        return lastGroundLocation;
    }

    public void setLastGroundLocation(LegitimateLocation location) {
        this.lastGroundLocation = location;
    }

    public LegitimateLocation getPreviousLegitimateLocation() {
        return previousLegitimateLocation;
    }

    public SetbackOverride getOverride() {
        return override;
    }

    public void setOverride(SetbackOverride override) {
        this.override = (override != null) ? override : SetbackOverride.DEFAULT;
    }

    public long getLastSetbackTimestamp() {
        return lastSetbackTimestamp;
    }

    public void setLastSetbackTimestamp(long lastSetbackTimestamp) {
        this.lastSetbackTimestamp = lastSetbackTimestamp;
    }

    public long getLastSetbackTimestamp(String checkName) {
        return lastSetbackByCheck.getOrDefault(checkName, 0L);
    }

    public void setLastSetbackTimestamp(String checkName, long timestamp) {
        this.lastSetbackByCheck.put(checkName, timestamp);
        this.lastSetbackTimestamp = timestamp;
    }

    public long getImmunityUntil() {
        return immunityUntil;
    }

    public void setImmunityUntil(long immunityUntil) {
        this.immunityUntil = immunityUntil;
    }

    public long getLastNotifyTimestamp() {
        return lastNotifyTimestamp;
    }

    public void setLastNotifyTimestamp(long lastNotifyTimestamp) {
        this.lastNotifyTimestamp = lastNotifyTimestamp;
    }

    public PendingCorrection getPendingCorrection() {
        return pendingCorrection;
    }

    public void setPendingCorrection(PendingCorrection pendingCorrection) {
        this.pendingCorrection = pendingCorrection;
    }

    public void clearPendingCorrection() {
        this.pendingCorrection = null;
    }

    public boolean hasPendingCorrection() {
        PendingCorrection pc = this.pendingCorrection;
        return pc != null && !pc.isExpired();
    }

    public boolean matchesCorrection(Location loc) {
        PendingCorrection pc = this.pendingCorrection;
        return pc != null && pc.matchesCorrection(loc);
    }

    public void addLog(ViolationLog log, int maxLogs) {
        logs.addLast(log);
        while (logs.size() > maxLogs) {
            logs.pollFirst();
        }
    }

    public List<ViolationLog> getLogs() {
        return new ArrayList<>(logs);
    }

    public void addFailureLog(SetbackFailureLog log, int maxLogs) {
        failureLogs.addLast(log);
        while (failureLogs.size() > maxLogs) {
            failureLogs.pollFirst();
        }
    }

    public List<SetbackFailureLog> getFailureLogs() {
        return new ArrayList<>(failureLogs);
    }

    public Location getLockedAnchorLocation() {
        return lockedAnchorLocation;
    }

    public void setLockedAnchorLocation(Location loc) {
        this.lockedAnchorLocation = (loc != null) ? loc.clone() : null;
        this.lockedAnchorTimestamp = System.currentTimeMillis();
    }

    public void clearLockedAnchor() {
        this.lockedAnchorLocation = null;
        this.lockedAnchorTimestamp = 0L;
        this.consecutiveCancellationViolations = 0;
    }

    public int getConsecutiveCancellationViolations() {
        return consecutiveCancellationViolations;
    }

    public void incrementConsecutiveCancellationViolations() {
        this.consecutiveCancellationViolations++;
        this.lastCancellationViolationTime = System.currentTimeMillis();
    }

    public long getLastCancellationViolationTime() {
        return lastCancellationViolationTime;
    }

    public long getLockedAnchorTimestamp() {
        return lockedAnchorTimestamp;
    }
}
