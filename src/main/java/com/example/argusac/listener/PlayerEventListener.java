package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.check.CheckManager;
import com.example.argusac.data.PlayerData;

import com.example.argusac.model.VelocitySourceType;
import org.bukkit.Location;
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getEntity());
        if (data != null) {
            data.getElytraData().resetAll();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.getElytraData().resetAll();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.getElytraData().resetAll();
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
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) {
            return;
        }

        data.setFromLocation(from);
        data.setToLocation(to);
        // 用服务端物理碰撞判断接地状态，替代已废弃的 player.isOnGround()
        boolean onGround = to.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid();
        data.setOnGround(onGround);

        // P1 改法：将“防止回退递归/网络校正误报”与“关闭所有检测”拆开
        // 仅在当前移动为服务端回退的网络校正包时赋予短时校正速度豁免，检测流程继续正常运行，彻底消除 1.5 秒盲区
        if (plugin.getSetbackManager().isCorrectionMove(player, from, to)) {
            data.grantLegalVelocity(com.example.argusac.model.VelocitySourceType.SETBACK_CORRECTION, 300L);
        }

        Location originalTo = to.clone();

        // 1. 数据包完整性与异常角度检测
        checkManager.getBadPacketsCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            data.setToLocation(event.getTo());
            return;
        }

        // 鞘翅滑翔检测 (ElytraFly) 与传统陆地/悬空移动检测 (Speed, Fly, NoFall) 显式互斥
        if (checkManager.isGlidingExempt(player, data)) {
            // 滑翔状态由 ElytraFly 集中全面接管 (包含 ElytraSpeed, ElytraAscend, ElytraHover, ElytraTimer 等)
            checkManager.getElytraFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                data.setToLocation(event.getTo());
                return;
            }
        } else {
            // 2. 移动速度检测
            checkManager.getSpeedCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                data.setToLocation(event.getTo());
                return;
            }

            // 3. 飞行与非法滞空检测
            checkManager.getFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                data.setToLocation(event.getTo());
                return;
            }

            // 4. 虚假落地检测
            checkManager.getNoFallCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                data.setToLocation(event.getTo());
                return;
            }
        }

        // 5. 背包移动检测
        checkManager.getInventoryMoveCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            data.setToLocation(event.getTo());
            return;
        }

        // 6. 击退位移追踪
        checkManager.getVelocityCheck().handleMove(player, data, event);
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

        // 点击计数
        checkManager.getAutoClickerCheck().handleClick(attacker);

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
            checkManager.getAutoClickerCheck().handleClick(event.getPlayer());
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
}
