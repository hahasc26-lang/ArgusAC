package com.example.argusac.manager;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.api.CheckType;
import com.example.argusac.data.PlayerData;
import com.example.argusac.log.model.SetbackFailureLog;
import com.example.argusac.log.model.ViolationLog;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.model.SetbackOverride;
import com.example.argusac.setback.ActiveHoverTracker;
import com.example.argusac.setback.PlayerSetbackContext;
import com.example.argusac.setback.SafeLocationFinder;
import com.example.argusac.setback.SetbackNotifier;
import com.example.argusac.setback.SetbackPacketSync;
import com.example.argusac.setback.SetbackPhysics;
import com.example.argusac.setback.handler.CancelSetbackHandler;
import com.example.argusac.setback.handler.HardSetbackHandler;
import com.example.argusac.setback.handler.SoftSetbackHandler;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态回退全能集中门面管理器 (SetbackManager)
 * <p>
 * 采用模块化微内核架构设计，将职责细分解耦至专门子模块：
 * 1. CancelSetbackHandler: 负责 CANCEL 模式的即时当帧拦截、负数 Teleport ID 隔离与锁定锚点防蠕变；
 * 2. SoftSetbackHandler: 负责 SOFT 模式的目标坐标平滑重写与空中自由落体拟合；
 * 3. HardSetbackHandler: 负责 HARD 模式的有界合法快照查找、主线程传送与网络校准序列；
 * 4. SafeLocationFinder: 负责安全地面与 AABB 碰撞箱 Top-Y 精细贴合探测；
 * 5. SetbackPhysics: 负责原版重力加速度曲线仿真计算；
 * 6. SetbackPacketSync: 负责 PacketEvents 协议级网络位置重置报文下发；
 * 7. SetbackNotifier: 负责提示通知、管理广播、安全踢出与防篡改审计日志记录；
 * 8. PlayerSetbackContext: 维护单名玩家的快照历史、锁定锚点与校正时钟状态。
 */
public class SetbackManager implements Listener {

    private final ArgusACPlugin plugin;
    private final SafeLocationFinder safeLocationFinder;
    private final SetbackNotifier setbackNotifier;

    // 三种独立回退模式处理器
    private final CancelSetbackHandler cancelHandler;
    private final SoftSetbackHandler softHandler;
    private final HardSetbackHandler hardHandler;

    // ==================== 配置参数 ====================
    private boolean globalEnabled;
    private String defaultMode; // "CANCEL", "SOFT" 或 "HARD"
    private long cooldownMs;
    private int globalMinVl;
    private boolean kickWhenBothBlocked;
    private String kickMessage;
    private final Set<String> exemptedWorlds = new HashSet<>();

    // 边界与快照配置
    private double maxSetbackDistance;
    private long maxSnapshotAgeMs;
    private int maxSnapshotCapacity;

    // 网络校正参数
    private long correctionTimeoutMs;

    // 提示通知参数
    private String notifyPlayerChannel; // "ACTIONBAR", "CHAT", "NONE"
    private long notifyPlayerCooldownMs;
    private boolean notifyAdminEnabled;

    // ==================== 集中化状态存储 ====================
    private final Map<UUID, PlayerSetbackContext> contextMap = new ConcurrentHashMap<>();
    private final Set<UUID> selfTeleportingPlayers = ConcurrentHashMap.newKeySet();
    private BukkitTask activeGravityTask = null;

    public SetbackManager(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.safeLocationFinder = new SafeLocationFinder();
        this.setbackNotifier = new SetbackNotifier(plugin);
        this.cancelHandler = new CancelSetbackHandler(plugin, this, safeLocationFinder, setbackNotifier);
        this.softHandler = new SoftSetbackHandler(plugin, this, setbackNotifier);
        this.hardHandler = new HardSetbackHandler(plugin, this, safeLocationFinder, setbackNotifier);

        this.reloadConfig();
        this.startActiveGravityTask();
    }

