package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.manager.CheckManager;
import com.example.argusac.data.PlayerData;

import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.model.VelocitySourceType;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/**
 * 玩家事件主监听器 (PlayerEventListener)
 * 统一调度移动、战斗、世界交互及生命周期事件。
 * <p>
 * 【回退机制重构原则】：
 * 1. 每次移动通过所有检测模块验证后，立即更新覆盖唯一的 lastLegitimateLocation。
 * 2. 死亡时清空，加入/重生/换界/外部传送时重置，进出载具时清空 previousLegitimateLocation。
 * 3. 彻底删除危险方块判定、空气层探测与快照队列。
 */
public class PlayerEventListener implements Listener {

    private final ArgusACPlugin plugin;
    private final CheckManager checkManager;

    public PlayerEventListener(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.checkManager = plugin.getCheckManager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.getElytraData().resetAll();
        }

        // 记录统一会话 SESSION 加入日志
        String brand = com.example.argusac.compat.VersionAdapterFactory.getAdapter().getClientBrand(player);
        int ping = com.example.argusac.compat.VersionAdapterFactory.getAdapter().getPing(player);
        String ip = resolvePlayerIp(player);
        com.example.argusac.log.model.LogEntry sessionLog = new com.example.argusac.log.model.LogEntry.Builder(
                com.example.argusac.log.model.LogType.SESSION,
                plugin.getAuditLogManager().getServerId())
                .player(player.getName())
                .uuid(player.getUniqueId())
                .ip(ip)
                .brand(brand)
                .ping(ping)
                .sessionAction("JOIN")
                .extra("world=" + player.getWorld().getName())
                .build();
        plugin.getAuditLogManager().submitLog(sessionLog);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        long duration = 0L;
        if (data != null) {
            data.getElytraData().resetAll();
            duration = Math.max(0L, System.currentTimeMillis() - data.getLastJoinTime());
        }

        // 记录统一会话 SESSION 退出日志
        com.example.argusac.log.model.LogEntry sessionLog = new com.example.argusac.log.model.LogEntry.Builder(
                com.example.argusac.log.model.LogType.SESSION,
                plugin.getAuditLogManager().getServerId())
                .player(player.getName())
                .uuid(player.getUniqueId())
                .sessionAction("QUIT")
                .duration(duration)
                .build();
        plugin.getAuditLogManager().submitLog(sessionLog);

