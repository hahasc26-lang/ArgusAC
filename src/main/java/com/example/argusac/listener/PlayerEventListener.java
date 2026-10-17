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
                plugin.getAuditLogManager().getServerId()
        )
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
                plugin.getAuditLogManager().getServerId()
        )
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
                plugin.getAuditLogManager().getServerId()
        )
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
        if (player.getAddress() == null) return null;
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

        data.setFromLocation(from);
        data.setToLocation(to);
        data.setLastLookYaw(to.getYaw());
        data.setLastLookPitch(to.getPitch());

        // 鲁棒多点与方块材质物理碰撞接地判定 (消除地毯、雪片、台阶、方块边缘悬空误判)
        boolean onGround = isGrounded(player, to);
        // 如果客户端声明离地且正在垂直向上起跳/升空，决不可标记为接地状态
        if (!player.isOnGround() && (to.getY() - from.getY()) > 0.05) {
            onGround = false;
        }
        data.setOnGround(onGround);

        // 冰面与粘液块滑行惯性追踪 (离开冰面后维持 25 tick 惯性记忆，彻底消除跳出冰面误报 Speed)
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        Block under = to.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isIce(under) || adapter.isSlime(under)) {
            data.setIceTicks(25);
        } else {
            data.decrementIceTicks();
        }

        // P1 改法：将“防止回退递归/网络校正误报”与“关闭所有检测”拆开
        // 仅在当前移动为服务端回退的网络校正包时赋予短时校正速度豁免，检测流程继续正常运行，彻底消除 1.5 秒盲区
        if (plugin.getSetbackManager().isCorrectionMove(player, from, to)) {
            data.grantLegalVelocity(com.example.argusac.model.VelocitySourceType.SETBACK_CORRECTION, 300L);
        }

        Location originalTo = to.clone();

        // 1. 数据包完整性与异常角度检测 (即使仅原地旋转视角也必须拦截非法 Pitch/NaN)
        checkManager.getBadPacketsCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(data, event.isCancelled() ? from : event.getTo());
            return;
        }

        // 若无空间位置位移 (仅为视角转动)，则安全结束，无需执行后续空间物理位移检测
        if (!posChanged) {
            return;
        }

        // 鞘翅滑翔检测 (ElytraFly) 与传统陆地/悬空移动检测 (Speed, Fly, NoFall) 显式互斥
        if (checkManager.isGlidingExempt(player, data)) {
            // 滑翔状态由 ElytraFly 集中全面接管 (包含 ElytraSpeed, ElytraAscend, ElytraHover, ElytraTimer 等)
            checkManager.getElytraFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(data, event.isCancelled() ? from : event.getTo());
                return;
            }
        } else {
            // 2. 移动速度检测
            checkManager.getSpeedCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(data, event.isCancelled() ? from : event.getTo());
                return;
            }

            // 3. 飞行与非法滞空检测
            checkManager.getFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(data, event.isCancelled() ? from : event.getTo());
                return;
            }

            // 4. 虚假落地检测
            checkManager.getNoFallCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(data, event.isCancelled() ? from : event.getTo());
                return;
            }
        }

        // 5. 背包移动检测
        checkManager.getInventoryMoveCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(data, event.isCancelled() ? from : event.getTo());
            return;
        }

        // 6. 击退位移追踪
        checkManager.getVelocityCheck().handleMove(player, data, event);
    }

    private void handleSetbackOrCancellation(PlayerData data, Location finalLoc) {
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
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            recordClickIfFallback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
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
        if (world == null) return false;

        // 探测中心点及四个底角 (AABB 半径 0.28)
        double[][] points = {
                {0, 0},
                {-0.28, -0.28},
                {0.28, -0.28},
                {-0.28, 0.28},
                {0.28, 0.28}
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
}
