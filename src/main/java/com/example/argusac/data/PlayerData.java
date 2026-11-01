package com.example.argusac.data;

import com.example.argusac.log.model.SetbackFailureLog;
import com.example.argusac.log.model.ViolationLog;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.model.SetbackOverride;
import com.example.argusac.model.VelocitySourceType;
import com.example.argusac.prediction.PlayerPhysicsState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 玩家反作弊运行时核心数据模型 (PlayerData)
 * 保存单一回退点 (lastLegitimateLocation)、前一位置兜底 (previousLegitimateLocation)、
 * 合法速度来源时效表、自免疫截止期与检测上下文
 */
public class PlayerData {

    private final UUID uuid;
    private final String name;

    // 各检测模块的 VL 等级映射
    private final Map<String, Integer> violationLevels = new ConcurrentHashMap<>();

    // 历史违规日志队列 (限制容量)
    private final Deque<ViolationLog> violationLogs = new ConcurrentLinkedDeque<>();

    // 回退失败审计日志队列
    private final Deque<SetbackFailureLog> failureLogs = new ConcurrentLinkedDeque<>();

    // 合法速度与机动来源时效表 (Key: 来源类型, Value: 有效期截止毫秒时间戳)
    private final Map<VelocitySourceType, Long> legalVelocitySources = new ConcurrentHashMap<>();

    private volatile int lastPunishedVl = 0;

    // 移动上下文
    private Location fromLocation;
    private Location toLocation;
    private double lastHorizontalDistance = 0.0;
    private volatile int airTicks = 0;
    private volatile long lastAirTickNanos = 0L;
    private volatile boolean clientOnGround;
    private int noFallSpoofPackets = 0;
    private long lastNoFallSpoofNanos = 0L;
    private int groundTicks = 0;
    private volatile boolean onGround = true; // Also read by the packet listener off the main thread.
    private boolean lastOnGround = true;
    private volatile boolean airborneFromIce = false; // 标记当前滞空是否起源于冰面/粘液块

    // 击退响应追踪 (Velocity Check)
    private Vector expectedVelocity = null;
    private long lastVelocityReceivedTime = 0L;
    private boolean velocityHandled = true;
    private double maxObservedVelocityY = 0.0;
    private double maxObservedVelocityH = 0.0;

    // 特殊环境标记 (由主线程更新，供 Netty 线程无锁读取)
    private volatile boolean inSpecialEnvironment = false;

    // 基础缓冲时间戳
    private long lastTeleportTime = 0L;
    private long lastFlightToggleTime = 0L;
    private long lastJoinTime = 0L;
    private long lastRespawnTime = 0L;

    // 数据包层实时位置与视角追踪 (Netty Thread Safe Packet Position & Rotation)
    private volatile double packetX;
    private volatile double packetY;
    private volatile double packetZ;
    private volatile float packetYaw;
    private volatile float packetPitch;
    private volatile boolean hasPacketLocation = false;
    private volatile long lastPacketLocationTime = 0L;

    // 最近一次在 Netty 数据包层已核验放行的攻击记录
    private volatile UUID lastVerifiedAttackTarget = null;
    private volatile long lastVerifiedAttackTime = 0L;

    // 加入游戏后的豁免期时长（毫秒）
    private static final long GRACE_PERIOD_MS = 5000L;
    private long lastDamageTime = 0L;
    private Location lastDamageLocation = null;
    private long lastDamageLocationTime = 0L;

    // 背包状态追踪 (InventoryMove)
    private boolean inventoryOpen = false;
    private Location inventoryOpenLocation = null;
    private long inventoryOpenTime = 0L;
    private long lastInventoryCloseTime = 0L;

    // 战斗相关数据 (AutoClicker, KillAura, Reach)
    private final Deque<Long> clickTimestamps = new ConcurrentLinkedDeque<>();
    private long lastClickTimestamp = 0L;
    private UUID lastAttackedTarget = null;
    private long lastAttackTime = 0L;
    private float lastLookYaw = 0.0f;
    private float lastLookPitch = 0.0f;

    // 客户端虚拟时钟驱动与协议生命周期追踪 (Client Flying Virtual Clock & TCP Burst Defense)
    private volatile long clientFlyingTicks = 0L;
    private volatile long lastFlyingPacketNanos = 0L;
    private volatile int clicksInCurrentFlyingTick = 0;
    private volatile long lastFlyingPacketIntervalNanos = 50_000_000L;
    private volatile long lastWindowFocusRecoveryTime = 0L;
    private volatile long lastContainerCloseTick = 0L;

    // 攻击与挥臂乱序配对 (Attack & Swing Packet Ordering)
    private volatile long lastSwingClientTick = -1L;
    private volatile long lastAttackClientTick = -1L;
    private volatile int consecutiveNoSwingAttacks = 0;

    // 右键实体孪生交互去重 (InteractAt & Interact Deduplication)
    private volatile int lastInteractEntityId = -1;
    private volatile long lastInteractAtClientTick = -1L;

    // 快捷栏切换与手持物历史回溯缓冲 (Held Item History Buffer)
    private volatile int currentHeldSlot = 0;
    private volatile long lastSlotSwitchTime = 0L;
    private final Deque<Long> heldItemSwitchTimes = new ConcurrentLinkedDeque<>();

    // 连点器统计学分析样本队列 (Tick 间隔与毫秒间隔)
    private final Deque<Integer> flyingIntervalSamples = new ConcurrentLinkedDeque<>();
    private final Deque<Double> msIntervalSamples = new ConcurrentLinkedDeque<>();
    private volatile boolean skipNextIntervalSample = false;

    // 移动平滑缓冲区与环境惯性追踪 (SpeedCheck)
    private double speedBuffer = 0.0;
    private long lastSpeedCheckNanos = 0L;
    private int iceTicks = 0;
    private int soulSpeedTicks = 0;