    /**
     * 重载回退配置文件
     */
    public void reloadConfig() {
        FileConfiguration config = plugin.getConfig();

        this.globalEnabled = config.getBoolean("setback.global-enabled", true);
        this.defaultMode = config.getString("setback.default-mode", "CANCEL").toUpperCase();
        this.cooldownMs = config.getLong("setback.cooldown-ms", 350L);
        this.globalMinVl = config.getInt("setback.default-min-vl", 1);
        this.kickWhenBothBlocked = config.getBoolean("setback.boundary.kick-when-both-blocked", false);
        this.kickMessage = config.getString("setback.boundary.kick-message",
                "&c[ArgusAC] Irregular movement detected and previous location obstructed. Disconnected safely.");

        this.exemptedWorlds.clear();
        for (String w : config.getStringList("setback.exempted-worlds")) {
            this.exemptedWorlds.add(w.toLowerCase());
        }

        this.maxSetbackDistance = config.getDouble("setback.boundary.max-distance", 8.0);
        this.maxSnapshotAgeMs = config.getLong("setback.snapshot.max-age-ms", 2500L);
        this.maxSnapshotCapacity = config.getInt("setback.snapshot.max-capacity", 30);

        this.correctionTimeoutMs = config.getLong("setback.correction.timeout-ms", 600L);

        this.notifyPlayerChannel = config.getString("setback.notifications.player.channel", "ACTIONBAR").toUpperCase();
        this.notifyPlayerCooldownMs = config.getLong("setback.notifications.player.cooldown-ms", 1000L);

        this.notifyAdminEnabled = config.getBoolean("setback.notifications.admin.enabled", true);
    }

