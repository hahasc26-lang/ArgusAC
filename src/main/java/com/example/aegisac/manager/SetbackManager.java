package com.example.aegisac.manager;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.Check;
import com.example.aegisac.model.LegitimateLocation;
import com.example.aegisac.model.SetbackFailureLog;
import com.example.aegisac.model.SetbackOverride;
import com.example.aegisac.model.ViolationLog;
import com.example.aegisac.util.ColorUtil;
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
 * 本类作为自包含的独立反作弊核心回退子系统 (Self-contained Subsystem & Listener)，
 * 集中管理所有回退数据、生命周期监听、移动合法性采样、边界阻挡裁决、SOFT/HARD执行、自免疫控制、通知告警与落盘日志。
 * 外部模块 (如 PlayerData / PlayerEventListener) 无需分散持有回退状态。
 */
public class SetbackManager implements Listener {

    private final AegisACPlugin plugin;

    // ==================== 配置参数 ====================
    private boolean globalEnabled;
    private String defaultMode; // "SOFT" 或 "HARD"
    private long cooldownMs;
    private int globalMinVl;
    private boolean kickWhenBothBlocked;
    private String kickMessage;
    private final Set<String> exemptedWorlds = new HashSet<>();

    // 回退自免疫参数
    private long minImmunityMs;
    private long immunityExtraMs;

    // 提示通知参数
    private String notifyPlayerChannel; // "ACTIONBAR", "CHAT", "NONE"
    private String notifyPlayerMessage;
    private long notifyPlayerCooldownMs;
    private boolean notifyAdminEnabled;
    private String notifyAdminFormat;

    // ==================== 集中化状态存储 ====================
    // 玩家回退上下文映射 (Key: Player UUID)
    private final Map<UUID, PlayerSetbackContext> contextMap = new ConcurrentHashMap<>();

    // 记录正在执行内部回退传送的玩家 UUID (防止 PlayerTeleportEvent 产生重入误判)
    private final Set<UUID> selfTeleportingPlayers = ConcurrentHashMap.newKeySet();

    public SetbackManager(AegisACPlugin plugin) {
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
                "&c[AegisAC] 移动异常且历史位置已被阻挡，已安全断开连接。");

        this.exemptedWorlds.clear();
        for (String w : config.getStringList("setback.exempted-worlds")) {
            this.exemptedWorlds.add(w.toLowerCase());
        }

        this.minImmunityMs = config.getLong("setback.immunity.min-duration-ms", 1500L);
        this.immunityExtraMs = config.getLong("setback.immunity.extra-buffer-ms", 200L);

        this.notifyPlayerChannel = config.getString("setback.notifications.player.channel", "ACTIONBAR").toUpperCase();
        this.notifyPlayerMessage = config.getString("setback.notifications.player.message", "&c[AegisAC] 移动异常，已回退到上一个合法位置！");
        this.notifyPlayerCooldownMs = config.getLong("setback.notifications.player.cooldown-ms", 1000L);

