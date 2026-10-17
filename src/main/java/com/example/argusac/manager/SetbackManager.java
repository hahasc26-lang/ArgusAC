package com.example.argusac.manager;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.api.CheckType;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.model.SetbackFailureLog;
import com.example.argusac.model.SetbackOverride;
import com.example.argusac.model.ViolationLog;
import com.example.argusac.util.ColorUtil;
import com.example.argusac.util.MathUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;

/**
 * 状态回退全能集中管理器 (SetbackManager)
 * <p>
 * 【架构设计与集中化原则】：
 * 1. 严格将回退约束于移动类检测 (CheckType.MOVEMENT)，战斗与方块交互违规通过取消事件处理，不执行传送；
 * 2. 移除 HARD 传送后对玩家步行/飞行速度的强制覆盖重置，保留服务器与技能配置；
 * 3. 维护短窗口已验证移动快照，选择同世界、时效有效且距离有界的目标；超出边界时安全降级取消当前移动；
 * 4. 采用跨版本玩家物理碰撞箱检测目标空间通行性；
 * 5. 将网络校正追踪与检测剥离，收窄自免疫窗口，避免检测盲区；
 * 6. 支持按检测模块独立节流冷却，并在冷却或失败时优先取消当前非法移动。
 */
public class SetbackManager implements Listener {

    private final ArgusACPlugin plugin;

    // ==================== 配置参数 ====================
    private boolean globalEnabled;
    private String defaultMode; // "SOFT" 或 "HARD"
    private long cooldownMs;
    private int globalMinVl;
    private boolean kickWhenBothBlocked;
    private String kickMessage;
    private final Set<String> exemptedWorlds = new HashSet<>();

    // 边界与快照配置
    private double maxSetbackDistance;
    private long maxSnapshotAgeMs;
    private int maxSnapshotCapacity;

    // 网络校正与自免疫参数
    private long correctionTimeoutMs;
    private long minImmunityMs;
    private long immunityExtraMs;

    // 提示通知参数
    private String notifyPlayerChannel; // "ACTIONBAR", "CHAT", "NONE"
    private long notifyPlayerCooldownMs;
    private boolean notifyAdminEnabled;

    // ==================== 集中化状态存储 ====================
    private final Map<UUID, PlayerSetbackContext> contextMap = new ConcurrentHashMap<>();
    private final Set<UUID> selfTeleportingPlayers = ConcurrentHashMap.newKeySet();

    public SetbackManager(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.reloadConfig();
    }

    /**
     * 重载回退配置文件
     */
    public void reloadConfig() {
        FileConfiguration config = plugin.getConfig();

        this.globalEnabled = config.getBoolean("setback.global-enabled", true);
        this.defaultMode = config.getString("setback.default-mode", "HARD").toUpperCase();
        this.cooldownMs = config.getLong("setback.cooldown-ms", 350L);
        this.globalMinVl = config.getInt("setback.default-min-vl", 1);
        this.kickWhenBothBlocked = config.getBoolean("setback.boundary.kick-when-both-blocked", false);
        this.kickMessage = config.getString("setback.boundary.kick-message",
                "&c[ArgusAC] 移动异常且历史位置已被阻挡，已安全断开连接。");

        this.exemptedWorlds.clear();
        for (String w : config.getStringList("setback.exempted-worlds")) {
            this.exemptedWorlds.add(w.toLowerCase());
        }

        this.maxSetbackDistance = config.getDouble("setback.boundary.max-distance", 8.0);
        this.maxSnapshotAgeMs = config.getLong("setback.snapshot.max-age-ms", 2500L);
        this.maxSnapshotCapacity = config.getInt("setback.snapshot.max-capacity", 30);

        this.correctionTimeoutMs = config.getLong("setback.correction.timeout-ms", 600L);
        this.minImmunityMs = config.getLong("setback.immunity.min-duration-ms", 400L);
        this.immunityExtraMs = config.getLong("setback.immunity.extra-buffer-ms", 150L);

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

        // 客户端坐标校正匹配消费
        if (context.hasPendingCorrection() && context.matchesCorrection(to)) {
            context.clearPendingCorrection();
        }

        // 若处于自免疫期，不采样可能残留过渡抖动的坐标
        if (context.isImmune()) {
            return;
        }

        UUID vehicleId = (player.isInsideVehicle() && player.getVehicle() != null)
                ? player.getVehicle().getUniqueId() : null;

        LegitimateLocation verified = LegitimateLocation.from(
                to,
                player.getVelocity(),
                to.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid(),
                player.isInsideVehicle(),
                vehicleId
        );

        if (verified != null) {
            context.update(verified, maxSnapshotCapacity, maxSnapshotAgeMs);
        }
    }