        plugin.getPlayerManager().removePlayerData(player.getUniqueId());
        plugin.getAlertManager().clearSessionState(player);
        if (plugin.getSetbackManager() != null) {
            plugin.getSetbackManager().getContext(player.getUniqueId()).clearLockedAnchor();
        }
        if (plugin.getHistoryTracker() != null) {
            plugin.getHistoryTracker().removeEntity(player.getUniqueId());
        }
        if (plugin.getLanguageManager() != null) {
            plugin.getLanguageManager().invalidateCache(player.getUniqueId());
        }
        if (plugin.getCombatMitigationHandler() != null) {
            plugin.getCombatMitigationHandler().clearPlayer(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (plugin.getSetbackManager() != null) {
            plugin.getSetbackManager().getContext(event.getEntity().getUniqueId()).clearLockedAnchor();
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getEntity());
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(event.getEntity().getLocation());
            data.resetMaceFallState(event.getEntity().getLocation().getY());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (plugin.getSetbackManager() != null) {
            plugin.getSetbackManager().getContext(event.getPlayer().getUniqueId()).clearLockedAnchor();
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(event.getRespawnLocation());
            data.resetMaceFallState(event.getRespawnLocation().getY());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (plugin.getSetbackManager() != null) {
            plugin.getSetbackManager().getContext(player.getUniqueId()).clearLockedAnchor();
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(player.getLocation());
            data.resetMaceFallState(player.getLocation().getY());
        }

        // 记录跨世界 SESSION 日志
        com.example.argusac.log.model.LogEntry sessionLog = new com.example.argusac.log.model.LogEntry.Builder(
                com.example.argusac.log.model.LogType.SESSION,
                plugin.getAuditLogManager().getServerId())
                .player(player.getName())
                .uuid(player.getUniqueId())
                .sessionAction("WORLD_CHANGE")
                .extra("from=" + event.getFrom().getName() + ";to=" + player.getWorld().getName())
                .build();
        plugin.getAuditLogManager().submitLog(sessionLog);
    }

    private String resolvePlayerIp(Player player) {
        if (!plugin.getConfig().getBoolean("audit.privacy.log-ip", false)) {
            return null;
        }
        if (player.getAddress() == null)
            return null;
        String raw = player.getAddress().getAddress().getHostAddress();
        if (plugin.getConfig().getBoolean("audit.privacy.hash-ip", true)) {
            String salt = plugin.getConfig().getString("audit.privacy.salt", "ArgusAC-Salt");
            String hashed = com.example.argusac.log.core.HashChainEngine.sha256(salt + ":" + raw);
            return hashed.substring(0, 16);
        }
        return raw;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleportCheck(PlayerTeleportEvent event) {
        // 浮点数异常检测拦截 (NaN / Infinite)
        Location to = event.getTo();
        if (to != null && (Double.isNaN(to.getX()) || Double.isNaN(to.getY()) || Double.isNaN(to.getZ())
                || Double.isInfinite(to.getX()) || Double.isInfinite(to.getY()) || Double.isInfinite(to.getZ()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (plugin.getSetbackManager().isSelfTeleport(player.getUniqueId())) {
            return;
        }

        com.example.argusac.setback.PlayerSetbackContext context = plugin.getSetbackManager().getContext(player.getUniqueId());
        if (context != null && event.getTo() != null) {
            java.util.UUID vehicleId = (player.isInsideVehicle() && player.getVehicle() != null)
                    ? player.getVehicle().getUniqueId() : null;
            context.reset(event.getTo(), player.isInsideVehicle(), vehicleId);
            context.clearLockedAnchor();
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.setLastTeleportTime(System.currentTimeMillis());
            data.setAirTicks(0);
            data.setOnGround(false);
            data.resetSpeedTracking();
            data.getElytraData().resetAll();
            if (event.getTo() != null) {
                data.getPhysicsState().reset(event.getTo());
                data.getPhysicsState().clearPendingVelocities();
                data.resetMaceFallState(event.getTo().getY());
            }

            // 外部传送赋予合法速度豁免
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
                data.grantLegalVelocity(VelocitySourceType.ENDER_PEARL, 1000L);
            } else {
                data.grantLegalVelocity(VelocitySourceType.EXTERNAL_TELEPORT, 1500L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityToggleGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (plugin.isPlayerExempt(player)) {
                return;
            }
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                checkManager.getElytraFlyCheck().handleToggleGlide(player, data, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.grantLegalVelocity(VelocitySourceType.VEHICLE, 1000L);
                data.getElytraData().resetGlideState();
                data.resetMaceFallState(player.getLocation().getY());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.grantLegalVelocity(VelocitySourceType.VEHICLE, 1000L);
                data.getElytraData().resetGlideState();
                data.resetMaceFallState(player.getLocation().getY());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.setLastFlightToggleTime(System.currentTimeMillis());
            data.getElytraData().resetGlideState();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            checkManager.getVelocityCheck().handleVelocity(player, event);
            checkManager.getElytraFlyCheck().handleVelocity(player, data, event);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null) {
            return;
        }

        // PlayerTeleportEvent extends PlayerMoveEvent. Teleports have their own validation
        // and lifecycle handling; never compare their destination against movement limits.
        if (event instanceof PlayerTeleportEvent) {
            data.resetSpeedTracking();
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        boolean posChanged = (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ());
        boolean rotChanged = (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch());

        // 既无位置位移也无旋转变化时直接跳过
        if (!posChanged && !rotChanged) {
            return;
        }

        // 对齐 GrimAC 传送传输中 (Teleport In-Flight) 机制：
        // 服务端发出的位置重置包在客户端收到并回复 ACK 之前存在 1 个 RTT 延迟。
        // 期间来自客户端的旧移动包必须坚决阻断取消，严禁推动玩家位置或触发二次误判
        com.example.argusac.setback.PlayerSetbackContext setbackCtx = plugin.getSetbackManager().getContext(player.getUniqueId());
        if (setbackCtx != null && setbackCtx.isTeleportInFlight()) {
            if (setbackCtx.matchesCorrection(to)) {
                setbackCtx.confirmTeleport(setbackCtx.getInFlightTeleportId());
            } else {
                Location inFlightTarget = setbackCtx.getTeleportInFlightTarget();
                if (inFlightTarget != null) {
                    event.setTo(inFlightTarget);
                }
                event.setCancelled(true);
                return;
            }
        }

        data.setFromLocation(from);
        data.setToLocation(to);
        data.setLastLookYaw(to.getYaw());
        data.setLastLookPitch(to.getPitch());

        // 鲁棒多点与方块材质物理碰撞接地判定 (消除地毯、雪片、台阶、方块边缘悬空误判)
        boolean onGround = isGrounded(player, to);
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 双向接地校验：若多点探针处于微小接缝或台阶提升 (Step-Up) 处，只要客户端声明在地面且脚下 0.35 格内有物理承托面，均认定为接地
        if (!onGround && player.isOnGround()) {
            if (hasSupportingBlockBelow(to, adapter, 0.35)) {
                onGround = true;
            }
        }

        // 如果客户端声明离地且正在垂直向上起跳/升空，仅在脚下没有紧贴实体方块时认定为离地
        if (!player.isOnGround() && (to.getY() - from.getY()) > 0.05 && !hasSupportingBlockBelow(to, adapter, 0.15)) {
            onGround = false;
        }
        data.setLastOnGround(data.isOnGround());
        data.setOnGround(onGround);

        // 接地与流体/滞空状态维护 (水、岩浆等流体拥有原版浮力与阻力，不计为真空空气滞空)
        boolean inLiquid = adapter.isInWater(player)
                || adapter.isSwimming(player)
                || adapter.isLiquid(to.getBlock())
                || adapter.isLiquid(from.getBlock())
                || adapter.isLiquid(to.clone().subtract(0, 0.4, 0).getBlock());
        Block under = to.clone().subtract(0, 0.5, 0).getBlock();
        if (onGround || inLiquid) {
            data.setAirTicks(0);
            data.incrementGroundTicks();
            data.resetMaceFallState(to.getY());
            // 落地且已脱离冰面与滑行记忆期，重置空中冰面起跳惯性
            if (!adapter.isIce(under) && !adapter.isSlime(under) && data.getIceTicks() <= 0) {
                data.setAirborneFromIce(false);
            }
        } else {
            data.setGroundTicks(0);
            data.updateMaceApex(to.getY());
            // 离地起跳帧：若脚下为冰/粘液或处于冰面记忆期，标记本次滞空继承冰面起跳大动量
            Block underFrom = from.clone().subtract(0, 0.5, 0).getBlock();
            if (data.getIceTicks() > 0 || adapter.isIce(underFrom) || adapter.isSlime(underFrom)) {
                data.setAirborneFromIce(true);
            }
        }

        // 冰面与粘液块滑行惯性追踪 (离开冰面后维持 4 tick 原版真实摩擦衰减，杜绝利用 25 tick 惯性盲区虚假滑冰)
        if (adapter.isIce(under) || adapter.isSlime(under)) {
            data.setIceTicks(4);
        } else {
            data.decrementIceTicks();
        }
        if (adapter.isSlime(under)) {
            data.setSlimeTicks(20);
        } else {
            data.decrementSlimeTicks();
        }

        Location originalTo = to.clone();

        // 1. 数据包完整性与异常角度检测 (即使仅原地旋转视角也必须拦截非法 Pitch/NaN)
        checkManager.getBadPacketsCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
            return;
        }

        // 核心防空中悬停：若无空间位置位移 (仅为视角转动或客户端发包锁定原点)：
        // 1. 若玩家处于地面上，安全结束，无需执行后续空间物理位移检测；
        // 2. 若玩家处于空中 (!onGround)，空间位置保持不动正是最典型的空中定点悬浮 (Hover)！
        //    绝对不能跳过，必须强制送入 FlyCheck 严格判定滞空与重力下坠状态！
        if (!posChanged) {
            if (!onGround && !checkManager.isGlidingExempt(player, data)) {
                checkManager.getFlyCheck().handleMove(player, data, event);
                if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                    handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                }
            }
            return;
        }

        // 鞘翅滑翔检测 (ElytraFly) 与传统陆地/悬空移动检测 (Speed, Fly, NoFall) 显式互斥
        if (checkManager.isGlidingExempt(player, data)) {
            // 滑翔状态由 ElytraFly 集中全面接管 (包含 ElytraSpeed, ElytraAscend, ElytraHover,
            // ElytraTimer 等)
            checkManager.getElytraFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }
        } else {
            // 2. 移动速度检测
            checkManager.getSpeedCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }

            // 3. 飞行与非法滞空检测
            checkManager.getFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }

            // 4. 水上行走与水面虚假悬浮检测 (JesusCheck)
            checkManager.getJesusCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }

        }

        // 5. 背包移动检测
        checkManager.getInventoryMoveCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
            return;
        }

        // 6. 击退位移追踪
        checkManager.getVelocityCheck().handleMove(player, data, event);

        // 7. 合法移动状态更新与锁定锚点解除：
        // 若本 tick 移动完全合法且未触发任何取消/回退，且玩家安全处于稳固地面上，
        // 在距最后一次取消违规一定安全窗口 (400ms) 之后，平稳解除原位锁定锚点
        if (!event.isCancelled() && !hasSetbackOccurred(originalTo, event.getTo())) {
            if (onGround || inLiquid) {
                com.example.argusac.setback.PlayerSetbackContext setbackContext =
                        plugin.getSetbackManager().getContext(player.getUniqueId());
                if (setbackContext != null && setbackContext.getLockedAnchorLocation() != null) {
                    // 对标 GrimAC 锚点守恒法则：必须完整度过 2000ms 违规锁死周期，且在地面稳定合法移动，才允许解除锁定锚点！
                    if (!setbackContext.isUnderViolationLock()) {
                        setbackContext.clearLockedAnchor();
                    }
                }
            }
        }
    }

    private void handleSetbackOrCancellation(Player player, PlayerData data, Location finalLoc) {
        if (finalLoc != null && isGrounded(player, finalLoc)) {
            data.setAirTicks(0);
            data.setOnGround(true);
        } else {
            data.setOnGround(false);
        }
        if (finalLoc != null) {
            data.setToLocation(finalLoc);
            if (data.getPhysicsState() != null) {
                data.getPhysicsState().reset(finalLoc);
            }
        }
    }

    private boolean hasSetbackOccurred(Location origTo, Location currentTo) {
        if (currentTo == null) {
            return true;
        }
        return origTo.getX() != currentTo.getX()
                || origTo.getY() != currentTo.getY()
                || origTo.getZ() != currentTo.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.setLastDamageTime(System.currentTimeMillis());
                // 受伤击退赋予合法速度豁免
                data.grantLegalVelocity(VelocitySourceType.EXPLOSION, 800L);
                checkManager.getElytraFlyCheck().handleDamage(player, data, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (!(damager instanceof Player attacker)) {
            return;
        }

        if (plugin.isPlayerExempt(attacker)) {
            return;
        }

        // 第三阶段 Bukkit 层兜底防御 (Fallback)：
        // 正常情况下违规攻击已在 Netty 进包阶段 (PacketPlayInUseEntity) 被直接丢弃，本事件不应被触发；
        // 若因同 tick 多包穿透或外部插件直接构造事件触发至此，首先检查攻击者是否已处于抑制窗口
        if (plugin.getCombatMitigationHandler() != null && plugin.getCombatMitigationHandler().isAttackSuppressed(attacker)) {
            event.setCancelled(true);
            try {
                event.setDamage(0.0);
            } catch (Throwable ignored) {}
            return;
        }

        // 若目标实体为首次交互的非玩家生物（尚未进入 EntityHistoryTracker 索引），当场补录进索引缓存
        if (plugin.getHistoryTracker() != null && event.getEntity() != null) {
            plugin.getHistoryTracker().recordEntity(
                    event.getEntity(),
                    VersionAdapterFactory.getAdapter(),
                    System.currentTimeMillis()
            );
        }

        // 仅在数据包协议管线未就绪时由 Bukkit 事件作为降级统计，杜绝网络层与主线程双重打点导致的 CPS 翻倍误报
        recordClickIfFallback(attacker);

        // 重锤下落伤害上限与最终伤害数值核验 (MaceDMG / Theoretical Damage Cap)
        checkManager.getMaceCheck().handleAttack(attacker, event.getEntity(), event);
        if (event.isCancelled()) {
            return;
        }

        // 当 Packet 管线未激活或目标实体此前未被缓存命中时，执行 KillAura 与 Reach 的 Bukkit 层兜底检测
        checkManager.getKillAuraCheck().handleAttack(attacker, event.getEntity(), event);
        if (event.isCancelled()) {
            return;
        }

        checkManager.getReachCheck().handleAttack(attacker, event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.isPlayerExempt(event.getPlayer())) {
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            checkManager.getElytraFlyCheck().handleInteract(event.getPlayer(), data, event);
        }
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            if (event.getItem() != null) {
                checkManager.getFastBowCheck().handleBowStart(event.getPlayer(), event.getItem());
                checkManager.getFastEatCheck().handleItemStartUse(event.getPlayer(), event.getItem());
            }
            Player p = event.getPlayer();
            if (p.getInventory() != null && p.getInventory().getItemInOffHand() != null) {
                checkManager.getFastEatCheck().handleItemStartUse(p, p.getInventory().getItemInOffHand());
            }
        } else if (action == Action.LEFT_CLICK_BLOCK) {
            if (data != null) {
                data.setDigging(true);
                data.setLastDiggingTime(System.currentTimeMillis());
                data.getClickTimestamps().clear();
            }
        } else if (action == Action.LEFT_CLICK_AIR) {
            recordClickIfFallback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            checkManager.getFastBowCheck().handleShootBow(player, event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.setDigging(true);
            data.setLastDiggingTime(System.currentTimeMillis());
            data.getClickTimestamps().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.setLastDiggingTime(System.currentTimeMillis());
            data.setDigging(false);
        }
        checkManager.getFastBreakCheck().handleBreak(player, event);
        if (event.isCancelled()) {
            return;
        }
        checkManager.getNukerCheck().handleBreak(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.setLastPlaceTime(System.currentTimeMillis());
        }
        checkManager.getScaffoldCheck().handlePlace(player, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.setInventoryOpen(true, player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.setInventoryOpen(false, null);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null && (player.isGliding() || data.isElytraGliding())) {
                boolean isChestplateSlot = (event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR && event.getSlot() == 38)
                        || event.getRawSlot() == 6;
                boolean isArmorRelated = isChestplateSlot
                        || event.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR
                        || event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND
                        || event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY;

                if (isArmorRelated) {
                    long now = System.currentTimeMillis();
                    data.getElytraData().recordArmorSwap(now);

                    boolean armorSwapCancel = plugin.getChecksConfig().getBoolean("checks.ElytraFly.glide-state.armor-swap-cancel", true);
                    if (isChestplateSlot && armorSwapCancel) {
                        // 正在空中滑翔时强行换脱胸甲：当帧直接阻断该交互包，剥离滑翔姿态并下发协议同步包锚定
                        event.setCancelled(true);
                        player.setGliding(false);
                        data.getElytraData().resetGlideState();
                        data.resetMaceFallState(player.getLocation().getY());
                        plugin.getSetbackManager().sendPositionSyncPacket(player, player.getLocation());
                        return;
                    }

                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && (player.isGliding() || data.isElytraGliding())) {
                            org.bukkit.inventory.ItemStack chest = player.getInventory().getChestplate();
                            if (chest == null || chest.getType() != org.bukkit.Material.ELYTRA) {
                                player.setGliding(false);
                                data.getElytraData().resetGlideState();
                                data.resetMaceFallState(player.getLocation().getY());
                                plugin.getSetbackManager().sendPositionSyncPacket(player, player.getLocation());
                            }
                        }
                    });
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        checkManager.getFastEatCheck().handleItemConsume(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {
        checkManager.getFastEatCheck().handleItemStopUse(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        checkManager.getFastEatCheck().handleItemStopUse(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null && (player.isGliding() || data.isElytraGliding())) {
            data.getElytraData().recordArmorSwap(System.currentTimeMillis());
        }
        checkManager.getFastEatCheck().handleItemStopUse(player);
    }

    /**
     * 鲁棒多点与环境方块物理碰撞接地判定
     */
    private boolean isGrounded(Player player, Location loc) {
        if (player.isInsideVehicle()) {
            return true;
        }
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        org.bukkit.World world = loc.getWorld();
        if (world == null)
            return false;

        // 探测中心点及四个底角 (AABB 半径 0.28)
        double[][] points = {
                { 0, 0 },
                { -0.28, -0.28 },
                { 0.28, -0.28 },
                { -0.28, 0.28 },
                { 0.28, 0.28 }
        };

        for (double[] p : points) {
            Location checkLoc = new Location(world, x + p[0], y - 0.05, z + p[1]);
            Block b = checkLoc.getBlock();
            if (adapter.isStandable(b)) {
                return true;
            }
            // 针对栅栏 (Fence) 与石墙 (Wall)：碰撞箱高 1.5 格，脚下方块在 y - 0.6 处
            Block fenceBlock = checkLoc.clone().subtract(0, 0.55, 0).getBlock();
            String name = fenceBlock.getType().name();
            if (name.contains("FENCE") || name.contains("WALL") || name.contains("BAMBOO")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 仅在 PacketEvents 底层数据包协议管线未就绪时，降级采用 Bukkit 事件统计 CPS
     */
    private void recordClickIfFallback(Player player) {
        if (plugin.getPacketPipelineManager() == null || !plugin.getPacketPipelineManager().isActive()) {
            checkManager.getAutoClickerCheck().handleClick(player);
        }
    }

    private boolean hasSupportingBlockBelow(Location loc, VersionAdapter adapter, double maxDepth) {
        if (loc == null || loc.getWorld() == null)
            return false;
        for (double d = 0.05; d <= maxDepth; d += 0.10) {
            Block b = loc.clone().subtract(0, d, 0).getBlock();
            if (adapter.isStandable(b)) {
                return true;
            }
        }
        return false;
    }
}