    // ==============================================================================
    // 自包含生命周期与移动监听 (Event Listeners)
    // ==============================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerSetbackContext context = getContext(player.getUniqueId());
        context.reset(player.getLocation(), player.isInsideVehicle(),
                player.getVehicle() != null ? player.getVehicle().getUniqueId() : null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uid = event.getPlayer().getUniqueId();
        contextMap.remove(uid);
        selfTeleportingPlayers.remove(uid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        PlayerSetbackContext context = contextMap.get(event.getEntity().getUniqueId());
        if (context != null) {
            context.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        PlayerSetbackContext context = getContext(event.getPlayer().getUniqueId());
        context.reset(event.getRespawnLocation(), false, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlayerSetbackContext context = getContext(player.getUniqueId());
        UUID vehicleId = (player.isInsideVehicle() && player.getVehicle() != null)
                ? player.getVehicle().getUniqueId() : null;
        context.reset(player.getLocation(), player.isInsideVehicle(), vehicleId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID uid = player.getUniqueId();

        // 识别本反作弊自身发起的内部回退传送，跳过重置
        if (selfTeleportingPlayers.contains(uid)) {
            return;
        }

        Location to = event.getTo();
        if (to != null) {
            PlayerSetbackContext context = getContext(uid);
            UUID vehicleId = (player.isInsideVehicle() && player.getVehicle() != null)
                    ? player.getVehicle().getUniqueId() : null;
            context.reset(to, player.isInsideVehicle(), vehicleId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            PlayerSetbackContext context = getContext(player.getUniqueId());
            context.resetPreviousOnly();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            PlayerSetbackContext context = getContext(player.getUniqueId());
            context.resetPreviousOnly();
        }
    }

    /**
     * 在 MONITOR 优先级监听移动事件：
     * 仅当移动事件已通过所有检测模块 (HIGHEST 优先级)、未被取消且坐标确实改变时，采样维护短时快照
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
        }

        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) {
            return;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());

        if (context.hasPendingCorrection() && context.matchesCorrection(to)) {
            context.confirmTeleport(context.getInFlightTeleportId());
            context.clearPendingCorrection();
            context.setImmunityUntil(0L);
        }

        // 对标 GrimAC 权威安全锚点防污染法则：若处于在途传送未确认状态或违规锁定期内，严禁将未经验证的过渡位移记入合法快照！
        if (context.isTeleportInFlight() || context.isUnderViolationLock() || context.isImmune()) {
            return;
        }

        PlayerData pData = plugin.getPlayerManager().getPlayerData(player);
        // 若当前处于超速嫌疑累积期 (speedBuffer > 0.04)，绝不允许将涉嫌加速的坐标吸纳为合法安全快照！
        if (pData != null && pData.getSpeedBuffer() > 0.04) {
            return;
        }

        UUID vehicleId = (player.isInsideVehicle() && player.getVehicle() != null)
                ? player.getVehicle().getUniqueId() : null;

        boolean onGround = to.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid();
        if (pData != null && pData.isOnGround()) {
            onGround = true;
        }

        LegitimateLocation verified = LegitimateLocation.from(
                to,
                player.getVelocity(),
                onGround,
                player.isInsideVehicle(),
                vehicleId
        );

        if (verified != null) {
            context.update(verified, maxSnapshotCapacity, maxSnapshotAgeMs);
            if (verified.isOnGround()) {
                context.setLastGroundLocation(verified);
            }
        }
    }

    // ==============================================================================
    // 核心处罚决策与执行入口 (Mitigation API)
    // ==============================================================================

    /**
     * 判断某个检测模块和玩家当前是否应该执行回退 (仅限移动检测，按检测模块独立冷却)
     */
    public boolean shouldSetback(Player player, Check check, int currentVl) {
        if (player == null || check == null) {
            return false;
        }

        if (plugin.isPlayerExempt(player)) {
            return false;
        }

        if (check.getType() != CheckType.MOVEMENT) {
            return false;
        }

        if (player.getWorld() != null && exemptedWorlds.contains(player.getWorld().getName().toLowerCase())) {
            return false;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());

        SetbackOverride pOverride = context.getOverride();
        if (pOverride == SetbackOverride.DISABLED) {
            return false;
        }

        if (pOverride == SetbackOverride.DEFAULT) {
            if (!globalEnabled || !check.isSetbackEnabled()) {
                return false;
            }
        }

        int requiredVl = check.getSetbackMinVl() > 0 ? check.getSetbackMinVl() : globalMinVl;
        return currentVl >= requiredVl;
    }

    /**
     * 判断某个检测模块和玩家当前是否应该执行即时当帧位移取消 (CANCEL 模式)
     */
    public boolean shouldCancel(Player player, Check check, int currentVl) {
        return shouldSetback(player, check, currentVl);
    }

    public boolean shouldSetback(Player player, PlayerData data, Check check, int currentVl) {
        return shouldSetback(player, check, currentVl);
    }

    public boolean isCheckOnCooldown(UUID uuid, String checkName) {
        PlayerSetbackContext context = contextMap.get(uuid);
        if (context == null) return false;
        return (System.currentTimeMillis() - context.getLastSetbackTimestamp(checkName)) < cooldownMs;
    }

    public boolean isCorrectionMove(Player player, Location from, Location to) {
        if (player == null || from == null || to == null) return false;
        PlayerSetbackContext context = contextMap.get(player.getUniqueId());
        if (context == null) return false;
        return context.matchesCorrection(from) && context.matchesCorrection(to);
    }

    public void sendPositionSyncPacket(Player player, Location target) {
        SetbackPacketSync.sendPositionSyncPacket(player, target, 0, selfTeleportingPlayers);
    }

    public void sendPositionSyncPacket(Player player, Location target, int teleportId) {
        SetbackPacketSync.sendPositionSyncPacket(player, target, teleportId, selfTeleportingPlayers);
    }

    /**
     * 零延迟当帧直接取消 (Direct Cancellation) 核心机制
     */
    public boolean directCancel(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return cancelHandler.handle(player, check, currentVl, reason, moveEvent);
    }

    /**
     * 默认反作弊惩戒统一执行入口 (Mitigate / Setback)
     * 根据当前配置或覆盖策略智能路由至对应的处理器
     */
    public boolean mitigate(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        if (player == null || !player.isOnline() || check == null) {
            return false;
        }

        if (check.getType() != CheckType.MOVEMENT) {
            return false;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());
        SetbackOverride override = context.getOverride();
        String effectiveMode = check.getSetbackMode() != null ? check.getSetbackMode().toUpperCase() : defaultMode;
        if (override == SetbackOverride.SOFT_ONLY) {
            effectiveMode = "SOFT";
        } else if (override == SetbackOverride.HARD_ONLY) {
            effectiveMode = "HARD";
        } else if (override == SetbackOverride.CANCEL_ONLY) {
            effectiveMode = "CANCEL";
        }

        if ("CANCEL".equalsIgnoreCase(effectiveMode) && moveEvent != null) {
            return cancelHandler.handle(player, check, currentVl, reason, moveEvent);
        }

        if ("SOFT".equalsIgnoreCase(effectiveMode) && moveEvent != null) {
            return softHandler.handle(player, check, currentVl, reason, moveEvent);
        }

        return hardHandler.handle(player, check, currentVl, reason, moveEvent);
    }

    public boolean execute(Player player, PlayerData data, Check check,
                           int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    public boolean execute(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    public boolean isGrounded(Player player, Location loc) {
        PlayerData data = (player != null && plugin.getPlayerManager() != null)
                ? plugin.getPlayerManager().getPlayerData(player) : null;
        return safeLocationFinder.isGrounded(player, loc, data);
    }

    public static double computeVanillaGravityFall(int airTicks) {
        return SetbackPhysics.computeVanillaGravityFall(airTicks);
    }

    public void applySafeAirCancellation(PlayerMoveEvent moveEvent) {
        softHandler.applySafeAirCancellation(moveEvent);
    }

    public Location findSafeGroundNear(Location loc, double maxDistance) {
        return safeLocationFinder.findSafeGroundNear(loc, maxDistance);
    }

    public Location findSafeGroundBelow(Location loc) {
        return safeLocationFinder.findSafeGroundBelow(loc);
    }

    public void cleanup() {
        stopActiveGravityTask();
        contextMap.clear();
        selfTeleportingPlayers.clear();
    }

    public void startActiveGravityTask() {
        if (activeGravityTask != null) {
            activeGravityTask.cancel();
        }
        this.activeGravityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickActiveGravityEnforcement, 2L, 2L);
    }

    public void stopActiveGravityTask() {
        if (activeGravityTask != null) {
            activeGravityTask.cancel();
            activeGravityTask = null;
        }
    }

    /**
     * 服务端主动重力监管引擎：
     * 每 2 tick 主动巡检所有在线玩家，若处于空中无支撑面且没有原版重力下落位移，强制压制下坠！
     */
    private void tickActiveGravityEnforcement() {
        if (!globalEnabled) return;
        var adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) continue;
            if (plugin.isPlayerExempt(player)) continue;

            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                    || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) {
                continue;
            }

            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data == null || data.isInGracePeriod()) continue;

            if (adapter.isGliding(player)
                    || adapter.hasPotionEffect(player, com.example.argusac.compat.CompatPotionType.LEVITATION)
                    || adapter.hasPotionEffect(player, com.example.argusac.compat.CompatPotionType.SLOW_FALLING)) {
                continue;
            }

            PlayerSetbackContext context = getContext(player.getUniqueId());
            if (context == null) continue;
            boolean underViolationLock = context.isTeleportInFlight() || context.isUnderViolationLock();

            if (!underViolationLock && (data.hasAnyLegalVelocity() || data.hasRecentVelocity(1000L))) {
                continue;
            }

            Location curLoc = player.getLocation();
            if (curLoc == null || curLoc.getWorld() == null) continue;
            if (exemptedWorlds.contains(curLoc.getWorld().getName().toLowerCase())) continue;

            boolean grounded = isGrounded(player, curLoc) || (data != null && data.isOnGround());
            var feet = curLoc.getBlock();
            var head = curLoc.clone().add(0, 1.0, 0).getBlock();
            boolean climbOrSubmerged = adapter.isClimbableAround(player, curLoc)
                    || adapter.isClimbable(feet) || adapter.isClimbable(head)
                    || adapter.isSwimming(player)
                    || (adapter.isLiquid(feet) && adapter.isLiquid(head));

            // 若玩家接地、处于液体/攀爬环境，或 airTicks 为 0 (地面正常运动)，即刻重置断包悬浮追踪
            if (grounded || climbOrSubmerged || (data != null && data.getAirTicks() <= 0)) {
                context.resetActiveHoverTracking();
                continue;
            }

            ActiveHoverTracker tracker = context.getHoverTracker();
            tracker.tick(curLoc, now);

            // 针对真正断包挂起 (Blink / Freecam / Suspend) 悬空外挂：必须在完全脱离地面的空中持续悬空 20 ticks (1000ms) 以上
            if (tracker.isIllegalHover(20, 1000L)) {
                selfTeleportingPlayers.add(player.getUniqueId());
                try {
                    Location target = curLoc.clone();
                    double fallOffset = Math.min(-0.16, computeVanillaGravityFall(tracker.getAirTicks()) * 1.5);
                    target.setY(curLoc.getY() + fallOffset);
                    player.teleport(target, PlayerTeleportEvent.TeleportCause.UNKNOWN);

                    if (data.getPhysicsState() != null) {
                        data.getPhysicsState().reset(target);
                    }

                    Check flyCheck = plugin.getCheckManager() != null ? plugin.getCheckManager().getFlyCheck() : null;
                    if (flyCheck != null) {
                        plugin.getViolationManager().handleViolation(
                                player, flyCheck, 2,
                                String.format("ActiveHoverSuppression airTicks=%d, noGravityFall", tracker.getAirTicks()),
                                null
                        );
                    }
                } finally {
                    selfTeleportingPlayers.remove(player.getUniqueId());
                }
            }
        }
    }

    // ==============================================================================
    // 集中上下文访问与诊断运维 API
    // ==============================================================================

    public PlayerSetbackContext getContext(UUID uuid) {
        return contextMap.computeIfAbsent(uuid, k -> new PlayerSetbackContext());
    }

    public PlayerSetbackContext getExistingContext(UUID uuid) {
        return uuid != null ? contextMap.get(uuid) : null;
    }

    public void removeContext(UUID uuid) {
        if (uuid != null) {
            contextMap.remove(uuid);
            selfTeleportingPlayers.remove(uuid);
        }
    }

    public boolean isImmune(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        return context != null && context.isImmune();
    }

    public void clearImmunity(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        if (context != null) {
            context.setImmunityUntil(0L);
            context.clearPendingCorrection();
        }
    }

    public long getImmunityRemaining(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        if (context == null) return 0L;
        return Math.max(0L, context.getImmunityUntil() - System.currentTimeMillis());
    }

    public SetbackOverride getOverride(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        return context != null ? context.getOverride() : SetbackOverride.DEFAULT;
    }

    public void setOverride(UUID uuid, SetbackOverride override) {
        getContext(uuid).setOverride(override);
    }

    public LegitimateLocation getLastLocation(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        return context != null ? context.getLastLegitimateLocation() : null;
    }

    public LegitimateLocation getLastGroundLocation(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        return context != null ? context.getLastGroundLocation() : null;
    }

    public LegitimateLocation getPreviousLocation(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        return context != null ? context.getPreviousLegitimateLocation() : null;
    }

    public List<SetbackFailureLog> getFailureLogs(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        return context != null ? context.getFailureLogs() : Collections.emptyList();
    }

    public List<ViolationLog> getSetbackLogs(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        return context != null ? context.getLogs() : Collections.emptyList();
    }

    public boolean isSelfTeleport(UUID playerUuid) {
        return playerUuid != null && selfTeleportingPlayers.contains(playerUuid);
    }

    public void addSelfTeleporting(UUID playerUuid) {
        if (playerUuid != null) {
            selfTeleportingPlayers.add(playerUuid);
        }
    }

    public void removeSelfTeleporting(UUID playerUuid) {
        if (playerUuid != null) {
            selfTeleportingPlayers.remove(playerUuid);
        }
    }

    public boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public void setGlobalEnabled(boolean globalEnabled) {
        this.globalEnabled = globalEnabled;
        plugin.getConfig().set("setback.global-enabled", globalEnabled);
        plugin.saveConfig();
    }

    public String getDefaultMode() {
        return defaultMode;
    }

    public double getMaxSetbackDistance() {
        return maxSetbackDistance;
    }

    public long getMaxSnapshotAgeMs() {
        return maxSnapshotAgeMs;
    }

    public int getMaxSnapshotCapacity() {
        return maxSnapshotCapacity;
    }

    public long getCorrectionTimeoutMs() {
        return correctionTimeoutMs;
    }

    public boolean isKickWhenBothBlocked() {
        return kickWhenBothBlocked;
    }

    public String getKickMessage() {
        return kickMessage;
    }

    public String getNotifyPlayerChannel() {
        return notifyPlayerChannel;
    }

    public long getNotifyPlayerCooldownMs() {
        return notifyPlayerCooldownMs;
    }

    public boolean isNotifyAdminEnabled() {
        return notifyAdminEnabled;
    }

    public CancelSetbackHandler getCancelHandler() {
        return cancelHandler;
    }

    public SoftSetbackHandler getSoftHandler() {
        return softHandler;
    }

    public HardSetbackHandler getHardHandler() {
        return hardHandler;
    }

    public void clearPlayerCooldown(UUID uuid) {
        PlayerSetbackContext context = contextMap.get(uuid);
        if (context != null) {
            context.setLastNotifyTimestamp(0L);
        }
    }

    public void recordFailure(Player player, PlayerSetbackContext context, String checkName, String reason, String origLoc) {
        setbackNotifier.recordFailure(player, context, checkName, reason, origLoc);
    }

    public void dispatchKick(Player player, String rawMessage) {
        setbackNotifier.dispatchKick(player, rawMessage);
    }

    /**
     * 发送战斗攻击取消或方块交互取消的独立提示（与移动取消/位置回退严格区分）
     */
    public void notifyInteractionCancel(Player player, Check check, int currentVl) {
        if (player == null || check == null) {
            return;
        }
        PlayerSetbackContext context = getContext(player.getUniqueId());
        setbackNotifier.notifyInteractionCancel(
                player, context, check, currentVl,
                notifyPlayerChannel, notifyPlayerCooldownMs, notifyAdminEnabled
        );
    }
}