    // ==============================================================================
    // 核心处罚决策与执行入口 (Mitigation API)
    // ==============================================================================

    /**
     * 判断某个检测模块和玩家当前是否应该执行回退 (P0: 仅限移动检测，按检测模块独立冷却)
     */
    public boolean shouldSetback(Player player, Check check, int currentVl) {
        if (player == null || check == null) {
            return false;
        }

        if (plugin.isPlayerExempt(player)) {
            return false;
        }

        // P0 改法：回退机制严格仅针对移动类违规！战斗与方块交互通过取消事件处理，绝不传送
        if (check.getType() != CheckType.MOVEMENT) {
            return false;
        }

        if (player.getWorld() != null && exemptedWorlds.contains(player.getWorld().getName().toLowerCase())) {
            return false;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());

        // 一级裁决：单人覆写
        SetbackOverride pOverride = context.getOverride();
        if (pOverride == SetbackOverride.DISABLED) {
            return false;
        }

        // 二级与三级裁决：单人默认时受全局和模块开关管控
        if (pOverride == SetbackOverride.DEFAULT) {
            if (!globalEnabled || !check.isSetbackEnabled()) {
                return false;
            }
        }

        // P2: 按检测模块独立冷却检查，避免不同检测连续触发时互相压制
        long now = System.currentTimeMillis();
        long lastCheckSetback = context.getLastSetbackTimestamp(check.getName());
        if (now - lastCheckSetback < cooldownMs) {
            return false;
        }

        // 违规等级门槛检查
        int requiredVl = check.getSetbackMinVl() > 0 ? check.getSetbackMinVl() : globalMinVl;
        return currentVl >= requiredVl;
    }

    public boolean shouldSetback(Player player, com.example.argusac.data.PlayerData data, Check check, int currentVl) {
        return shouldSetback(player, check, currentVl);
    }

    /**
     * 查询指定玩家的特定检测模块是否正处于回退冷却中
     */
    public boolean isCheckOnCooldown(UUID uuid, String checkName) {
        PlayerSetbackContext context = contextMap.get(uuid);
        if (context == null) return false;
        return (System.currentTimeMillis() - context.getLastSetbackTimestamp(checkName)) < cooldownMs;
    }

    /**
     * 判断某次移动是否是由服务端回退引发的客户端网络坐标校正
     */
    public boolean isCorrectionMove(Player player, Location from, Location to) {
        if (player == null || to == null) return false;
        PlayerSetbackContext context = contextMap.get(player.getUniqueId());
        if (context == null) return false;
        return context.matchesCorrection(to);
    }

