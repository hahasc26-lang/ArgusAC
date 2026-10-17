package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.check.CheckManager;
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
        if (plugin.getHistoryTracker() != null) {
            plugin.getHistoryTracker().removeEntity(player.getUniqueId());
        }
        if (plugin.getLanguageManager() != null) {
            plugin.getLanguageManager().invalidateCache(player.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getEntity());
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(event.getRespawnLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(player.getLocation());
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

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.setLastTeleportTime(System.currentTimeMillis());
            data.setAirTicks(0);
            data.setOnGround(false);
            data.resetSpeedTracking();
            data.getElytraData().resetAll();
            if (event.getTo() != null) {
                data.getPhysicsState().reset(event.getTo());
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

        // During setback immunity, allow only the client acknowledgement at the setback
        // target. Any other movement ends immunity and is checked in this same event.
        if (plugin.getSetbackManager().isImmune(player.getUniqueId())
                && !plugin.getSetbackManager().isCorrectionMove(player, from, to)) {
            plugin.getSetbackManager().clearImmunity(player.getUniqueId());
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

        // 如果客户端声明离地且正在垂直向上起跳/升空，决不可标记为接地状态
        if (!player.isOnGround() && (to.getY() - from.getY()) > 0.05) {
            onGround = false;
        }
        data.setOnGround(onGround);

        // 【P0 修复】若在地面或真正深潜入水/游泳中，即刻重置空中滞空计时
        // 水面行走（脚在水面但头在空中）不再重置 airTicks，让 FlyCheck 能够检测水面悬浮
        boolean trulyInWater = adapter.isSwimming(player)
                || (adapter.isLiquid(to.getBlock()) && adapter.isLiquid(to.clone().add(0, 1.0, 0).getBlock()));
        if (onGround || trulyInWater) {
            data.setAirTicks(0);
        }

        // 冰面与粘液块滑行惯性追踪 (离开冰面后维持 4 tick 原版真实摩擦衰减，杜绝利用 25 tick 惯性盲区虚假滑冰)
        Block under = to.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isIce(under) || adapter.isSlime(under)) {
            data.setIceTicks(4);
        } else {
            data.decrementIceTicks();
        }

        Location originalTo = to.clone();

        // 1. 数据包完整性与异常角度检测 (即使仅原地旋转视角也必须拦截非法 Pitch/NaN)
        checkManager.getBadPacketsCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(player, data, event.isCancelled() ? from : event.getTo());
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
                    handleSetbackOrCancellation(player, data, event.isCancelled() ? from : event.getTo());
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
                handleSetbackOrCancellation(player, data, event.isCancelled() ? from : event.getTo());
                return;
            }
        } else {
            // 2. 移动速度检测
            checkManager.getSpeedCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.isCancelled() ? from : event.getTo());
                return;
            }

            // 3. 飞行与非法滞空检测
            checkManager.getFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.isCancelled() ? from : event.getTo());
                return;
            }

            // 4. 水上行走与水面虚假悬浮检测 (JesusCheck)
            checkManager.getJesusCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.isCancelled() ? from : event.getTo());
                return;
            }

        }

        // 5. 背包移动检测
        checkManager.getInventoryMoveCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(player, data, event.isCancelled() ? from : event.getTo());
            return;
        }

        // 6. 击退位移追踪
        checkManager.getVelocityCheck().handleMove(player, data, event);
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

        // 仅在数据包协议管线未就绪时由 Bukkit 事件作为降级统计，杜绝网络层与主线程双重打点导致的 CPS 翻倍误报
        recordClickIfFallback(attacker);

        // 杀戮光环检测
        checkManager.getKillAuraCheck().handleAttack(attacker, event.getEntity(), event);
        if (event.isCancelled()) {
            return;
        }

        // 攻击距离检测
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
        if (action == Action.LEFT_CLICK_BLOCK) {
            if (data != null) {
                data.setDigging(true);
                data.setLastDiggingTime(System.currentTimeMillis());
                data.getClickTimestamps().clear();
            }
        } else if (action == Action.LEFT_CLICK_AIR) {
            recordClickIfFallback(event.getPlayer());
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
