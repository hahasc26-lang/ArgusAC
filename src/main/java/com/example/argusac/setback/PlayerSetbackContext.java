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

    // 装聋作哑 (Ignore Teleport / Creeping) 逃避反作弊回退补刀追踪器 (对标 GrimAC BadPacketsN)
    private volatile int unackedMovePackets = 0;
    private volatile long lastResendSetbackTimestamp = 0L;
    private static final Random RANDOM = new Random();

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
     * 生成绝对安全的负数 Teleport ID (对齐 GrimAC 源码: random.nextInt() | Integer.MIN_VALUE)
     * 最高二进制位强制置 1，与原版服务端及其他插件的非负自增 Teleport ID (0, 1, 2...) 形成绝对二进制隔离！
     */
    public int generateSecureTeleportId() {
        int id = RANDOM.nextInt() | Integer.MIN_VALUE;
        return (id == 0) ? -1 : id;
    }

    /**
     * 标记进入处于纠正状态 (Teleport In-Flight)
     * 发出回退包后生成特定的 Teleport ID，并在客户端正式回传确认包前锁定状态
     * 自动构造 PendingCorrection，保证协议与坐标双通道校验闭环
     */
    public void markTeleportInFlight(int teleportId, Location target) {
        this.teleportInFlight = true;
        this.inFlightTeleportId = teleportId;
        long now = System.currentTimeMillis();
        this.teleportInFlightTimestamp = now;
        this.teleportInFlightTarget = (target != null) ? target.clone() : null;
        this.unackedMovePackets = 0;

        if (target != null && target.getWorld() != null) {
            this.pendingCorrection = new PendingCorrection(
                    target.getX(), target.getY(), target.getZ(),
                    target.getWorld().getUID(),
                    now + 1500L,
                    teleportId
            );
        }
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
            this.inFlightTeleportId = 0;
            this.pendingCorrection = null;
            this.unackedMovePackets = 0;
            return false;
        }
        return true;
    }

    /**
     * 记录在 In-Flight 传输态下接收到的非法偏离移动数据包
     * @return true 若客户端连续发送未确认移动包达到阈值（装聋作哑/丢弃回退包），需要立即原地瞬发补刀重发回退数据包！
     */
    public boolean recordUnackedMovePacket() {
        if (!teleportInFlight) {
            return false;
        }
        unackedMovePackets++;
        long now = System.currentTimeMillis();
        // 对齐 GrimAC：若未确认移动包达到 2 次以上，或距离回退发出超过 200ms 依然持续发移动包，立即触发补刀
        if (unackedMovePackets >= 2 || (now - teleportInFlightTimestamp > 200L && now - lastResendSetbackTimestamp > 150L)) {
            this.lastResendSetbackTimestamp = now;
            this.unackedMovePackets = 0;
            return true;
        }
        return false;
    }

    /**
     * 收到客户端 ACK 确认 (Teleport Confirm / AcceptTeleportationPacket) 或坐标吻合，解除 In-Flight
     * 严格对标 GrimAC 机制：
     * 1. 只接受负数 Teleport ID 闭环，原版或第三方插件的正数 ID (>= 0) 绝不干扰反作弊回退！
     * 2. 解除传输态时不清除违规锁定锚点，防止作弊者紧接着下一 tick 蹭步前移！
     */
    public void confirmTeleport(int teleportId) {
        if (!teleportInFlight) {
            return;
        }
        if (teleportId < 0 && (this.inFlightTeleportId == teleportId || this.inFlightTeleportId == 0)) {
            this.teleportInFlight = false;
            this.inFlightTeleportId = 0;
            this.unackedMovePackets = 0;
            clearPendingCorrection();
        }
    }

    /**
     * 判断当前是否处于违规锚点锁死周期内 (防止作弊者连续按 W 产生微小位移前移锚点)
     */
    public boolean isUnderViolationLock() {
        return lockedAnchorLocation != null
                && (System.currentTimeMillis() - lastCancellationViolationTime < 2000L);
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
        // 对齐 GrimAC：处于在途传送或违规锁定期内，严禁将未经验证的中间过渡位移写入历史快照，彻底杜绝快照污染！
        if (isTeleportInFlight() || isUnderViolationLock()) {
            return;
        }

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

        // 0. 对标 GrimAC 锚点守恒法则：若处于违规锁定期内，绝对优先返回锁定锚点 (Locked Anchor)，禁止任何向前漂移！
        if (isUnderViolationLock() && lockedAnchorLocation != null && lockedAnchorLocation.getWorld() != null
                && lockedAnchorLocation.getWorld().getUID().equals(worldUid)) {
            LegitimateLocation anchorLegit = LegitimateLocation.from(lockedAnchorLocation, null, true, false, null);
            if (anchorLegit != null && anchorLegit.isPassable()) {
                return anchorLegit;
            }
        }

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
