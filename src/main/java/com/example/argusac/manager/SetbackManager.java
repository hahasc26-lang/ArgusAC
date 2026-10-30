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
import com.example.argusac.setback.PendingCorrection;
import com.example.argusac.setback.PlayerSetbackContext;
import com.example.argusac.setback.SafeLocationFinder;
import com.example.argusac.setback.SetbackNotifier;
import com.example.argusac.setback.SetbackPacketSync;
import com.example.argusac.setback.SetbackPhysics;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
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
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 状态回退全能集中门面管理器 (SetbackManager)
 * <p>
 * 采用模块化微内核架构设计，将职责细分解耦至专门子模块：
 * 1. SafeLocationFinder: 负责安全地面与 AABB 碰撞箱 Top-Y 精细贴合探测；
 * 2. SetbackPhysics: 负责原版重力加速度曲线仿真计算与空中下落阻断；
 * 3. SetbackPacketSync: 负责 PacketEvents 协议级网络位置重置报文下发；
 * 4. SetbackNotifier: 负责提示通知、管理广播、安全踢出与防篡改审计日志记录；
 * 5. PlayerSetbackContext: 维护单名玩家的快照历史、锁定锚点与校正时钟状态。
 */
public class SetbackManager implements Listener {

    private final ArgusACPlugin plugin;
    private final SafeLocationFinder safeLocationFinder;
    private final SetbackNotifier setbackNotifier;

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