    // 挖掘与方块交互状态 (AutoClicker 豁免与挖掘动画追踪)
    private volatile boolean digging = false;
    private volatile long lastDiggingTime = 0L;
    private volatile long diggingStartTime = 0L;
    private volatile long lastDiggingStopTime = 0L;

    // 方块破坏与放置追踪 (FastBreak, Nuker, Scaffold)
    private long lastBreakTick = -1L;
    private long lastBreakTimestamp = 0L;
    private int breaksThisTick = 0;
    private final Deque<Long> breakTimestamps = new ConcurrentLinkedDeque<>();
    private long lastPlaceTime = 0L;

    // 鞘翅滑翔数据追踪 (ElytraFly)
    private final ElytraData elytraData = new ElytraData();

    // 确定性物理仿真运行时状态 (Prediction Simulation)
    private final PlayerPhysicsState physicsState = new PlayerPhysicsState();

    // 协议层 Timer 发包时钟平衡追踪器 (Timer & Clock Drift)
    private final com.example.argusac.packet.TimerTracker timerTracker = new com.example.argusac.packet.TimerTracker();

    public PlayerData(Player player) {
        this.uuid = player.getUniqueId();
        this.name = player.getName();
        this.lastJoinTime = System.currentTimeMillis();
    }

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.lastJoinTime = System.currentTimeMillis();
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public Player getPlayer() {
        return org.bukkit.Bukkit.getPlayer(uuid);
    }

    // ==================== VL 相关方法 ====================

    public int getVl(String checkName) {
        return violationLevels.getOrDefault(checkName, 0);
    }

    public int addVl(String checkName, int amount) {
        return violationLevels.compute(checkName, (k, v) -> (v == null ? 0 : v) + amount);
    }

    public void setVl(String checkName, int vl) {
        if (vl <= 0) {
            violationLevels.remove(checkName);
        } else {
            violationLevels.put(checkName, vl);
        }
    }

    public void decayVl(int amount) {
        for (Map.Entry<String, Integer> entry : violationLevels.entrySet()) {
            int newVl = Math.max(0, entry.getValue() - amount);
            if (newVl == 0) {
                violationLevels.remove(entry.getKey());
            } else {
                violationLevels.put(entry.getKey(), newVl);
            }
        }
        if (getTotalVl() < lastPunishedVl) {
            lastPunishedVl = getTotalVl();
        }
    }

    public int getLastPunishedVl() {
        return lastPunishedVl;
    }

    public void setLastPunishedVl(int lastPunishedVl) {
        this.lastPunishedVl = lastPunishedVl;
    }

    public int getTotalVl() {
        int total = 0;
        for (int v : violationLevels.values()) {
            total += v;
        }
        return total;
    }

    public Map<String, Integer> getAllVls() {
        return Collections.unmodifiableMap(violationLevels);
    }

    // ==================== 回退代理方法 (全部路由至 SetbackManager) ====================

    public LegitimateLocation getLastLegitimateLocation() {
        return com.example.argusac.ArgusACPlugin.getInstance().getSetbackManager().getLastLocation(uuid);
    }

    public LegitimateLocation getPreviousLegitimateLocation() {
        return com.example.argusac.ArgusACPlugin.getInstance().getSetbackManager().getPreviousLocation(uuid);
    }

    public SetbackOverride getSetbackOverride() {
        return com.example.argusac.ArgusACPlugin.getInstance().getSetbackManager().getOverride(uuid);
    }

    public void setSetbackOverride(SetbackOverride setbackOverride) {
        com.example.argusac.ArgusACPlugin.getInstance().getSetbackManager().setOverride(uuid, setbackOverride);
    }

    public boolean isSetbackImmune() {
        return com.example.argusac.ArgusACPlugin.getInstance().getSetbackManager().isImmune(uuid);
    }

    public long getSetbackImmunityUntil() {
        com.example.argusac.setback.PlayerSetbackContext ctx = com.example.argusac.ArgusACPlugin.getInstance()
                .getSetbackManager().getContext(uuid);
        return ctx != null ? ctx.getImmunityUntil() : 0L;
    }

    /**
     * 记录合法高机动/速度来源
     */
    public void grantLegalVelocity(VelocitySourceType type, long durationMs) {
        legalVelocitySources.put(type, System.currentTimeMillis() + durationMs);
    }

    /**
     * 判断指定合法速度来源当前是否有效
     */
    public boolean hasLegalVelocity(VelocitySourceType type) {
        Long expireTime = legalVelocitySources.get(type);
        return expireTime != null && expireTime > System.currentTimeMillis();
    }

    /**
     * 判断玩家当前是否存在任何生效中的合法高机动来源
     */
    public boolean hasAnyLegalVelocity() {
        long now = System.currentTimeMillis();
        for (Long expireTime : legalVelocitySources.values()) {
            if (expireTime > now) {
                return true;
            }
        }
        return false;
    }

    // ==================== 日志队列 ====================

    public void addLog(ViolationLog log, int maxLogs) {
        violationLogs.addLast(log);
        while (violationLogs.size() > maxLogs) {
            violationLogs.pollFirst();
        }
    }

