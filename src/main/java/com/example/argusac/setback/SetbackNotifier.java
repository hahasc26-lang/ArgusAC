package com.example.argusac.setback;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.i18n.I18n;
import com.example.argusac.log.model.CoordPoint;
import com.example.argusac.log.model.LogEntry;
import com.example.argusac.log.model.LogSeverity;
import com.example.argusac.log.model.LogType;
import com.example.argusac.log.model.SetbackFailureLog;
import com.example.argusac.log.model.ViolationLog;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * 回退提示通知、日志归档与安全踢出管理器 (SetbackNotifier)
 */
public class SetbackNotifier {

    private final ArgusACPlugin plugin;

    public SetbackNotifier(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    public void sendPlayerNotification(Player player, PlayerSetbackContext context, String notifyPlayerChannel, long notifyPlayerCooldownMs) {
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
            I18n.sendPrefixed(player, "punishment.setback-notify");
        } else {
            I18n.sendActionBar(player, "punishment.setback-notify");
        }
    }

    public void postSetbackSuccess(Player player, PlayerSetbackContext context, String checkName, int currentVl,
                                   String details, String mode, String fromLocStr, String toLocStr, boolean usedFallback,
                                   LegitimateLocation target, String notifyPlayerChannel, long notifyPlayerCooldownMs,
                                   boolean notifyAdminEnabled) {
        long now = System.currentTimeMillis();

        // 1. 对齐 GrimAC：直接取消机制彻底清除自免疫期，作弊移动被阻断后不给任何保护时间
        context.setImmunityUntil(0L);
        context.setLastSetbackTimestamp(now);

        // 2. 玩家端提示 (带独立冷却，走 i18n 多语言)
        sendPlayerNotification(player, context, notifyPlayerChannel, notifyPlayerCooldownMs);

        // 3. 管理员端广播 (全面接入 i18n 多语言)
        if (notifyAdminEnabled) {
            I18n.broadcastStaff(
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
            // 仅当回退目标确处于地面时才重置滞空时间
            if (target != null && target.isOnGround()) {
                pData.setAirTicks(0);
                pData.setOnGround(true);
            } else {
                pData.setOnGround(false);
            }
            pData.resetSpeedTracking();
            pData.getTimerTracker().reset();
            pData.addLog(log, maxLogs);
        }

        // 5. 提交统一结构化日志至 AuditLogManager
        CoordPoint fromPt = CoordPoint.fromLocation(player.getLocation(), false);
        LegitimateLocation targetLegit = context.getLastLegitimateLocation();
        CoordPoint toPt = targetLegit != null ? CoordPoint.fromLocation(targetLegit.toLocation(), false) : null;
        LogSeverity severity = (currentVl >= 15)
                ? LogSeverity.HIGH
                : (currentVl >= 5 ? LogSeverity.MEDIUM : LogSeverity.LOW);

        LogEntry entry = new LogEntry.Builder(
                LogType.VIOLATION,
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

        LogEntry failEntry = new LogEntry.Builder(
                LogType.SETBACK_FAIL,
                plugin.getAuditLogManager().getServerId()
        )
                .player(player.getName())
                .uuid(player.getUniqueId())
                .check(checkName)
                .reason(reason)
                .from(CoordPoint.fromLocation(player.getLocation(), false))
                .build();
        plugin.getAuditLogManager().submitLog(failEntry);
    }

    public void dispatchKick(Player player, String rawMessage) {
        Component kickReason = I18n.component(player, "punishment.kick-reason", "check", "Setback", "code", "OCCLUDED");
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
}