        // 对标 GrimAC：移动类检测彻底废除物理回退的冷却放行时间，杜绝作弊者利用 350ms 冷却窗口蹭步蠕动！
        // 告警广播与玩家聊天提示的频次限制由 SetbackNotifier 独立控制，物理拦截本身必须当 tick 即时生效
        int requiredVl = check.getSetbackMinVl() > 0 ? check.getSetbackMinVl() : globalMinVl;
        return currentVl >= requiredVl;
    }

    /**
     * 判断某个检测模块和玩家当前是否应该执行即时当帧位移取消 (CANCEL 模式)
     * 严格遵循 min-vl 阈值与世界/权限豁免，杜绝单 tick 抖动引起的误判拉回，并在达标后无冷却持续阻断
     */
    public boolean shouldCancel(Player player, Check check, int currentVl) {
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
        if (player == null || check == null || moveEvent == null) {
            return false;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        Location from = moveEvent.getFrom();
        Location to = moveEvent.getTo();
        if (from == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long previousCancelTime = context.getLastCancellationViolationTime();
        context.incrementConsecutiveCancellationViolations();
        context.setLastSetbackTimestamp(check.getName(), now);

        boolean physicallyGrounded = isGrounded(player, from) || (data != null && data.isOnGround());
        boolean isAirborne = !physicallyGrounded;
        String checkLower = check.getName().toLowerCase();

        Location lockedAnchor = context.getLockedAnchorLocation();
        Location cancelTarget;

        if (lockedAnchor != null && from.getWorld() != null && from.getWorld().equals(lockedAnchor.getWorld())
                && (now - previousCancelTime < 4000L)) {
            cancelTarget = lockedAnchor.clone();
            // 校验历史锚点有效性：若锚点低于脚下方块顶面，立即对齐坚实表面，杜绝历史穿模死锁
            Location anchorGround = findSafeGroundNear(cancelTarget, 0.6);
            if (anchorGround != null && cancelTarget.getY() < anchorGround.getY()) {
                cancelTarget.setY(anchorGround.getY());
            }
        } else {
            if (!isAirborne) {
                // 地面移动或方块边缘：优先吸附对齐台面坚实表面 (Surface Y 与水平安全内侧，杜绝悬崖边缘下坠)
                Location nearGround = findSafeGroundNear(from, 0.6);
                if (nearGround != null && Math.abs(nearGround.getY() - from.getY()) <= 0.35) {
                    cancelTarget = nearGround.clone();
                } else {
                    cancelTarget = from.clone();
                }
            } else {
                // 真实空中悬浮滞空：向下搜索最近落脚点
                Location nearGround = findSafeGroundNear(from, 1.5);
                if (nearGround != null) {
                    cancelTarget = nearGround.clone();
                } else {
                    cancelTarget = from.clone();
                }
            }
            context.setLockedAnchorLocation(cancelTarget);
        }

        if (to != null) {
            cancelTarget.setYaw(to.getYaw());
            cancelTarget.setPitch(to.getPitch());
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        boolean inLiquid = adapter.isInWater(player)
                || adapter.isLiquid(cancelTarget.getBlock())
                || adapter.isLiquid(from.getBlock())
                || adapter.isLiquid(cancelTarget.clone().subtract(0, 0.4, 0).getBlock());

        // 仅在纯真空空中滞空且非流体环境下才模拟原版重力加速度下坠下压；
        // 流体环境 (水、岩浆) 自带浮力，绝不执行真空自由落体下坠，杜绝水面跳跃/回退时被连环下压沉底
        if (inLiquid && to != null && to.getY() < cancelTarget.getY() && (cancelTarget.getY() - to.getY()) <= 0.5) {
            // 在水中发生回退锚定时，允许玩家自然下沉入水，防止将玩家死锁在水面表层 (如 Y=62.94) 引发连环 Jesus 误报
            cancelTarget.setY(to.getY());
            if (context.getLockedAnchorLocation() != null) {
                context.getLockedAnchorLocation().setY(to.getY());
            }
        }
        if (isAirborne && !inLiquid) {
            // 获取脚下最近的稳固地面支撑高度作为物理硬底线 (Ground Floor)
            Location groundBelow = findSafeGroundNear(from, 1.5);
            if (groundBelow == null) {
                groundBelow = findSafeGroundBelow(from);
            }
            double minAllowedY = (groundBelow != null) ? groundBelow.getY() : (from.getWorld() != null ? from.getWorld().getMinHeight() : -64.0);

            if (to != null && to.getY() > cancelTarget.getY()) {
                cancelTarget.setY(Math.min(cancelTarget.getY(), from.getY()));
            }
            int airTicks = (data != null) ? data.getAirTicks() : 1;
            int consec = context.getConsecutiveCancellationViolations();
            double expectedFall = computeVanillaGravityFall(Math.max(airTicks, consec));
            double currentDeltaY = (to != null) ? (to.getY() - from.getY()) : 0.0;
            if (currentDeltaY > expectedFall) {
                double targetY = Math.min(cancelTarget.getY(), from.getY() + expectedFall);
                // 严禁重力加速度将玩家压穿坚实方块表面 (Surface Y)！最低只降至 minAllowedY！
                double newY = Math.max(minAllowedY, targetY);
                cancelTarget.setY(newY);
                if (context.getLockedAnchorLocation() != null) {
                    context.getLockedAnchorLocation().setY(newY);
                }
            }
        }

        if (player.isInsideVehicle() && player.getVehicle() != null) {
            org.bukkit.entity.Entity vehicle = player.getVehicle();
            vehicle.setVelocity(new Vector(0, 0, 0));
            // 严格对标 GrimAC：强制将违规玩家从载具弹出下马，拉回载具，彻底封死载具插值微位移
            player.leaveVehicle();
            if (!isAirborne) {
                vehicle.teleport(cancelTarget);
            }
        }

        moveEvent.setTo(cancelTarget);
        moveEvent.setCancelled(true);

        if (checkLower.contains("elytra") || checkLower.contains("fly") || player.isGliding()) {
            player.setGliding(false);
            if (data != null && data.getElytraData() != null) {
                data.getElytraData().resetGlideState();
            }
        }

        // 生成绝对隔离的负数 Teleport ID 并下发位置同步包 (GrimAC 源码: random.nextInt() | Integer.MIN_VALUE)
        int teleportId = context.generateSecureTeleportId();
        context.markTeleportInFlight(teleportId, cancelTarget);
        sendPositionSyncPacket(player, cancelTarget, teleportId);

        // 速度与运动状态彻底清零，消除作弊客户端本地残留 Motion 再次触发异常位移
        player.setVelocity(new Vector(0, 0, 0));
        if (data != null) {
            data.clearExpectedVelocity();
            data.setSpeedBuffer(0.0);
            data.getTimerTracker().reset();
            if (data.getPhysicsState() != null) {
                data.getPhysicsState().reset(cancelTarget);
                data.getPhysicsState().clearPendingVelocities();
            }
        }

        setbackNotifier.postSetbackSuccess(player, context, check.getName(), currentVl, reason, "DIRECT_CANCEL",
                SafeLocationFinder.formatLoc(from), SafeLocationFinder.formatLoc(cancelTarget), isAirborne,
                LegitimateLocation.from(cancelTarget, null, isGrounded(player, cancelTarget), false, null),
                notifyPlayerChannel, notifyPlayerCooldownMs, notifyAdminEnabled);
        return true;
    }

    /**
     * 默认反作弊惩戒统一执行入口 (Mitigate / Setback)
     */
    public boolean mitigate(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        if (player == null || !player.isOnline() || check == null) {
            return false;
        }

        if (check.getType() != CheckType.MOVEMENT) {
            return false;
        }

        if (moveEvent != null) {
            return directCancel(player, check, currentVl, reason, moveEvent);
        }

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> mitigate(player, check, currentVl, reason, null));
            return true;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());
        Location curLoc = player.getLocation();
        String fromLocStr = SafeLocationFinder.formatLoc(curLoc);
        long now = System.currentTimeMillis();

        UUID curWorldUid = (curLoc.getWorld() != null) ? curLoc.getWorld().getUID() : null;
        LegitimateLocation target = context.findBoundedLegitimateLocation(
                curWorldUid,
                curLoc,
                maxSetbackDistance,
                maxSnapshotAgeMs
        );
        boolean usedFallback = false;

        if (target == null) {
            if (context.getLastGroundLocation() != null && context.getLastGroundLocation().isPassable()) {
                target = context.getLastGroundLocation();
                usedFallback = true;
            } else {
                Location safeGround = findSafeGroundBelow(curLoc);
                if (safeGround != null) {
                    target = LegitimateLocation.from(safeGround, null, true, false, null);
                    usedFallback = true;
                }
            }

            if (target == null) {
                LegitimateLocation relaxedTarget = context.findNearestPassableLocation(curWorldUid, curLoc, maxSetbackDistance * 2.0);
                if (relaxedTarget != null) {
                    target = relaxedTarget;
                    usedFallback = true;
                } else {
                    setbackNotifier.recordFailure(player, context, check.getName(), "UNBOUNDED_OR_OCCLUDED", fromLocStr);
                    if (kickWhenBothBlocked) {
                        setbackNotifier.dispatchKick(player, kickMessage);
                    }
                    return false;
                }
            }
        }

        Location targetLocation = target.toLocation();
        if (targetLocation == null || targetLocation.getWorld() == null) {
            setbackNotifier.recordFailure(player, context, check.getName(), "WORLD_UNAVAILABLE", target.toString());
            return false;
        }

        targetLocation.setYaw(curLoc.getYaw());
        targetLocation.setPitch(curLoc.getPitch());

        String toLocStr = SafeLocationFinder.formatLoc(targetLocation);

        int ping = Math.max(0, com.example.argusac.compat.VersionAdapterFactory.getAdapter().getPing(player));
        long correctionTimeout = Math.min(correctionTimeoutMs, 2L * ping + 150L);
        long seq = context.getNextSequenceId();
        context.setActiveSequenceId(seq);
        context.setPendingCorrection(new PendingCorrection(
                targetLocation.getX(), targetLocation.getY(), targetLocation.getZ(),
                targetLocation.getWorld().getUID(),
                now + correctionTimeout,
                seq
        ));
        context.setLastSetbackTimestamp(check.getName(), now);

        final LegitimateLocation finalTarget = target;
        final boolean finalUsedFallback = usedFallback;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    executeHardSetbackOnMainThread(player, context, targetLocation, finalTarget, check.getName(), currentVl, reason, fromLocStr, toLocStr, finalUsedFallback)
            );
        } else {
            executeHardSetbackOnMainThread(player, context, targetLocation, finalTarget, check.getName(), currentVl, reason, fromLocStr, toLocStr, finalUsedFallback);
        }
        return true;
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
        PlayerData data = (moveEvent != null && moveEvent.getPlayer() != null && plugin.getPlayerManager() != null)
                ? plugin.getPlayerManager().getPlayerData(moveEvent.getPlayer()) : null;
        SetbackPhysics.applySafeAirCancellation(moveEvent, data);
    }

    public Location findSafeGroundNear(Location loc, double maxDistance) {
        return safeLocationFinder.findSafeGroundNear(loc, maxDistance);
    }

    public Location findSafeGroundBelow(Location loc) {
        return safeLocationFinder.findSafeGroundBelow(loc);
    }

    private void executeHardSetbackOnMainThread(Player player, PlayerSetbackContext context,
                                                Location targetLocation, LegitimateLocation target,
                                                String checkName, int currentVl, String reason,
                                                String fromLocStr, String toLocStr, boolean usedFallback) {
        if (!player.isOnline()) {
            setbackNotifier.recordFailure(player, context, checkName, "PLAYER_OFFLINE", toLocStr);
            return;
        }

        try {
            selfTeleportingPlayers.add(player.getUniqueId());

            if (player.isInsideVehicle() && player.getVehicle() != null) {
                org.bukkit.entity.Entity vehicle = player.getVehicle();
                vehicle.setVelocity(new Vector(0, 0, 0));
                // 严格对标 GrimAC：强制将违规玩家从载具弹出下马并单独拉回载具
                player.leaveVehicle();
                vehicle.teleport(targetLocation);
            }

            int teleportId = context.generateSecureTeleportId();
            context.markTeleportInFlight(teleportId, targetLocation);
            sendPositionSyncPacket(player, targetLocation, teleportId);

            boolean teleportSuccess = player.teleport(targetLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);

            if (teleportSuccess) {
                if (target != null && target.isOnGround()) {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                } else {
                    player.setVelocity(new Vector(0, -0.08, 0));
                }

                PlayerData data = plugin.getPlayerManager().getPlayerData(player);
                if (data != null) {
                    data.clearExpectedVelocity();
                    data.setSpeedBuffer(0.0);
                    data.getTimerTracker().reset();
                    if (data.getPhysicsState() != null) {
                        data.getPhysicsState().reset(targetLocation);
                        data.getPhysicsState().clearPendingVelocities();
                    }
                }

                if (target != null) {
                    context.setLastLegitimateLocation(target);
                }

                setbackNotifier.postSetbackSuccess(player, context, checkName, currentVl, reason, "HARD", fromLocStr, toLocStr, usedFallback, target,
                        notifyPlayerChannel, notifyPlayerCooldownMs, notifyAdminEnabled);
            } else {
                context.confirmTeleport(teleportId);
                setbackNotifier.recordFailure(player, context, checkName, "TELEPORT_CANCELLED", toLocStr);
            }

        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Error executing HARD setback for " + player.getName(), ex);
            setbackNotifier.recordFailure(player, context, checkName, "EXCEPTION_" + ex.getMessage(), toLocStr);
        } finally {
            selfTeleportingPlayers.remove(player.getUniqueId());
        }
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
            boolean climbOrSubmerged = adapter.isClimbable(feet) || adapter.isClimbable(head)
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
}
