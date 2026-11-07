package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * 玩家生命周期与会话监听器 (PlayerLifecycleListener)
 * 统一调度玩家加入、退出、死亡、重生、跨世界等生命周期事件及审计日志。
 */
public class PlayerLifecycleListener implements Listener {

    private final ArgusACPlugin plugin;

    public PlayerLifecycleListener(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.getElytraData().resetAll();
            Location loc = player.getLocation();
            if (loc != null) {
                data.updatePacketLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
            }
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
            plugin.getSetbackManager().removeContext(player.getUniqueId());
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
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getEntity());
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(event.getEntity().getLocation());
            data.resetMaceFallState(event.getEntity().getLocation().getY());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(event.getRespawnLocation());
            data.resetMaceFallState(event.getRespawnLocation().getY());
            data.clearExpectedVelocity();
            data.setVelocityHandled(true);
            data.setLastRespawnTime(System.currentTimeMillis());
            Location respawnLoc = event.getRespawnLocation();
            if (respawnLoc != null) {
                data.updatePacketLocation(respawnLoc.getX(), respawnLoc.getY(), respawnLoc.getZ(),
                        respawnLoc.getYaw(), respawnLoc.getPitch());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.getElytraData().resetAll();
            data.getPhysicsState().reset(player.getLocation());
            data.resetMaceFallState(player.getLocation().getY());
            Location loc = player.getLocation();
            if (loc != null) {
                data.updatePacketLocation(loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
            }
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
}