    /**
     * 默认反作弊惩戒统一执行入口 (Mitigate / Setback)
     *
     * @param player 违规玩家
     * @param check 触发的检测模块
     * @param currentVl 违规等级
     * @param reason 违规详情
     * @param moveEvent 若在 PlayerMoveEvent 内调用可传入；可为 null
     * @return 是否成功处置违规
     */
    public boolean mitigate(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        if (player == null || !player.isOnline() || check == null) {
            return false;
        }

        // 严格限制回退仅用于移动违规
        if (check.getType() != CheckType.MOVEMENT) {
            return false;
        }

        // 若非主线程调用且无移动事件，安全调度回主线程执行
        if (!Bukkit.isPrimaryThread() && moveEvent == null) {
            Bukkit.getScheduler().runTask(plugin, () -> mitigate(player, check, currentVl, reason, null));
            return true;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());
        Location curLoc = player.getLocation();
        String fromLocStr = String.format("%.2f,%.2f,%.2f", curLoc.getX(), curLoc.getY(), curLoc.getZ());
        long now = System.currentTimeMillis();

        // P0 改法：InventoryMove 等优先原地取消本次移动，不进行远距离传送
        if ("InventoryMove".equalsIgnoreCase(check.getName()) && moveEvent != null) {
            moveEvent.setTo(moveEvent.getFrom());
            context.setLastSetbackTimestamp(check.getName(), now);
            postSetbackSuccess(player, context, check.getName(), currentVl, reason, "CANCEL_MOVE", fromLocStr, fromLocStr, false);
            return true;
        }

        // P1 改法：在短时间窗口内检索同世界、未超时且距离有界的通行快照
        UUID curWorldUid = (curLoc.getWorld() != null) ? curLoc.getWorld().getUID() : null;
        LegitimateLocation target = context.findBoundedLegitimateLocation(
                curWorldUid,
                curLoc,
                maxSetbackDistance,
                maxSnapshotAgeMs
        );
        boolean usedFallback = false;

        // 若未找到符合有界且可通行的快照：执行安全降级策略
        if (target == null) {
            // 降级 1: 若当前存在移动事件，直接将终点设为起点 from，安全取消本次位移，不盲目跨区大传送
            if (moveEvent != null && moveEvent.getFrom() != null) {
                moveEvent.setTo(moveEvent.getFrom());
                context.setLastSetbackTimestamp(check.getName(), now);
                postSetbackSuccess(player, context, check.getName(), currentVl, reason, "SAFE_FALLBACK_CANCEL", fromLocStr, fromLocStr, true);
                return true;
            }

            // 降级 2: 无移动事件时，在同世界尝试寻找距离最近的可通行快照 (放宽至 2 倍最大距离)
            LegitimateLocation relaxedTarget = context.findNearestPassableLocation(curWorldUid, curLoc, maxSetbackDistance * 2.0);
            if (relaxedTarget != null) {
                target = relaxedTarget;
                usedFallback = true;
            } else {
                recordFailure(player, context, check.getName(), "UNBOUNDED_OR_OCCLUDED", fromLocStr);
                if (kickWhenBothBlocked) {
                    dispatchKick(player, kickMessage);
                }
                return false;
            }
        }

        Location targetLocation = target.toLocation();
        if (targetLocation == null || targetLocation.getWorld() == null) {
            recordFailure(player, context, check.getName(), "WORLD_UNAVAILABLE", target.toString());
            return false;
        }

        // 对齐 Grim 等主流反作弊核心机制：严格保留玩家当前的水平与垂直朝向 (Yaw/Pitch)
        // 彻底杜绝回退时由于恢复历史朝向导致的视角瞬间扭转 (Snap View / 视角扯头眩晕)
        float currentYaw = (moveEvent != null && moveEvent.getTo() != null) ? moveEvent.getTo().getYaw() : curLoc.getYaw();
        float currentPitch = (moveEvent != null && moveEvent.getTo() != null) ? moveEvent.getTo().getPitch() : curLoc.getPitch();
        targetLocation.setYaw(currentYaw);
        targetLocation.setPitch(currentPitch);

        // 确定执行模式
        String effectiveMode = determineEffectiveMode(context.getOverride(), check.getSetbackMode());
        String toLocStr = String.format("%.2f,%.2f,%.2f", targetLocation.getX(), targetLocation.getY(), targetLocation.getZ());

        // 记录预期网络校正目标与时效
        int ping = Math.max(0, com.example.argusac.compat.VersionAdapterFactory.getAdapter().getPing(player));
        long correctionTimeout = Math.min(correctionTimeoutMs, 2L * ping + immunityExtraMs);
        context.setPendingCorrection(new PendingCorrection(
                targetLocation.getX(), targetLocation.getY(), targetLocation.getZ(),
                targetLocation.getWorld().getUID(),
                now + correctionTimeout
        ));
        context.setLastSetbackTimestamp(check.getName(), now);

        // 执行具体模式 (若玩家在载具中，必须走 HARD 模式脱离载具传送)
        final LegitimateLocation finalTarget = target;
        final boolean finalUsedFallback = usedFallback;

        if ("SOFT".equalsIgnoreCase(effectiveMode) && moveEvent != null && !player.isInsideVehicle()) {
            // SOFT 模式：直接在事件内修正终点
            moveEvent.setTo(targetLocation);
            context.setLastLegitimateLocation(finalTarget);
            postSetbackSuccess(player, context, check.getName(), currentVl, reason, "SOFT", fromLocStr, toLocStr, finalUsedFallback);
            return true;
        } else {
            // HARD 模式：主线程执行强制传送与动量清空
            if (!Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        executeHardSetbackOnMainThread(player, context, targetLocation, finalTarget, check.getName(), currentVl, reason, fromLocStr, toLocStr, finalUsedFallback)
                );
            } else {
                executeHardSetbackOnMainThread(player, context, targetLocation, finalTarget, check.getName(), currentVl, reason, fromLocStr, toLocStr, finalUsedFallback);
            }
            return true;
        }
    }

    public boolean execute(Player player, com.example.argusac.data.PlayerData data, Check check,
                           int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    public boolean execute(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    /**
     * HARD 模式在主线程执行传送与动量重置 (P0: 移除速度默认值重置，保留服务器设置)
     */
    private void executeHardSetbackOnMainThread(Player player, PlayerSetbackContext context,
                                                Location targetLocation, LegitimateLocation target,
                                                String checkName, int currentVl, String reason,
                                                String fromLocStr, String toLocStr, boolean usedFallback) {
        if (!player.isOnline()) {
            recordFailure(player, context, checkName, "PLAYER_OFFLINE", toLocStr);
            return;
        }

        try {
            selfTeleportingPlayers.add(player.getUniqueId());

            if (player.isInsideVehicle()) {
                player.leaveVehicle();
            }

            boolean teleportSuccess = player.teleport(targetLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);

            if (teleportSuccess) {
                // P0 改法：清除异常动量与坠落距离，绝不重置 walkSpeed / flySpeed，保留服务器与技能配置
                player.setVelocity(new Vector(0, 0, 0));
                
                // 对齐 Grim 等主流反作弊免摔保护策略：
                // 若目标合法点在地面，安全重置坠落距离；若在空中，保留合理高度，防止作弊者利用回退当免摔刷伤害
                if (target != null && target.isOnGround()) {
                    player.setFallDistance(0.0f);
                }

                // 同步更新最后合法位置为回退落点
                if (target != null) {
                    context.setLastLegitimateLocation(target);
                }

                postSetbackSuccess(player, context, checkName, currentVl, reason, "HARD", fromLocStr, toLocStr, usedFallback);
            } else {
                recordFailure(player, context, checkName, "TELEPORT_CANCELLED", toLocStr);
            }

        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "执行玩家 " + player.getName() + " HARD 回退时异常", ex);
            recordFailure(player, context, checkName, "EXCEPTION_" + ex.getMessage(), toLocStr);
        } finally {
            selfTeleportingPlayers.remove(player.getUniqueId());
        }
    }

    /**
     * 回退成功后的统一后续处理
     */
    private void postSetbackSuccess(Player player, PlayerSetbackContext context, String checkName, int currentVl,
                                    String details, String mode, String fromLocStr, String toLocStr, boolean usedFallback) {
        long now = System.currentTimeMillis();

        // 1. 写入收窄后的自免疫截止时间戳 (P1: 由 1500ms 大幅收窄为与 ping 自适应的短时间窗口)
        int ping = Math.max(0, com.example.argusac.compat.VersionAdapterFactory.getAdapter().getPing(player));
        long immunityDuration = Math.max(minImmunityMs, 2L * ping + immunityExtraMs);
        context.setImmunityUntil(now + immunityDuration);
        context.setLastSetbackTimestamp(now);

        // 2. 玩家端提示 (带独立冷却，走 i18n 多语言)
        sendPlayerNotification(player, context);

        // 3. 管理员端广播 (全面接入 i18n 多语言)
        if (notifyAdminEnabled) {
            com.example.argusac.i18n.I18n.broadcastStaff(
                    "punishment.setback-admin",
                    "player", player.getName(),
                    "check", checkName,
                    "mode", mode,
                    "vl", currentVl,
                    "old_loc", fromLocStr,
                    "new_loc", toLocStr
            );
        }

        // 4. 内部审计日志记录
        ViolationLog log = new ViolationLog(
                player.getName(),
                player.getUniqueId(),
                checkName,
                currentVl,
                details,
                true,
                mode,
                fromLocStr,
                toLocStr,
                usedFallback
        );
        int maxLogs = plugin.getConfig().getInt("settings.logs.max-per-player", 50);
        context.addLog(log, maxLogs);

        com.example.argusac.data.PlayerData pData = plugin.getPlayerManager().getPlayerData(player);
        if (pData != null) {
            pData.addLog(log, maxLogs);
        }

        // 5. 提交统一结构化日志至 AuditLogManager
        com.example.argusac.log.model.CoordPoint fromPt = com.example.argusac.log.model.CoordPoint.fromLocation(player.getLocation(), false);
        LegitimateLocation targetLegit = context.getLastLegitimateLocation();
        com.example.argusac.log.model.CoordPoint toPt = targetLegit != null ? com.example.argusac.log.model.CoordPoint.fromLocation(targetLegit.toLocation(), false) : null;
        com.example.argusac.log.model.LogSeverity severity = (currentVl >= 15)
                ? com.example.argusac.log.model.LogSeverity.HIGH
                : (currentVl >= 5 ? com.example.argusac.log.model.LogSeverity.MEDIUM : com.example.argusac.log.model.LogSeverity.LOW);

        com.example.argusac.log.model.LogEntry entry = new com.example.argusac.log.model.LogEntry.Builder(
                com.example.argusac.log.model.LogType.VIOLATION,
                plugin.getAuditLogManager().getServerId()
        )
                .player(player.getName())
                .uuid(player.getUniqueId())
                .check(checkName)
                .vl(currentVl)
                .severity(severity)
                .reason(details)
                .mode(mode)
                .from(fromPt)
                .to(toPt)
                .fallbackUsed(usedFallback)
                .build();
        plugin.getAuditLogManager().submitLog(entry);
    }

    private void sendPlayerNotification(Player player, PlayerSetbackContext context) {
        if ("NONE".equalsIgnoreCase(notifyPlayerChannel)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - context.getLastNotifyTimestamp() < notifyPlayerCooldownMs) {
            return;
        }
        context.setLastNotifyTimestamp(now);

        // 全面接入 i18n 国际化多语言系统
        if ("CHAT".equalsIgnoreCase(notifyPlayerChannel)) {
            com.example.argusac.i18n.I18n.sendPrefixed(player, "punishment.setback-notify");
        } else {
            com.example.argusac.i18n.I18n.sendActionBar(player, "punishment.setback-notify");
        }
    }

    public void recordFailure(Player player, PlayerSetbackContext context, String checkName, String reason, String origLoc) {
        SetbackFailureLog failLog = new SetbackFailureLog(
                player.getName(),
                player.getUniqueId(),
                checkName,
                reason,
                origLoc
        );
        context.addFailureLog(failLog, 20);

        com.example.argusac.data.PlayerData pData = plugin.getPlayerManager().getPlayerData(player);
        if (pData != null) {
            pData.addFailureLog(failLog, 20);
        }

        if (plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().warning(failLog.toString());
        }

        com.example.argusac.log.model.LogEntry failEntry = new com.example.argusac.log.model.LogEntry.Builder(
                com.example.argusac.log.model.LogType.SETBACK_FAIL,
                plugin.getAuditLogManager().getServerId()
        )
                .player(player.getName())
                .uuid(player.getUniqueId())
                .check(checkName)
                .reason(reason)
                .from(com.example.argusac.log.model.CoordPoint.fromLocation(player.getLocation(), false))
                .build();
        plugin.getAuditLogManager().submitLog(failEntry);
    }

    private void dispatchKick(Player player, String rawMessage) {
        Component kickReason = com.example.argusac.i18n.I18n.component(player, "punishment.kick-reason", "check", "Setback", "code", "OCCLUDED");
        if (kickReason == null) {
            kickReason = ColorUtil.toComponent(rawMessage);
        }
        final Component finalKick = kickReason;
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> player.kick(finalKick));
        } else {
            player.kick(finalKick);
        }
    }

    private String determineEffectiveMode(SetbackOverride pOverride, String checkMode) {
        if (pOverride == SetbackOverride.SOFT_ONLY) {
            return "SOFT";
        }
        if (pOverride == SetbackOverride.HARD_ONLY) {
            return "HARD";
        }
        if (checkMode != null && !checkMode.isEmpty()) {
            return checkMode;
        }
        return defaultMode;
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

    public void cleanup() {
        contextMap.clear();
        selfTeleportingPlayers.clear();
    }

    // ==============================================================================
    // 待同步网络校正数据模型 (PendingCorrection)
    // ==============================================================================

    public static class PendingCorrection {
        private final double targetX;
        private final double targetY;
        private final double targetZ;
        private final UUID worldUid;
        private final long expireTime;

        public PendingCorrection(double targetX, double targetY, double targetZ, UUID worldUid, long expireTime) {
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.worldUid = worldUid;
            this.expireTime = expireTime;
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
            return (dx * dx + dy * dy + dz * dz) <= 0.25; // 距离落点在 0.5 格内视为校正到位
        }
    }

    // ==============================================================================
    // 集中化玩家回退状态模型 (PlayerSetbackContext)
    // ==============================================================================

    public static class PlayerSetbackContext {
        private volatile LegitimateLocation lastLegitimateLocation;
        private volatile LegitimateLocation previousLegitimateLocation;
        private volatile SetbackOverride override = SetbackOverride.DEFAULT;
        private volatile long lastSetbackTimestamp = 0L;
        private volatile long immunityUntil = 0L;
        private volatile long lastNotifyTimestamp = 0L;

        // P1: 短时验证移动快照队列
        private final Deque<LegitimateLocation> snapshotHistory = new ConcurrentLinkedDeque<>();

        // P2: 按检测名称细分的冷却时间戳映射
        private final Map<String, Long> lastSetbackByCheck = new ConcurrentHashMap<>();

        // P1: 待确认网络坐标校正
        private volatile PendingCorrection pendingCorrection = null;

        private final Deque<ViolationLog> logs = new ConcurrentLinkedDeque<>();
        private final Deque<SetbackFailureLog> failureLogs = new ConcurrentLinkedDeque<>();

        public void update(LegitimateLocation location, int maxCapacity, long maxAgeMs) {
            this.previousLegitimateLocation = this.lastLegitimateLocation;
            this.lastLegitimateLocation = location;

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
            }
        }

        /**
         * P1: 从快照队列检索同世界、时效有效、距离有界且可通行的最优合法快照
         * 对齐 Grim 机制：地面稳定态优先策略 (Ground Preference)，优先让玩家安全落地方块表面，避免悬空下滑
         */
        public LegitimateLocation findBoundedLegitimateLocation(UUID worldUid, Location currentLoc, double maxDistance, long maxAgeMs) {
            if (worldUid == null || currentLoc == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            LegitimateLocation firstPassable = null;

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
                if (snap.isPassable()) {
                    // 若处于稳固地面，作为第一优先级立即采纳返回
                    if (snap.isOnGround()) {
                        return snap;
                    }
                    if (firstPassable == null) {
                        firstPassable = snap;
                    }
                }
            }
            return firstPassable;
        }

        /**
         * 安全降级：在同世界且限定距离内查找最近的可通行快照
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
                if (dist <= maxDistance && dist < bestDist && snap.isPassable()) {
                    best = snap;
                    bestDist = dist;
                }
            }
            return best;
        }

        public void reset(Location loc, boolean inVehicle, UUID vehicleUid) {
            LegitimateLocation reset = LegitimateLocation.from(loc, new Vector(0, 0, 0), true, inVehicle, vehicleUid);
            this.lastLegitimateLocation = reset;
            this.previousLegitimateLocation = reset;
            this.snapshotHistory.clear();
            if (reset != null) {
                this.snapshotHistory.addFirst(reset);
            }
            this.pendingCorrection = null;
        }

        public void resetPreviousOnly() {
            this.previousLegitimateLocation = null;
        }

        public void clear() {
            this.lastLegitimateLocation = null;
            this.previousLegitimateLocation = null;
            this.snapshotHistory.clear();
            this.pendingCorrection = null;
        }

        public boolean isImmune() {
            return System.currentTimeMillis() < immunityUntil;
        }

        public LegitimateLocation getLastLegitimateLocation() {
            return lastLegitimateLocation;
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
    }
}