    public List<ViolationLog> getLogs() {
        return new ArrayList<>(violationLogs);
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

    // ==================== 状态追踪 Getter & Setter ====================

    public Location getFromLocation() {
        return fromLocation;
    }

    public void setFromLocation(Location fromLocation) {
        this.fromLocation = fromLocation;
    }

    public Location getToLocation() {
        return toLocation;
    }

    public void setToLocation(Location toLocation) {
        this.toLocation = toLocation;
    }

    public double getLastHorizontalDistance() {
        return lastHorizontalDistance;
    }

    public void setLastHorizontalDistance(double lastHorizontalDistance) {
        this.lastHorizontalDistance = lastHorizontalDistance;
    }

    public int getAirTicks() {
        return airTicks;
    }

    public void setAirTicks(int airTicks) {
        this.airTicks = airTicks;
        if (airTicks <= 0) {
            lastAirTickNanos = 0L;
        }
    }

    public double incrementAirTicks() {
        long now = System.nanoTime();
        long previous = lastAirTickNanos;
        if (previous == 0L) {
            airTicks++;
            lastAirTickNanos = now;
            return 1.0;
        }

        double elapsedTicks = (now - previous) / 50_000_000.0;
        if (elapsedTicks < 0.2) {
            return 0.0;
        }

        int added = Math.max(1, (int) Math.round(elapsedTicks));
        airTicks += Math.min(4, added);
        lastAirTickNanos = now;
        return Math.max(1.0, elapsedTicks);
    }

    public boolean recordNoFallSpoofPacket(boolean spoofed) {
        if (!spoofed) {
            noFallSpoofPackets = 0;
            lastNoFallSpoofNanos = 0L;
            return false;
        }

        long now = System.nanoTime();
        if (lastNoFallSpoofNanos != 0L && now - lastNoFallSpoofNanos < 50_000_000L) {
            return false;
        }

        lastNoFallSpoofNanos = now;
        noFallSpoofPackets = (noFallSpoofPackets % 3) + 1;
        return noFallSpoofPackets == 3;
    }

    public int getGroundTicks() {
        return groundTicks;
    }

    public void setGroundTicks(int groundTicks) {
        this.groundTicks = groundTicks;
    }

    public void incrementGroundTicks() {
        this.groundTicks++;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }

    public boolean isLastOnGround() {
        return lastOnGround;
    }

    public void setLastOnGround(boolean lastOnGround) {
        this.lastOnGround = lastOnGround;
    }

    public Vector getExpectedVelocity() {
        return expectedVelocity;
    }

    public void setExpectedVelocity(Vector expectedVelocity) {
        if (expectedVelocity == null || expectedVelocity.lengthSquared() < 0.0001) {
            clearExpectedVelocity();
            return;
        }
        this.expectedVelocity = expectedVelocity;
        this.lastVelocityReceivedTime = System.currentTimeMillis();
        this.velocityHandled = false;
        this.maxObservedVelocityY = 0.0;
        this.maxObservedVelocityH = 0.0;
        if (this.physicsState != null) {
            this.physicsState.addPendingVelocity(expectedVelocity.getX(), expectedVelocity.getY(),
                    expectedVelocity.getZ());
        }
    }

    /**
     * 对齐 GrimAC：回退或定身时清空速度预期状态与外部击退时效，杜绝利用零速度包获取免检时间
     */
    public void clearExpectedVelocity() {
        this.expectedVelocity = null;
        this.lastVelocityReceivedTime = 0L;
        this.velocityHandled = true;
        this.maxObservedVelocityY = 0.0;
        this.maxObservedVelocityH = 0.0;
        if (this.physicsState != null) {
            this.physicsState.clearPendingVelocities();
        }
    }

    public void recordObservedVelocityResponse(double deltaY, double horizDist) {
        if (deltaY > this.maxObservedVelocityY) {
            this.maxObservedVelocityY = deltaY;
        }
        if (horizDist > this.maxObservedVelocityH) {
            this.maxObservedVelocityH = horizDist;
        }
    }

    public double getMaxObservedVelocityY() {
        return maxObservedVelocityY;
    }

    public double getMaxObservedVelocityH() {
        return maxObservedVelocityH;
    }

    public boolean isInSpecialEnvironment() {
        return inSpecialEnvironment;
    }

    public void setInSpecialEnvironment(boolean inSpecialEnvironment) {
        this.inSpecialEnvironment = inSpecialEnvironment;
    }

    public long getLastVelocityReceivedTime() {
        return lastVelocityReceivedTime;
    }

    public boolean isVelocityHandled() {
        return velocityHandled;
    }

    public void setVelocityHandled(boolean velocityHandled) {
        this.velocityHandled = velocityHandled;
    }

    public long getLastTeleportTime() {
        return lastTeleportTime;
    }

    public void setLastTeleportTime(long lastTeleportTime) {
        this.lastTeleportTime = lastTeleportTime;
        this.timerTracker.reset();
    }

    public long getLastFlightToggleTime() {
        return lastFlightToggleTime;
    }

    public void setLastFlightToggleTime(long lastFlightToggleTime) {
        this.lastFlightToggleTime = lastFlightToggleTime;
    }

    public long getLastJoinTime() {
        return lastJoinTime;
    }

    /**
     * 判断玩家是否处于加入后的初始豁免期（GRACE_PERIOD_MS 毫秒内）
     * 豁免期内跳过全部检测，防止因传送/加载延迟产生误报
     */
    public boolean isInGracePeriod() {
        return System.currentTimeMillis() - lastJoinTime < GRACE_PERIOD_MS;
    }

    public boolean isInventoryOpen() {
        return inventoryOpen;
    }

    public void setInventoryOpen(boolean inventoryOpen, Location loc) {
        this.inventoryOpen = inventoryOpen;
        this.inventoryOpenLocation = loc;
        if (inventoryOpen) {
            this.inventoryOpenTime = System.currentTimeMillis();
        }
    }

    public long getInventoryOpenTime() {
        return inventoryOpenTime;
    }

    public Location getInventoryOpenLocation() {
        return inventoryOpenLocation;
    }

    public long getLastInventoryCloseTime() {
        return lastInventoryCloseTime;
    }

    public void setLastInventoryCloseTime(long lastInventoryCloseTime) {
        this.lastInventoryCloseTime = lastInventoryCloseTime;
    }

    public Deque<Long> getClickTimestamps() {
        return clickTimestamps;
    }

    public boolean isDigging() {
        long now = System.currentTimeMillis();
        return digging || (now - lastDiggingTime < 300L) || (now - lastDiggingStopTime < 200L);
    }

    public void setDigging(boolean digging) {
        long now = System.currentTimeMillis();
        if (digging) {
            startDigging(now);
        } else {
            stopDigging(now);
        }
    }

    public void startDigging(long now) {
        this.digging = true;
        this.diggingStartTime = now;
        this.lastDiggingTime = now;
        this.clickTimestamps.clear();
        this.flyingIntervalSamples.clear();
        this.msIntervalSamples.clear();
        this.skipNextIntervalSample = true;
    }

    public void stopDigging(long now) {
        this.digging = false;
        this.lastDiggingStopTime = now;
        this.lastDiggingTime = now;
        // 关键硬截断：退出挖掘状态后的首次战斗点击，绝不与挖掘期间最后一次挥臂算作间隔！
        this.skipNextIntervalSample = true;
        this.clickTimestamps.clear();
    }

    public long getLastDiggingTime() {
        return lastDiggingTime;
    }

    public void setLastDiggingTime(long lastDiggingTime) {
        this.lastDiggingTime = lastDiggingTime;
    }

    public long getDiggingStartTime() {
        return diggingStartTime;
    }

    public void setDiggingStartTime(long diggingStartTime) {
        this.diggingStartTime = diggingStartTime;
    }

    public long getClientFlyingTicks() {
        return clientFlyingTicks;
    }

    public long incrementClientFlyingTicks(long nowNanos) {
        this.clientFlyingTicks++;
        this.clicksInCurrentFlyingTick = 0;
        if (lastFlyingPacketNanos > 0L) {
            long diff = nowNanos - lastFlyingPacketNanos;
            this.lastFlyingPacketIntervalNanos = diff;
            // 客户端挂起恢复检测：超过 500ms（10 ticks）无移动包，说明发生了 Alt+Tab、切屏、F3+T 或长卡顿
            if (diff > 500_000_000L) {
                this.lastWindowFocusRecoveryTime = System.currentTimeMillis();
                this.skipNextIntervalSample = true;
            }
        }
        this.lastFlyingPacketNanos = nowNanos;
        return this.clientFlyingTicks;
    }

    public int incrementClicksInCurrentFlyingTick() {
        return ++this.clicksInCurrentFlyingTick;
    }

    public int getClicksInCurrentFlyingTick() {
        return clicksInCurrentFlyingTick;
    }

    public boolean isInFocusRecoveryGracePeriod() {
        long now = System.currentTimeMillis();
        return (now - lastWindowFocusRecoveryTime < 350L)
                || (now - lastInventoryCloseTime < 250L)
                || (this.clientFlyingTicks - this.lastContainerCloseTick < 6L);
    }

    public long getLastFlyingPacketIntervalNanos() {
        return lastFlyingPacketIntervalNanos;
    }

    public long getLastContainerCloseTick() {
        return lastContainerCloseTick;
    }

    public void markContainerClosed(long now) {
        this.lastInventoryCloseTime = now;
        this.lastContainerCloseTick = this.clientFlyingTicks;
        this.skipNextIntervalSample = true;
    }

    public boolean recordInteractEntity(int entityId, boolean isInteractAt) {
        long currentTick = this.clientFlyingTicks;
        if (isInteractAt) {
            this.lastInteractEntityId = entityId;
            this.lastInteractAtClientTick = currentTick;
            return true;
        } else {
            // 若为普通 INTERACT 且在同 1 tick 内对同一实体发生，属于原版孪生包，标记为聚合去重
            if (this.lastInteractEntityId == entityId && this.lastInteractAtClientTick == currentTick) {
                return false;
            }
            this.lastInteractEntityId = entityId;
            return true;
        }
    }

    public void recordSlotSwitch(int newSlot) {
        this.currentHeldSlot = newSlot;
        long now = System.currentTimeMillis();
        this.lastSlotSwitchTime = now;
        this.heldItemSwitchTimes.addLast(now);
        while (!heldItemSwitchTimes.isEmpty() && now - heldItemSwitchTimes.peekFirst() > 1000L) {
            heldItemSwitchTimes.pollFirst();
        }
    }

    public boolean hasRecentSlotSwitch(long windowMs) {
        return (System.currentTimeMillis() - lastSlotSwitchTime) <= windowMs;
    }

    public int getCurrentHeldSlot() {
        return currentHeldSlot;
    }

    public void recordSwing() {
        this.lastSwingClientTick = this.clientFlyingTicks;
        // 双向配对：若本 tick 或前 1 个 tick 刚刚发生了 Attack，挥臂后置补齐配对，消除翻转误判
        if (lastAttackClientTick >= 0 && Math.abs(this.lastSwingClientTick - lastAttackClientTick) <= 1) {
            this.consecutiveNoSwingAttacks = 0;
        }
    }

    public void recordAttack() {
        this.lastAttackClientTick = this.clientFlyingTicks;
        // 双向配对：若本 tick 或前 1 个 tick 已经发生过 Swing，配对成功
        if (lastSwingClientTick >= 0 && Math.abs(this.lastAttackClientTick - lastSwingClientTick) <= 1) {
            this.consecutiveNoSwingAttacks = 0;
        } else {
            // 允许给可能后置到达的 Swing 预留 1 刻宽容窗口，仅在历史从未配对或连续多次时累积
            this.consecutiveNoSwingAttacks++;
        }
    }

    public int getConsecutiveNoSwingAttacks() {
        return consecutiveNoSwingAttacks;
    }

    public void recordClickSample(double msInterval, int flyingTickInterval) {
        if (skipNextIntervalSample) {
            skipNextIntervalSample = false;
            return;
        }
        if (msInterval > 15.0 && msInterval < 300.0) {
            msIntervalSamples.addLast(msInterval);
            while (msIntervalSamples.size() > 40) {
                msIntervalSamples.pollFirst();
            }
        }
        if (flyingTickInterval > 0 && flyingTickInterval <= 10) {
            flyingIntervalSamples.addLast(flyingTickInterval);
            while (flyingIntervalSamples.size() > 40) {
                flyingIntervalSamples.pollFirst();
            }
        }
    }

    public Deque<Double> getMsIntervalSamples() {
        return msIntervalSamples;
    }

    public Deque<Integer> getFlyingIntervalSamples() {
        return flyingIntervalSamples;
    }

    public void resetClickIntervalTracking() {
        this.skipNextIntervalSample = true;
        this.clickTimestamps.clear();
        this.msIntervalSamples.clear();
        this.flyingIntervalSamples.clear();
    }

    public int getSoulSpeedTicks() {
        return soulSpeedTicks;
    }

    public void setSoulSpeedTicks(int soulSpeedTicks) {
        this.soulSpeedTicks = soulSpeedTicks;
    }

    public void decrementSoulSpeedTicks() {
        if (this.soulSpeedTicks > 0) {
            this.soulSpeedTicks--;
        }
    }

    public long getLastClickTimestamp() {
        return lastClickTimestamp;
    }

    public void setLastClickTimestamp(long lastClickTimestamp) {
        this.lastClickTimestamp = lastClickTimestamp;
    }

    public UUID getLastAttackedTarget() {
        return lastAttackedTarget;
    }

    public void setLastAttackedTarget(UUID lastAttackedTarget) {
        this.lastAttackedTarget = lastAttackedTarget;
    }

    public long getLastAttackTime() {
        return lastAttackTime;
    }

    public void setLastAttackTime(long lastAttackTime) {
        this.lastAttackTime = lastAttackTime;
    }

    public long getLastBreakTick() {
        return lastBreakTick;
    }

    public void setLastBreakTick(long lastBreakTick) {
        this.lastBreakTick = lastBreakTick;
    }

    public long getLastBreakTimestamp() {
        return lastBreakTimestamp;
    }

    public void setLastBreakTimestamp(long lastBreakTimestamp) {
        this.lastBreakTimestamp = lastBreakTimestamp;
    }

    public long getLastDamageTime() {
        return lastDamageTime;
    }

    public void setLastDamageTime(long lastDamageTime) {
        this.lastDamageTime = lastDamageTime;
    }

    public void recordDamageLocation(Location loc) {
        if (loc != null) {
            this.lastDamageLocation = loc.clone();
            this.lastDamageLocationTime = System.currentTimeMillis();
        }
    }

    public Location getLastDamageLocation() {
        return lastDamageLocation;
    }

    public long getLastDamageLocationTime() {
        return lastDamageLocationTime;
    }

    public boolean isAirborneFromIce() {
        return airborneFromIce;
    }

    public void setAirborneFromIce(boolean airborneFromIce) {
        this.airborneFromIce = airborneFromIce;
    }

    public boolean hasRecentVelocity(long thresholdMs) {
        long now = System.currentTimeMillis();
        // 原版空中空气阻力小 (0.91)，若玩家仍处于空中滞空未着地，击退/受击抛物线通常持续 15~20 ticks (750~1000ms)，额外延长
        // 500ms
        long effectiveThreshold = (!onGround || airTicks > 0) ? thresholdMs + 500L : thresholdMs;
        boolean recentVel = (lastVelocityReceivedTime > 0L) && (now - lastVelocityReceivedTime < effectiveThreshold);
        boolean recentDmg = (lastDamageTime > 0L) && (now - lastDamageTime < effectiveThreshold);
        return recentVel || recentDmg;
    }

    public int getBreaksThisTick() {
        return breaksThisTick;
    }

    public void setBreaksThisTick(int breaksThisTick) {
        this.breaksThisTick = breaksThisTick;
    }

    public Deque<Long> getBreakTimestamps() {
        return breakTimestamps;
    }

    public long getLastPlaceTime() {
        return lastPlaceTime;
    }

    public void setLastPlaceTime(long lastPlaceTime) {
        this.lastPlaceTime = lastPlaceTime;
    }

    public ElytraData getElytraData() {
        return elytraData;
    }

    public boolean isElytraGliding() {
        return elytraData != null && elytraData.isGliding();
    }

    private volatile String clientBrand = "vanilla";

    public String getClientBrand() {
        return clientBrand;
    }

    public void setClientBrand(String clientBrand) {
        this.clientBrand = clientBrand;
    }

    public PlayerPhysicsState getPhysicsState() {
        return physicsState;
    }

    public com.example.argusac.packet.TimerTracker getTimerTracker() {
        return timerTracker;
    }

    /**
     * 彻底清除该玩家的所有运行时违规、日志与高机动时效状态 (用于 GDPR/合规性安全注销)
     */
    public void clearAllData() {
        violationLevels.clear();
        violationLogs.clear();
        failureLogs.clear();
        legalVelocitySources.clear();
        lastPunishedVl = 0;
        physicsState.reset(0.0, 0.0, 0.0, 0.0f, 0.0f);
        timerTracker.reset();
        speedBuffer = 0.0;
        lastSpeedCheckNanos = 0L;
        iceTicks = 0;
        resetWaterTrackingState();
        resetMaceFallState(0.0);
        lastMaceSmashTime = 0L;
    }

    public double getSpeedBuffer() {
        return speedBuffer;
    }

    public void setSpeedBuffer(double speedBuffer) {
        this.speedBuffer = Math.max(0.0, speedBuffer);
    }

    public long updateSpeedCheckTime(long nowNanos) {
        long previous = lastSpeedCheckNanos;
        lastSpeedCheckNanos = nowNanos;
        return previous;
    }

    public void resetSpeedTracking() {
        speedBuffer = 0.0;
        lastSpeedCheckNanos = System.nanoTime();
    }

    public int getIceTicks() {
        return iceTicks;
    }

    public void setIceTicks(int iceTicks) {
        this.iceTicks = iceTicks;
    }

    public void decrementIceTicks() {
        if (this.iceTicks > 0) {
            this.iceTicks--;
        }
    }

    public float getLastLookYaw() {
        return lastLookYaw;
    }

    public void setLastLookYaw(float lastLookYaw) {
        this.lastLookYaw = lastLookYaw;
    }

    public float getLastLookPitch() {
        return lastLookPitch;
    }

    public void setLastLookPitch(float lastLookPitch) {
        this.lastLookPitch = lastLookPitch;
    }

    // 水面平移与悬浮追踪 (JesusCheck)
    private int waterHoverTicks = 0;
    private int waterSpeedTicks = 0;
    private long lastSolidSupportTime = 0L;
    private long lastWaterHoverTickNanos = 0L;

    // 水面持续停留累积追踪 (跨检测周期，仅离开水面/深潜时重置)
    private int waterSurfaceTicks = 0;
    private long lastWaterSurfaceTickNanos = 0L;

    public int getWaterHoverTicks() {
        return waterHoverTicks;
    }

    public void setWaterHoverTicks(int ticks) {
        this.waterHoverTicks = ticks;
    }

    public boolean incrementWaterHoverTicks() {
        long now = System.nanoTime();
        if (lastWaterHoverTickNanos != 0L && now - lastWaterHoverTickNanos < 50_000_000L) {
            return false;
        }
        lastWaterHoverTickNanos = now;
        this.waterHoverTicks++;
        return true;
    }

    public void resetWaterHoverTicks() {
        this.waterHoverTicks = 0;
        this.lastWaterHoverTickNanos = 0L;
    }

    public int getWaterSurfaceTicks() {
        return waterSurfaceTicks;
    }

    /**
     * 递增水面持续停留滴答数 (限速：至少间隔 50ms / 1 tick)
     * 
     * @return 是否成功递增 (若调用过于频繁则返回 false)
     */
    public boolean incrementWaterSurfaceTicks() {
        long now = System.nanoTime();
        if (lastWaterSurfaceTickNanos != 0L && now - lastWaterSurfaceTickNanos < 50_000_000L) {
            return false;
        }
        lastWaterSurfaceTickNanos = now;
        this.waterSurfaceTicks++;
        return true;
    }

    /**
     * 完全重置水面停留累积计数 (仅在玩家真正离开水面时调用)
     */
    public void resetWaterSurfaceTicks() {
        this.waterSurfaceTicks = 0;
        this.lastWaterSurfaceTickNanos = 0L;
    }

    /**
     * 设置水面停留累积计数 (用于惩罚后部分重置)
     */
    public void setWaterSurfaceTicks(int ticks) {
        this.waterSurfaceTicks = Math.max(0, ticks);
    }

    public int getWaterSpeedTicks() {
        return waterSpeedTicks;
    }

    public void incrementWaterSpeedTicks() {
        this.waterSpeedTicks++;
    }

    public void resetWaterSpeedTicks() {
        this.waterSpeedTicks = 0;
    }

    public void setWaterSpeedTicks(int ticks) {
        this.waterSpeedTicks = Math.max(0, ticks);
    }

    public long getLastSolidSupportTime() {
        return lastSolidSupportTime;
    }

    public void markSolidSupportNow() {
        this.lastSolidSupportTime = System.currentTimeMillis();
    }

    public void resetWaterTrackingState() {
        resetWaterHoverTicks();
        resetWaterSurfaceTicks();
        resetWaterSpeedTicks();
    }

    public boolean isClientOnGround() {
        return clientOnGround;
    }

    public void setClientOnGround(boolean clientOnGround) {
        this.clientOnGround = clientOnGround;
    }

    // 悬停平飞与反重力连续采样追踪 (FlyCheck)
    private int flatHoverTicks = 0;
    private int antiGravityTicks = 0;
    private int slimeTicks = 0;

    public int getFlatHoverTicks() {
        return flatHoverTicks;
    }

    public void setFlatHoverTicks(int ticks) {
        this.flatHoverTicks = ticks;
    }

    public void incrementFlatHoverTicks() {
        this.flatHoverTicks++;
    }

    public void resetFlatHoverTicks() {
        this.flatHoverTicks = 0;
    }

    public int getAntiGravityTicks() {
        return antiGravityTicks;
    }

    public void setAntiGravityTicks(int ticks) {
        this.antiGravityTicks = ticks;
    }

    public void incrementAntiGravityTicks() {
        this.antiGravityTicks++;
    }

    public void resetAntiGravityTicks() {
        this.antiGravityTicks = 0;
    }

    public int getSlimeTicks() {
        return slimeTicks;
    }

    public void setSlimeTicks(int slimeTicks) {
        this.slimeTicks = slimeTicks;
    }

    public void decrementSlimeTicks() {
        if (this.slimeTicks > 0) {
            this.slimeTicks--;
        }
    }

    // 自瞄突变连续命中追踪 (KillAuraCheck)
    private int snapCount = 0;
    private long lastSnapTime = 0L;
    private int angleAnomalyCount = 0;
    private long lastAngleAnomalyTime = 0L;

    public int recordSnap(long now) {
        if (now - lastSnapTime < 800L) {
            snapCount++;
        } else {
            snapCount = 1;
        }
        lastSnapTime = now;
        return snapCount;
    }

    public void resetSnap() {
        snapCount = 0;
        lastSnapTime = 0L;
    }

    /**
     * 记录连续视角偏角异常次数（用于吸收客户端 INTERACT_ENTITY 早于 PLAYER_ROTATION 到达引起的单次甩鼠反击/受击硬直视角差）
     */
    public int recordAngleAnomaly(long now) {
        if (now - lastAngleAnomalyTime < 1200L) {
            angleAnomalyCount++;
        } else {
            angleAnomalyCount = 1;
        }
        lastAngleAnomalyTime = now;
        return angleAnomalyCount;
    }

    public void resetAngleAnomaly() {
        angleAnomalyCount = 0;
        lastAngleAnomalyTime = 0L;
    }

    // 潜行疾跑状态突变追踪 (BadPacketsCheck)
    private int sneakSprintTicks = 0;

    public int getSneakSprintTicks() {
        return sneakSprintTicks;
    }

    public void incrementSneakSprintTicks() {
        this.sneakSprintTicks++;
    }

    public void resetSneakSprintTicks() {
        this.sneakSprintTicks = 0;
    }

    // 弓箭蓄力与射速状态追踪 (FastBowCheck)
    private volatile boolean bowCharging = false;
    private volatile long bowChargeStartTick = -1L;
    private volatile long bowChargeStartTimeMs = 0L;
    private volatile long lastBowShotTime = 0L;
    private volatile long lastBowShotTick = 0L;
    private final Deque<Long> bowShotTimestamps = new ConcurrentLinkedDeque<>();

    // 快速进食与物品使用状态追踪 (FastEatCheck)
    private volatile boolean eating = false;
    private volatile long eatStartTick = -1L;
    private volatile long eatStartTimeMs = 0L;
    private volatile String eatingItemType = null;

    public void startEating(long currentTick, long nowMs, String itemType) {
        // 若已经在进食同一种物品且未超时 (2000ms 内)，避免重复右键交互重置起始时间导致 FastEat 误报
        if (this.eating && itemType != null && itemType.equals(this.eatingItemType)
                && this.eatStartTimeMs > 0L && (nowMs - this.eatStartTimeMs) < 2000L) {
            return;
        }
        this.eating = true;
        this.eatStartTick = currentTick;
        this.eatStartTimeMs = nowMs;
        this.eatingItemType = itemType;
    }

    public void stopEating() {
        this.eating = false;
        this.eatStartTick = -1L;
        this.eatStartTimeMs = 0L;
        this.eatingItemType = null;
    }

    public boolean isEating() {
        return eating;
    }

    public long getEatStartTick() {
        return eatStartTick;
    }

    public long getEatStartTimeMs() {
        return eatStartTimeMs;
    }

    public String getEatingItemType() {
        return eatingItemType;
    }

    public void startBowCharge(long currentTick, long nowMs) {
        // 若已经处于拉弓蓄力状态且未超时 (3500ms 内)，避免准星划过方块再次触发交互重置蓄力起点导致 FastBow 误报
        if (this.bowCharging && this.bowChargeStartTimeMs > 0L && (nowMs - this.bowChargeStartTimeMs) < 3500L) {
            return;
        }
        this.bowCharging = true;
        this.bowChargeStartTick = currentTick;
        this.bowChargeStartTimeMs = nowMs;
    }

    public void stopBowCharge() {
        this.bowCharging = false;
        this.bowChargeStartTick = -1L;
        this.bowChargeStartTimeMs = 0L;
    }

    public boolean isBowCharging() {
        return bowCharging;
    }

    public long getBowChargeStartTick() {
        return bowChargeStartTick;
    }

    public long getBowChargeStartTimeMs() {
        return bowChargeStartTimeMs;
    }

    public long getLastBowShotTime() {
        return lastBowShotTime;
    }

    public long getLastBowShotTick() {
        return lastBowShotTick;
    }

    public int recordBowShot(long currentTick, long nowMs) {
        this.lastBowShotTime = nowMs;
        this.lastBowShotTick = currentTick;
        this.bowShotTimestamps.addLast(nowMs);
        while (!bowShotTimestamps.isEmpty() && (nowMs - bowShotTimestamps.peekFirst() > 1000L)) {
            bowShotTimestamps.pollFirst();
        }
        return bowShotTimestamps.size();
    }

    public int getBowShotsInWindow(long windowMs, long nowMs) {
        int count = 0;
        for (Long ts : bowShotTimestamps) {
            if (ts != null && (nowMs - ts) <= windowMs) {
                count++;
            }
        }
        return count;
    }

    // 重锤下落物理真峰值与滞空状态追踪 (MaceCheck / MaceDMG)
    private volatile double maceApexY = 0.0;
    private volatile double maceGroundY = 0.0;
    private volatile int maceAirTicks = 0;
    private volatile long lastMaceSmashTime = 0L;
    private volatile double lastLandedMaceApexY = 0.0;
    private volatile int lastLandedMaceAirTicks = 0;
    private volatile long lastLandedMaceTime = 0L;

    public void resetMaceFallState(double currentY) {
        if (this.maceAirTicks > 0 && (this.maceApexY - currentY) >= 1.45) {
            this.lastLandedMaceApexY = this.maceApexY;
            this.lastLandedMaceAirTicks = this.maceAirTicks;
            this.lastLandedMaceTime = System.currentTimeMillis();
        }
        this.maceApexY = currentY;
        this.maceGroundY = currentY;
        this.maceAirTicks = 0;
    }

    public double getLastLandedMaceApexY() {
        return lastLandedMaceApexY;
    }

    public int getLastLandedMaceAirTicks() {
        return lastLandedMaceAirTicks;
    }

    public long getLastLandedMaceTime() {
        return lastLandedMaceTime;
    }

    public void updateMaceApex(double currentY) {
        this.maceAirTicks++;
        if (currentY > this.maceApexY) {
            this.maceApexY = currentY;
        }
    }

    public double getMaceApexY() {
        return maceApexY;
    }

    public void setMaceApexY(double maceApexY) {
        this.maceApexY = maceApexY;
    }

    public double getMaceGroundY() {
        return maceGroundY;
    }

    public void setMaceGroundY(double maceGroundY) {
        this.maceGroundY = maceGroundY;
    }

    public int getMaceAirTicks() {
        return maceAirTicks;
    }

    public void setMaceAirTicks(int maceAirTicks) {
        this.maceAirTicks = maceAirTicks;
    }

    public long getLastMaceSmashTime() {
        return lastMaceSmashTime;
    }

    public void setLastMaceSmashTime(long lastMaceSmashTime) {
        this.lastMaceSmashTime = lastMaceSmashTime;
    }

    public long getLastRespawnTime() {
        return lastRespawnTime;
    }

    public void setLastRespawnTime(long lastRespawnTime) {
        this.lastRespawnTime = lastRespawnTime;
    }

    public void updatePacketLocation(double x, double y, double z, float yaw, float pitch) {
        this.lastLookYaw = this.packetYaw;
        this.lastLookPitch = this.packetPitch;
        this.packetX = x;
        this.packetY = y;
        this.packetZ = z;
        this.packetYaw = yaw;
        this.packetPitch = pitch;
        this.hasPacketLocation = true;
        this.lastPacketLocationTime = System.currentTimeMillis();
    }

    public void updatePacketPosition(double x, double y, double z) {
        this.packetX = x;
        this.packetY = y;
        this.packetZ = z;
        this.hasPacketLocation = true;
        this.lastPacketLocationTime = System.currentTimeMillis();
    }

    public void updatePacketRotation(float yaw, float pitch) {
        this.lastLookYaw = this.packetYaw;
        this.lastLookPitch = this.packetPitch;
        this.packetYaw = yaw;
        this.packetPitch = pitch;
        this.lastPacketLocationTime = System.currentTimeMillis();
    }

    public boolean hasPacketLocation() {
        return hasPacketLocation;
    }

    public double getPacketX() {
        return packetX;
    }

    public double getPacketY() {
        return packetY;
    }

    public double getPacketZ() {
        return packetZ;
    }

    public float getPacketYaw() {
        return packetYaw;
    }

    public float getPacketPitch() {
        return packetPitch;
    }

    public Location getLatestPacketEyeLocation(org.bukkit.World world, double fallbackEyeHeight) {
        if (world == null)
            return null;
        if (hasPacketLocation && (System.currentTimeMillis() - lastPacketLocationTime < 5000L)) {
            return new Location(world, packetX, packetY + fallbackEyeHeight, packetZ, packetYaw, packetPitch);
        }
        if (toLocation != null && toLocation.getWorld() == world) {
            return new Location(world, toLocation.getX(), toLocation.getY() + fallbackEyeHeight, toLocation.getZ(),
                    lastLookYaw, lastLookPitch);
        }
        return null;
    }

    /**
     * 获取攻击者在当前与相邻帧的候选眼睛位置集合（搭载最新实时视角），
     * 消除受击击退/高速移动或客户端 INTERACT_ENTITY 先于移动包到达时的单帧自身坐标偏移误报
     */
    public List<Location> getCandidateEyeLocations(org.bukkit.World world, double eyeHeight, Location entityBaseLoc) {
        List<Location> list = new ArrayList<>(4);
        if (world == null) {
            return list;
        }
        float yaw = hasPacketLocation ? packetYaw : (entityBaseLoc != null ? entityBaseLoc.getYaw() : lastLookYaw);
        float pitch = hasPacketLocation ? packetPitch
                : (entityBaseLoc != null ? entityBaseLoc.getPitch() : lastLookPitch);

        if (hasPacketLocation && (System.currentTimeMillis() - lastPacketLocationTime < 5000L)) {
            list.add(new Location(world, packetX, packetY + eyeHeight, packetZ, yaw, pitch));
        }
        if (toLocation != null && toLocation.getWorld() == world) {
            list.add(new Location(world, toLocation.getX(), toLocation.getY() + eyeHeight, toLocation.getZ(), yaw,
                    pitch));
        }
        if (fromLocation != null && fromLocation.getWorld() == world) {
            list.add(new Location(world, fromLocation.getX(), fromLocation.getY() + eyeHeight, fromLocation.getZ(), yaw,
                    pitch));
        }
        if (entityBaseLoc != null && entityBaseLoc.getWorld() == world) {
            list.add(new Location(world, entityBaseLoc.getX(), entityBaseLoc.getY() + eyeHeight, entityBaseLoc.getZ(),
                    entityBaseLoc.getYaw(), entityBaseLoc.getPitch()));
        }
        if (lastDamageLocation != null && lastDamageLocation.getWorld() == world
                && (System.currentTimeMillis() - lastDamageLocationTime < 1500L)) {
            list.add(new Location(world, lastDamageLocation.getX(), lastDamageLocation.getY() + eyeHeight,
                    lastDamageLocation.getZ(), yaw, pitch));
        }
        return list;
    }

    public void recordVerifiedAttack(UUID targetUuid) {
        this.lastVerifiedAttackTarget = targetUuid;
        this.lastVerifiedAttackTime = System.currentTimeMillis();
    }

    public boolean isAttackRecentlyVerified(UUID targetUuid, long windowMs) {
        if (targetUuid == null || lastVerifiedAttackTarget == null || !lastVerifiedAttackTarget.equals(targetUuid)) {
            return false;
        }
        return (System.currentTimeMillis() - lastVerifiedAttackTime) <= windowMs;
    }
}