        this.notifyAdminEnabled = config.getBoolean("setback.notifications.admin.enabled", true);
        this.notifyAdminFormat = config.getString("setback.notifications.admin.format",
                "&6[Setback] &e{player} &7因 &c{check} &7已被回退 &8(&f{old_loc} &8-> &a{new_loc}&8) &7[&b{mode}&7]");
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
     * 仅当移动事件已通过所有检测模块 (HIGHEST 优先级)、未被取消且坐标确实改变时，更新唯一合法位置
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
        // 若当前处于回退自免疫恢复期，不记录跳变过渡坐标
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
        context.update(verified);
    }

    // ==============================================================================
    // 核心处罚决策与执行入口 (Mitigation API)
    // ==============================================================================

    /**
     * 判断某个检测模块和玩家当前是否应该执行回退 (三级开关与冷却门槛裁决)
     */
    public boolean shouldSetback(Player player, Check check, int currentVl) {
        if (player == null || check == null) {
            return false;
        }

        if (plugin.isPlayerExempt(player)) {
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

        // 冷却时间检查 (防高频抖动)
        long now = System.currentTimeMillis();
        if (now - context.getLastSetbackTimestamp() < cooldownMs) {
            return false;
        }

        // 违规等级门槛检查 (默认 1)
        int requiredVl = check.getSetbackMinVl() > 0 ? check.getSetbackMinVl() : globalMinVl;
        return currentVl >= requiredVl;
    }

    /**
     * 兼容重载
     */
    public boolean shouldSetback(Player player, com.example.aegisac.data.PlayerData data, Check check, int currentVl) {
        return shouldSetback(player, check, currentVl);
    }

    /**
     * 默认反作弊惩戒统一执行入口 (Mitigate / Setback)
     *
     * @param player 违规玩家
     * @param check 触发的检测模块
     * @param currentVl 违规等级
     * @param reason 违规详情
     * @param moveEvent 若在 PlayerMoveEvent 内调用可传入，SOFT 模式将直接修改目标位置；可为 null
     * @return 是否成功回退
     */
    public boolean mitigate(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        if (player == null || !player.isOnline() || check == null) {
            return false;
        }

        // 若非主线程调用且无移动事件，安全调度回主线程执行
        if (!Bukkit.isPrimaryThread() && moveEvent == null) {
            Bukkit.getScheduler().runTask(plugin, () -> mitigate(player, check, currentVl, reason, null));
            return true;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());
        LegitimateLocation last = context.getLastLegitimateLocation();
        if (last == null) {
            recordFailure(player, context, check.getName(), "NO_LEGITIMATE_LOCATION", "-");
            return false;
        }

        // ==================== 极简边界处理 ====================
        // 回退目标本身就是玩家最后通过验证的位置。唯一需要处理的边界情况：该位置被外部活塞/方块填塞
        final LegitimateLocation target;
        final boolean usedFallback;

        if (last.isPassable()) {
            target = last;
            usedFallback = false;
        } else {
            LegitimateLocation prev = context.getPreviousLegitimateLocation();
            if (prev != null && prev.isPassable()) {
                target = prev;
                usedFallback = true;
            } else {
                String origLocStr = last.toString();
                recordFailure(player, context, check.getName(), "BOTH_LOCATIONS_OCCLUDED", origLocStr);
                if (kickWhenBothBlocked) {
                    dispatchKick(player, kickMessage);
                }
                return false;
            }
        }

        Location targetLocation = target.toLocation();
        if (targetLocation == null || targetLocation.getWorld() == null) {
            recordFailure(player, context, check.getName(), "WORLD_UNAVAILABLE", last.toString());
            return false;
        }

        // 确定执行模式
        String effectiveMode = determineEffectiveMode(context.getOverride(), check.getSetbackMode());

        Location curLoc = player.getLocation();
        String fromLocStr = String.format("%.2f,%.2f,%.2f", curLoc.getX(), curLoc.getY(), curLoc.getZ());
        String toLocStr = String.format("%.2f,%.2f,%.2f", targetLocation.getX(), targetLocation.getY(), targetLocation.getZ());

        // 执行具体模式 (若玩家在载具中，必须走 HARD 模式脱离载具传送)
        if ("SOFT".equalsIgnoreCase(effectiveMode) && moveEvent != null && !player.isInsideVehicle()) {
            // SOFT 模式：直接在事件内修正终点
            moveEvent.setTo(targetLocation);
            context.setLastLegitimateLocation(target);
            postSetbackSuccess(player, context, check.getName(), currentVl, reason, "SOFT", fromLocStr, toLocStr, usedFallback);
            return true;
        } else {
            // HARD 模式：调度至主线程执行强制传送与动量清空
            if (!Bukkit.isPrimaryThread()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        executeHardSetbackOnMainThread(player, context, targetLocation, target, check.getName(), currentVl, reason, fromLocStr, toLocStr, usedFallback)
                );
            } else {
                executeHardSetbackOnMainThread(player, context, targetLocation, target, check.getName(), currentVl, reason, fromLocStr, toLocStr, usedFallback);
            }
            return true;
        }
    }

    /**
     * 兼容旧接口
     */
    public boolean execute(Player player, com.example.aegisac.data.PlayerData data, Check check,
                           int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    public boolean execute(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    /**
     * HARD 模式在主线程执行传送、动量重置与速度复位
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
                // 彻底清空运动速度向量与坠落距离，并复位行走/飞行速度
                player.setVelocity(new Vector(0, 0, 0));
                player.setFallDistance(0.0f);
                player.setWalkSpeed(0.2f);
                player.setFlySpeed(0.1f);

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

        // 1. 写入回退自免疫截止时间戳
        int ping = Math.max(0, com.example.aegisac.compat.VersionAdapterFactory.getAdapter().getPing(player));
        long immunityDuration = Math.max(minImmunityMs, 4L * ping + immunityExtraMs);
        context.setImmunityUntil(now + immunityDuration);
        context.setLastSetbackTimestamp(now);

        // 2. 玩家端提示 (带独立冷却)
        sendPlayerNotification(player, context);

        // 3. 管理员端广播
        if (notifyAdminEnabled) {
            String adminMsg = notifyAdminFormat
                    .replace("{player}", player.getName())
                    .replace("{check}", checkName)
                    .replace("{mode}", mode)
                    .replace("{vl}", String.valueOf(currentVl))
                    .replace("{old_loc}", fromLocStr)
                    .replace("{new_loc}", toLocStr);
            plugin.getAlertManager().broadcastAdmin(adminMsg);
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

        // 同时给通用 PlayerData 记录一份副本供 /aegis logs 查询
        com.example.aegisac.data.PlayerData pData = plugin.getPlayerManager().getPlayerData(player);
        if (pData != null) {
            pData.addLog(log, maxLogs);
        }

        // 5. 提交统一结构化日志至 AuditLogManager (异步零I/O、哈希链落盘)
        com.example.aegisac.log.model.CoordPoint fromPt = com.example.aegisac.log.model.CoordPoint.fromLocation(player.getLocation(), false);
        LegitimateLocation targetLegit = context.getLastLegitimateLocation();
        com.example.aegisac.log.model.CoordPoint toPt = targetLegit != null ? com.example.aegisac.log.model.CoordPoint.fromLocation(targetLegit.toLocation(), false) : null;
        com.example.aegisac.log.model.LogSeverity severity = (currentVl >= 15)
                ? com.example.aegisac.log.model.LogSeverity.HIGH
                : (currentVl >= 5 ? com.example.aegisac.log.model.LogSeverity.MEDIUM : com.example.aegisac.log.model.LogSeverity.LOW);

        com.example.aegisac.log.model.LogEntry entry = new com.example.aegisac.log.model.LogEntry.Builder(
                com.example.aegisac.log.model.LogType.VIOLATION,
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

        if ("CHAT".equalsIgnoreCase(notifyPlayerChannel)) {
            com.example.aegisac.compat.VersionAdapterFactory.getAdapter().sendMessage(player, notifyPlayerMessage);
        } else {
            com.example.aegisac.compat.VersionAdapterFactory.getAdapter().sendActionBar(player, notifyPlayerMessage);
        }
    }

    private void recordFailure(Player player, PlayerSetbackContext context, String checkName, String reason, String origLoc) {
        SetbackFailureLog failLog = new SetbackFailureLog(
                player.getName(),
                player.getUniqueId(),
                checkName,
                reason,
                origLoc
        );
        context.addFailureLog(failLog, 20);

        com.example.aegisac.data.PlayerData pData = plugin.getPlayerManager().getPlayerData(player);
        if (pData != null) {
            pData.addFailureLog(failLog, 20);
        }

        if (plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().warning(failLog.toString());
        }

        // 统一构建 SETBACK_FAIL 日志并提交
        com.example.aegisac.log.model.LogEntry failEntry = new com.example.aegisac.log.model.LogEntry.Builder(
                com.example.aegisac.log.model.LogType.SETBACK_FAIL,
                plugin.getAuditLogManager().getServerId()
        )
                .player(player.getName())
                .uuid(player.getUniqueId())
                .check(checkName)
                .reason(reason)
                .from(com.example.aegisac.log.model.CoordPoint.fromLocation(player.getLocation(), false))
                .build();
        plugin.getAuditLogManager().submitLog(failEntry);
    }

    private void dispatchKick(Player player, String rawMessage) {
        Component kickReason = ColorUtil.toComponent(rawMessage);
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> player.kick(kickReason));
        } else {
            player.kick(kickReason);
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

    /**
     * 插件卸载或热重载时安全清理所有缓存
     */
    public void cleanup() {
        contextMap.clear();
        selfTeleportingPlayers.clear();
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

        private final Deque<ViolationLog> logs = new ConcurrentLinkedDeque<>();
        private final Deque<SetbackFailureLog> failureLogs = new ConcurrentLinkedDeque<>();

        public void update(LegitimateLocation location) {
            this.previousLegitimateLocation = this.lastLegitimateLocation;
            this.lastLegitimateLocation = location;
        }

        public void setLastLegitimateLocation(LegitimateLocation location) {
            this.lastLegitimateLocation = location;
        }

        public void reset(Location loc, boolean inVehicle, UUID vehicleUid) {
            LegitimateLocation reset = LegitimateLocation.from(loc, new Vector(0, 0, 0), true, inVehicle, vehicleUid);
            this.lastLegitimateLocation = reset;
            this.previousLegitimateLocation = reset;
        }

        public void resetPreviousOnly() {
            this.previousLegitimateLocation = null;
        }

        public void clear() {
            this.lastLegitimateLocation = null;
            this.previousLegitimateLocation = null;
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
