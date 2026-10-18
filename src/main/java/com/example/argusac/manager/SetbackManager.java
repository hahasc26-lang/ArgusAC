package com.example.argusac.manager;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.api.CheckType;
import com.example.argusac.log.model.SetbackFailureLog;
import com.example.argusac.log.model.ViolationLog;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.model.SetbackOverride;
import com.example.argusac.util.ColorUtil;
import com.example.argusac.util.MathUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitTask;
import com.example.argusac.compat.CompatBoundingBox;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapterFactory;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
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

        // Consume only a client correction move whose origin and destination both match
        // the setback target. A violation event rewritten to that target must not count
        // as the client's acknowledgement.
        if (context.hasPendingCorrection()
                && context.matchesCorrection(from)
                && context.matchesCorrection(to)) {
            context.clearPendingCorrection();
            context.setImmunityUntil(0L);
        }

        // 若处于自免疫期，不采样可能残留过渡抖动的坐标
        if (context.isImmune()) {
            return;
        }

        UUID vehicleId = (player.isInsideVehicle() && player.getVehicle() != null)
                ? player.getVehicle().getUniqueId() : null;

        boolean onGround = to.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid();
        com.example.argusac.data.PlayerData pData = plugin.getPlayerManager().getPlayerData(player);
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
        if (player == null || from == null || to == null) return false;
        PlayerSetbackContext context = contextMap.get(player.getUniqueId());
        if (context == null) return false;
        return context.matchesCorrection(from) && context.matchesCorrection(to);
    }

    private String formatLoc(Location loc) {
        if (loc == null) return "null";
        return String.format("%.2f,%.2f,%.2f", loc.getX(), loc.getY(), loc.getZ());
    }

    /**
     * 对齐 GrimAC 核心机制：零延迟即时直接取消 (Direct In-Event Cancellation)
     * 一旦检测模块判定非法移动，当帧立即在事件内直接取消/重写移动终点，杜绝任何位移偷跑与空中滞空
     *
     * @param player 违规玩家
     * @param check 触发检测项
     * @param currentVl 当前违规值
     * @param reason 违规详情
     * @param moveEvent 移动事件
     * @return 是否成功取消
     */
    /**
     * 底层数据包协议级即时发包同步 (向客户端强制推送当前位置重置报文，打断客户端本地物理预测)
     */
    public void sendPositionSyncPacket(Player player, Location target) {
        if (player == null || !player.isOnline() || target == null || target.getWorld() == null) {
            return;
        }

        boolean packetSent = false;
        try {
            if (PacketEvents.getAPI() != null && PacketEvents.getAPI().getPlayerManager() != null) {
                User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                if (user != null) {
                    try {
                        // PacketEvents 2.x 标准构造: (x, y, z, yaw, pitch, relativeFlags, teleportId)
                        WrapperPlayServerPlayerPositionAndLook packet =
                                new WrapperPlayServerPlayerPositionAndLook(
                                        target.getX(), target.getY(), target.getZ(),
                                        target.getYaw(), target.getPitch(),
                                        (byte) 0, 0
                                );
                        user.sendPacket(packet);
                        packetSent = true;
                    } catch (Throwable t) {
                        // 备用兼容构造器: (x, y, z, yaw, pitch, relativeFlags, teleportId, dismountVehicle)
                        try {
                            java.lang.reflect.Constructor<?> ctor = WrapperPlayServerPlayerPositionAndLook.class.getConstructor(
                                    double.class, double.class, double.class, float.class, float.class, byte.class, int.class, boolean.class
                            );
                            Object packet = ctor.newInstance(
                                    target.getX(), target.getY(), target.getZ(),
                                    target.getYaw(), target.getPitch(),
                                    (byte) 0, 0, false
                            );
                            user.sendPacket((com.github.retrooper.packetevents.wrapper.PacketWrapper<?>) packet);
                            packetSent = true;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (!packetSent) {
            // 底层包发送未命中时的服务端硬重置备选保证：通过自传送向客户端推送官方同步报文
            try {
                selfTeleportingPlayers.add(player.getUniqueId());
                player.teleport(target, PlayerTeleportEvent.TeleportCause.UNKNOWN);
            } catch (Throwable ignored) {
            } finally {
                selfTeleportingPlayers.remove(player.getUniqueId());
            }
        }
    }

    /**
     * 零延迟当帧直接取消 (Direct Cancellation) 核心机制
     * <p>
     * 【强化双重即时阻断与持续原位锚定】：
     * 1. 当帧 Bukkit 移动事件双保险：同时设置 setTo(target) 与 setCancelled(true)，彻底截断服务端物理管线；
     * 2. 底层数据包协议级发包即时同步：向玩家网络通道立即写入客户端位置重置包，强行打断客户端预测与发包冲卡；
     * 3. 持续当帧强行锚定原位 (Locked Anchor)：在短时间内多次尝试冲破阻断的玩家，持续将其锁定在初次违规锚点，杜绝前进蠕动 (Inchworming)；
     * 4. 空中飞行外挂严格向下施加自然重力衰减，绝不在空中形成悬停平台；
     * 5. 彻底清除自免疫期与冷却限制，当帧 0ms 绝对物理阻断。
     *
     * @param player 违规玩家
     * @param check 触发检测项
     * @param currentVl 当前违规值
     * @param reason 违规详情
     * @param moveEvent 移动事件
     * @return 是否成功取消
     */
    public boolean directCancel(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        if (player == null || check == null || moveEvent == null) {
            return false;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());
        com.example.argusac.data.PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        Location from = moveEvent.getFrom();
        Location to = moveEvent.getTo();
        if (from == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        context.incrementConsecutiveCancellationViolations();
        context.setLastSetbackTimestamp(check.getName(), now);

        // 判断是否属于空中/滞空异常位移 (Fly, Hover, Jesus 等)
        boolean isAirborne = (data != null && !data.isOnGround()) || !isGrounded(player, from);
        String checkLower = check.getName().toLowerCase();
        boolean isFlightOrWaterCheck = checkLower.contains("fly")
                || checkLower.contains("hover")
                || checkLower.contains("jesus");

        Location lockedAnchor = context.getLockedAnchorLocation();
        Location cancelTarget;

        // 【持续当帧强行原位锚定】：若在近 4 秒内已存在锁定锚点且在同世界，绝对锁定回该锚点！杜绝利用连续发包钻空子微小向前位移
        if (lockedAnchor != null && from.getWorld() != null && from.getWorld().equals(lockedAnchor.getWorld())
                && (now - context.getLastCancellationViolationTime() < 4000L)) {
            cancelTarget = lockedAnchor.clone();
        } else {
            // 初次违规：确定初始锚定坐标
            if (isAirborne || isFlightOrWaterCheck) {
                Location nearGround = findSafeGroundNear(from, 1.5);
                if (nearGround != null) {
                    cancelTarget = nearGround.clone();
                } else {
                    cancelTarget = from.clone();
                }
            } else {
                cancelTarget = from.clone();
            }
            context.setLockedAnchorLocation(cancelTarget);
        }

        // 严格保留玩家当前朝向 (Yaw/Pitch)，杜绝视角扭转眩晕
        if (to != null) {
            cancelTarget.setYaw(to.getYaw());
            cancelTarget.setPitch(to.getPitch());
        }

        // 空中强行飞行与滞空物理约束：
        if (isAirborne || isFlightOrWaterCheck) {
            // 水平 X/Z 绝对死锁在锁定锚点的原位坐标，不允许任何前进蠕动！
            // 垂直 Y 轴：
            // 1. 绝对压制向上拔高位移：
            if (to != null && to.getY() > cancelTarget.getY()) {
                cancelTarget.setY(Math.min(cancelTarget.getY(), from.getY()));
            }
            // 2. 针对强行飞行/悬空外挂，施加自然重力下坠衰减，坚决拽向地面，杜绝空中悬停平台
            int airTicks = (data != null) ? data.getAirTicks() : 1;
            int consec = context.getConsecutiveCancellationViolations();
            double expectedFall = computeVanillaGravityFall(Math.max(airTicks, consec));
            double currentDeltaY = (to != null) ? (to.getY() - from.getY()) : 0.0;
            if (currentDeltaY > expectedFall) {
                double newY = Math.min(cancelTarget.getY(), from.getY() + expectedFall);
                cancelTarget.setY(newY);
                // 同步更新锁定锚点的 Y 轴使之下沉，但 X/Z 依旧稳固锁在最初原位！
                if (context.getLockedAnchorLocation() != null) {
                    context.getLockedAnchorLocation().setY(newY);
                }
            }
        }

        // 若玩家坐在载具中 (船、矿车、坐骑等)：同步修正载具实体速度与位移，保持乘骑连续性
        if (player.isInsideVehicle() && player.getVehicle() != null) {
            org.bukkit.entity.Entity vehicle = player.getVehicle();
            vehicle.setVelocity(new Vector(0, 0, 0));
            if (!isAirborne) {
                vehicle.teleport(cancelTarget);
            }
        }

        // 【双重即时阻断 - 第一重】：当帧 Bukkit 移动事件双保险截断
        moveEvent.setTo(cancelTarget);
        moveEvent.setCancelled(true);

        // 【双重即时阻断 - 第二重】：底层数据包协议级即时发包同步
        sendPositionSyncPacket(player, cancelTarget);

        // 物理状态重置，防止下一帧因残差继承产生误报
        if (data != null && data.getPhysicsState() != null) {
            data.getPhysicsState().reset(cancelTarget);
        }

        postSetbackSuccess(player, context, check.getName(), currentVl, reason, "DIRECT_CANCEL",
                formatLoc(from), formatLoc(cancelTarget), isAirborne,
                LegitimateLocation.from(cancelTarget, null, isGrounded(player, cancelTarget), false, null));
        return true;
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

        // 核心架构升级对齐 GrimAC：凡存在移动事件上下文的移动作弊，当帧直接就地执行零延迟直接取消 (Direct Cancellation)！
        if (moveEvent != null) {
            return directCancel(player, check, currentVl, reason, moveEvent);
        }

        // 若非主线程调用且无移动事件，安全调度回主线程执行
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> mitigate(player, check, currentVl, reason, null));
            return true;
        }

        PlayerSetbackContext context = getContext(player.getUniqueId());
        Location curLoc = player.getLocation();
        String fromLocStr = formatLoc(curLoc);
        long now = System.currentTimeMillis();

        // P1 改法：在短时间窗口内检索同世界、未超时且距离有界的稳固地面快照
        UUID curWorldUid = (curLoc.getWorld() != null) ? curLoc.getWorld().getUID() : null;
        LegitimateLocation target = context.findBoundedLegitimateLocation(
                curWorldUid,
                curLoc,
                maxSetbackDistance,
                maxSnapshotAgeMs
        );
        boolean usedFallback = false;

        // 若未找到符合有界且可通行的地面快照：执行安全降级策略 (严格地面优先与防悬浮法则)
        if (target == null) {
            // 【P0 修复空中悬浮漏洞】：优先使用历史稳固地面位置 (同世界即可拉回，彻底杜绝空中停滞)
            if (context.getLastGroundLocation() != null && context.getLastGroundLocation().isPassable()) {
                target = context.getLastGroundLocation();
                usedFallback = true;
            } else {
                // 尝试向下垂直探测寻找安全地面落脚点
                Location safeGround = findSafeGroundBelow(curLoc);
                if (safeGround != null) {
                    target = LegitimateLocation.from(safeGround, null, true, false, null);
                    usedFallback = true;
                }
            }

            if (target == null) {
                // 无移动事件时，在同世界尝试寻找距离最近的可通行快照 (放宽至 2 倍最大距离)
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
        }

        Location targetLocation = target.toLocation();
        if (targetLocation == null || targetLocation.getWorld() == null) {
            recordFailure(player, context, check.getName(), "WORLD_UNAVAILABLE", target.toString());
            return false;
        }

        // 严格保留玩家当前的水平与垂直朝向 (Yaw/Pitch)
        targetLocation.setYaw(curLoc.getYaw());
        targetLocation.setPitch(curLoc.getPitch());

        String toLocStr = formatLoc(targetLocation);

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

        // 无事件上下文的降级兜底：主线程执行安全传送与动量清空
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    executeHardSetbackOnMainThread(player, context, targetLocation, finalTarget, check.getName(), currentVl, reason, fromLocStr, toLocStr, finalUsedFallback)
            );
        } else {
            executeHardSetbackOnMainThread(player, context, targetLocation, finalTarget, check.getName(), currentVl, reason, fromLocStr, toLocStr, finalUsedFallback);
        }
        return true;
    }

    public boolean execute(Player player, com.example.argusac.data.PlayerData data, Check check,
                           int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    public boolean execute(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        return mitigate(player, check, currentVl, reason, moveEvent);
    }

    /**
     * 判断指定玩家与位置是否处于有效支持面或地面上
     */
    public boolean isGrounded(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        com.example.argusac.data.PlayerData pData = plugin.getPlayerManager().getPlayerData(player);
        if (pData != null && pData.isOnGround()) return true;
        Block blockBelow = loc.clone().subtract(0, 0.1, 0).getBlock();
        return blockBelow.getType().isSolid();
    }

    /**
     * 拟合 Minecraft 原版自由落体加速度曲线：
     * v_Y = (v_{Y,prev} - 0.08) * 0.98，最大终端速度 -3.92
     */
    public static double computeVanillaGravityFall(int airTicks) {
        if (airTicks <= 0) return -0.08;
        double v = 0.0;
        int ticks = Math.min(60, airTicks);
        for (int i = 0; i < ticks; i++) {
            v = (v - 0.08) * 0.98;
            if (v < -3.92) {
                v = -3.92;
                break;
            }
        }
        return Math.max(-3.92, v);
    }

    /**
     * 对空中移动进行安全拦截与重力保全：
     * 1. 严格锁定 X、Z 水平位移至起点 from，阻止非法平移飞行；
     * 2. 垂直 Y 轴严禁锁定为恒定值 (杜绝空中悬停)，并防范微量下落慢速悬浮 (Slow-Fall Bypass)；
     *    根据玩家滞空时间动态计算原版自由落体期望加速度 (Vanilla Fall Curve)，
     *    若试图反重力上升、静止悬停或慢飘伪装，强制施加自然重力加速度衰减！
     */
    public void applySafeAirCancellation(PlayerMoveEvent moveEvent) {
        if (moveEvent == null || moveEvent.getFrom() == null) return;
        Location from = moveEvent.getFrom();
        Location to = moveEvent.getTo();
        if (to == null) {
            moveEvent.setTo(from.clone());
            return;
        }

        Location safeTo = from.clone();
        safeTo.setYaw(to.getYaw());
        safeTo.setPitch(to.getPitch());

        Player player = moveEvent.getPlayer();
        boolean slowFalling = (player != null) && VersionAdapterFactory.getAdapter().hasPotionEffect(player, CompatPotionType.SLOW_FALLING);

        double deltaY = to.getY() - from.getY();
        if (slowFalling) {
            // 拥有缓降药水：允许慢速滑翔
            if (deltaY < 0) {
                safeTo.setY(to.getY());
            } else {
                safeTo.setY(from.getY() - 0.02);
            }
        } else {
            // 无缓降药水：根据已滞空 airTicks 计算当 tick 应当承受的原版自由落体加速度
            com.example.argusac.data.PlayerData data = (player != null && plugin.getPlayerManager() != null)
                    ? plugin.getPlayerManager().getPlayerData(player) : null;
            int airTicks = (data != null) ? data.getAirTicks() : 1;
            double expectedFall = computeVanillaGravityFall(airTicks);

            // 若原本自然下落且下落位移符合或超出重力加速度 (deltaY <= expectedFall)，保留其自然下落
            // 若试图反重力上升、静止悬停或微量下坠伪装悬浮 (deltaY > expectedFall)，强制以原版重力加速度下坠！
            if (deltaY <= expectedFall) {
                safeTo.setY(to.getY());
            } else {
                safeTo.setY(from.getY() + expectedFall);
            }
        }
        moveEvent.setTo(safeTo);
    }

    /**
     * 在限定垂直距离内向下探测坚实地面 (支持半方块/台阶/雪层等精细碰撞箱 Top-Y 贴合，杜绝二次下落抖动)
     */
    public Location findSafeGroundNear(Location loc, double maxDistance) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        int startY = loc.getBlockY();
        int lowestY = Math.max(world.getMinHeight(), (int) Math.floor(loc.getY() - maxDistance));
        com.example.argusac.compat.VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        for (int y = startY; y >= lowestY; y--) {
            Block block = world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType().isSolid() || adapter.isStandable(block)) {
                CompatBoundingBox blockBox = adapter.getBlockBoundingBox(block);
                double surfaceY = (blockBox != null && blockBox.maxY() >= y) ? blockBox.maxY() : (y + 1.0);
                if (loc.getY() >= surfaceY - 0.15 && (loc.getY() - surfaceY) <= maxDistance) {
                    Location candidate = new Location(world, loc.getX(), surfaceY, loc.getZ(), loc.getYaw(), loc.getPitch());
                    CompatBoundingBox playerBox = new CompatBoundingBox(
                            candidate.getX() - 0.29, candidate.getY() + 0.01, candidate.getZ() - 0.29,
                            candidate.getX() + 0.29, candidate.getY() + 1.79, candidate.getZ() + 0.29
                    );
                    if (adapter.isSpacePassable(world, playerBox)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 向下探测坚实地面方块 (支持台阶、楼梯与半砖精细表面判定，搜索深度扩大至 64 格)
     */
    public Location findSafeGroundBelow(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        int startY = loc.getBlockY();
        int minHeight = world.getMinHeight();
        int lowestY = Math.max(minHeight, startY - 64);
        com.example.argusac.compat.VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        for (int y = startY; y >= lowestY; y--) {
            Block block = world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType().isSolid() || adapter.isStandable(block)) {
                CompatBoundingBox blockBox = adapter.getBlockBoundingBox(block);
                double surfaceY = (blockBox != null && blockBox.maxY() >= y) ? blockBox.maxY() : (y + 1.0);
                Location candidate = new Location(world, loc.getX(), surfaceY, loc.getZ(), loc.getYaw(), loc.getPitch());
                CompatBoundingBox playerBox = new CompatBoundingBox(
                        candidate.getX() - 0.29, candidate.getY() + 0.01, candidate.getZ() - 0.29,
                        candidate.getX() + 0.29, candidate.getY() + 1.79, candidate.getZ() + 0.29
                );
                if (adapter.isSpacePassable(world, playerBox)) {
                    return candidate;
                }
            }
        }
        return null;
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

            if (player.isInsideVehicle() && player.getVehicle() != null) {
                org.bukkit.entity.Entity vehicle = player.getVehicle();
                vehicle.setVelocity(new Vector(0, 0, 0));
                boolean vehicleSuccess = vehicle.teleport(targetLocation);
                if (!vehicleSuccess) {
                    player.leaveVehicle();
                }
            }

            boolean teleportSuccess = player.teleport(targetLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);

            if (teleportSuccess) {
                // P0 改法：清除异常动量与坠落距离，绝不重置 walkSpeed / flySpeed，保留服务器与技能配置
                // 对齐 Grim 等主流反作弊免摔保护与防浮空策略：
                // 若目标合法点在地面，安全清零速度与坠落距离；
                // 若在空中回退，施加自然下落初速度，严禁将其完全静止定在空中！
                if (target != null && target.isOnGround()) {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                } else {
                    player.setVelocity(new Vector(0, -0.08, 0));
                }

                // 同步更新最后合法位置为回退落点
                if (target != null) {
                    context.setLastLegitimateLocation(target);
                }

                postSetbackSuccess(player, context, checkName, currentVl, reason, "HARD", fromLocStr, toLocStr, usedFallback, target);
            } else {
                recordFailure(player, context, checkName, "TELEPORT_CANCELLED", toLocStr);
            }

        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Error executing HARD setback for " + player.getName(), ex);
            recordFailure(player, context, checkName, "EXCEPTION_" + ex.getMessage(), toLocStr);
        } finally {
            selfTeleportingPlayers.remove(player.getUniqueId());
        }
    }

    /**
     * 回退成功后的统一后续处理
     */

    private void postSetbackSuccess(Player player, PlayerSetbackContext context, String checkName, int currentVl,
                                    String details, String mode, String fromLocStr, String toLocStr, boolean usedFallback,
                                    LegitimateLocation target) {
        long now = System.currentTimeMillis();

        // 1. 对齐 GrimAC：直接取消机制彻底清除自免疫期，作弊移动被阻断后不给任何保护时间
        context.setImmunityUntil(0L);
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
            // 【P0 修复空中悬浮漏洞】：仅当回退目标确处于地面时才重置滞空时间！
            // 若回退至半空，必须保留空中滞空计数，绝不能让作弊者通过触发回退洗白 airTicks
            if (target != null && target.isOnGround()) {
                pData.setAirTicks(0);
                pData.setOnGround(true);
            } else {
                pData.setOnGround(false);
            }
            pData.resetSpeedTracking();
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


    public void cleanup() {
        stopActiveGravityTask();
        contextMap.clear();
        selfTeleportingPlayers.clear();
    }

    public void startActiveGravityTask() {
        if (activeGravityTask != null) {
            activeGravityTask.cancel();
        }
        // 每 2 tick (100ms) 主线程巡检所有玩家，主动压制空中滞空悬停
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
     * 解决作弊端挂起移动发包 (Blink/Suspend/Hover/Freecam) 导致 PlayerMoveEvent 不触发从而悬浮在空中的终极根源。
     * 每 2 tick 主动巡检所有在线玩家，若处于空中无支撑面且没有原版重力下落位移，服务端主动介入强制压制下坠！
     */
    private void tickActiveGravityEnforcement() {
        if (!globalEnabled) return;
        com.example.argusac.compat.VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) continue;
            if (plugin.isPlayerExempt(player)) continue;

            // 豁免创造/旁观、允许飞行、坐在载具中
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                    || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) {
                continue;
            }

            com.example.argusac.data.PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data == null || data.isInGracePeriod()) continue;

            // 鞘翅滑翔、飘浮、缓降药水豁免
            if (adapter.isGliding(player)
                    || adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)
                    || adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)) {
                continue;
            }

            // 具有合法击退冲量豁免
            if (data.hasAnyLegalVelocity() || data.hasRecentVelocity(800L)) {
                continue;
            }

            Location curLoc = player.getLocation();
            if (curLoc == null || curLoc.getWorld() == null) continue;
            if (exemptedWorlds.contains(curLoc.getWorld().getName().toLowerCase())) continue;

            // 检查脚下与周围是否处于物理支撑面或攀爬物/深水中
            boolean grounded = isGrounded(player, curLoc);
            Block feet = curLoc.getBlock();
            Block head = curLoc.clone().add(0, 1.0, 0).getBlock();
            boolean climbOrSubmerged = adapter.isClimbable(feet) || adapter.isClimbable(head)
                    || adapter.isSwimming(player)
                    || (adapter.isLiquid(feet) && adapter.isLiquid(head));

            PlayerSetbackContext context = getContext(player.getUniqueId());

            if (grounded || climbOrSubmerged) {
                // 在地面或攀爬物/水中，重置悬空跟踪
                context.resetActiveHoverTracking();
                continue;
            }

            // 此时确定玩家处于空中无支撑状态！
            ActiveHoverTracker tracker = context.getHoverTracker();
            tracker.tick(curLoc, now);

            // 如果连续滞空超过正常跳跃总时长 (例如 > 12 ticks / 600ms) 且没有有效自然下落
            if (tracker.isIllegalHover(12, 600L)) {
                // 主动强制介入：向下压制重力！
                selfTeleportingPlayers.add(player.getUniqueId());
                try {
                    Location target = curLoc.clone();
                    // 强制压低 Y 轴，根据滞空时间施加 2 ticks 原版自然重力下坠衰减
                    double fallOffset = Math.min(-0.16, computeVanillaGravityFall(tracker.getAirTicks()) * 1.5);
                    target.setY(curLoc.getY() + fallOffset);
                    player.teleport(target, PlayerTeleportEvent.TeleportCause.UNKNOWN);

                    if (data.getPhysicsState() != null) {
                        data.getPhysicsState().reset(target);
                    }

                    // 触发违规记录
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

    /**
     * End setback immunity when the player moves somewhere other than the pending correction target.
     */
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


    // ==============================================================================
    // 待同步网络校正数据模型 (PendingCorrection)
    // ==============================================================================

    public static class PendingCorrection {
        private final double targetX;
        private final double targetY;
        private final double targetZ;
        private final UUID worldUid;
        private final long expireTime;
        private final long sequenceId;

        public PendingCorrection(double targetX, double targetY, double targetZ, UUID worldUid, long expireTime, long sequenceId) {
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.worldUid = worldUid;
            this.expireTime = expireTime;
            this.sequenceId = sequenceId;
        }

        public long getSequenceId() {
            return sequenceId;
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
            return (dx * dx + dy * dy + dz * dz) <= 0.000025; // 5 mm tolerance for protocol coordinate rounding
        }
    }

    // ==============================================================================
    // 集中化玩家回退状态模型 (PlayerSetbackContext)
    // ==============================================================================

    public static class PlayerSetbackContext {
        private volatile LegitimateLocation lastLegitimateLocation;
        private volatile LegitimateLocation previousLegitimateLocation;
        private volatile LegitimateLocation lastGroundLocation;
        private volatile SetbackOverride override = SetbackOverride.DEFAULT;
        private volatile long lastSetbackTimestamp = 0L;
        private volatile long immunityUntil = 0L;
        private volatile long lastNotifyTimestamp = 0L;

        // 客户端传送序列确认追踪 (防高 Ping 连续回退风暴)
        private final java.util.concurrent.atomic.AtomicLong sequenceCounter = new java.util.concurrent.atomic.AtomicLong(0);
        private volatile long activeSequenceId = 0L;

        public long getNextSequenceId() {
            return sequenceCounter.incrementAndGet();
        }

        public long getActiveSequenceId() {
            return activeSequenceId;
        }

        public void setActiveSequenceId(long id) {
            this.activeSequenceId = id;
        }

        // P1: 短时验证移动快照队列
        private final Deque<LegitimateLocation> snapshotHistory = new ConcurrentLinkedDeque<>();

        // P2: 按检测名称细分的冷却时间戳映射
        private final Map<String, Long> lastSetbackByCheck = new ConcurrentHashMap<>();

        // P1: 待确认网络坐标校正
        private volatile PendingCorrection pendingCorrection = null;

        // 持续当帧强行原位锁定锚点状态追踪 (Locked Anchor State)
        private volatile Location lockedAnchorLocation = null;
        private volatile long lockedAnchorTimestamp = 0L;
        private volatile int consecutiveCancellationViolations = 0;
        private volatile long lastCancellationViolationTime = 0L;

        private final Deque<ViolationLog> logs = new ConcurrentLinkedDeque<>();
        private final Deque<SetbackFailureLog> failureLogs = new ConcurrentLinkedDeque<>();
        private final ActiveHoverTracker hoverTracker = new ActiveHoverTracker();

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
         * P1: 从快照队列检索同世界、时效有效、距离有界且处于稳固地面的最优合法快照
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

            // 2. 【P0 根治空中悬浮】：若近期快照无地面记录（例如长时间滞空/飞天），
            // 只要同世界且可通行，强制拉回历史最后合法的地面位置 (LastGroundLocation)，彻底剥夺空中悬停空间
            if (lastGroundLocation != null && lastGroundLocation.getWorldUid() != null
                    && lastGroundLocation.getWorldUid().equals(worldUid)
                    && lastGroundLocation.isPassable()) {
                return lastGroundLocation;
            }

            // 3. 队列与历史记录皆无地面快照时，返回 null，交由外层执行垂直向下探测安全地面 (findSafeGroundBelow)
            // 绝不返回空中快照！
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

    /**
     * 服务端主动重力监管状态追踪器：
     * 解决作弊端挂起移动发包 (Blink/Suspend/Hover/Freecam) 导致 PlayerMoveEvent 不触发从而悬浮在空中的终极根源。
     */
    public static class ActiveHoverTracker {
        private int airborneTicks = 0;
        private double lastY = Double.NaN;
        private long airborneStartTime = 0L;

        public void reset() {
            this.airborneTicks = 0;
            this.lastY = Double.NaN;
            this.airborneStartTime = 0L;
        }

        public void tick(Location loc, long now) {
            if (loc == null) return;
            if (airborneStartTime == 0L) {
                airborneStartTime = now;
            }
            airborneTicks += 2; // 每 2 tick 巡检一次

            double curY = loc.getY();
            if (!Double.isNaN(lastY)) {
                double dy = curY - lastY;
                // 若出现正常下落 (下落幅度至少 0.06)，说明重力在起效
                if (dy < -0.06) {
                    airborneTicks = Math.max(0, airborneTicks - 2);
                }
            }
            this.lastY = curY;
        }

        public boolean isIllegalHover(int thresholdTicks, long thresholdMs) {
            long now = System.currentTimeMillis();
            return airborneTicks >= thresholdTicks && (now - airborneStartTime >= thresholdMs);
        }

        public int getAirTicks() {
            return airborneTicks;
        }
    }
}
