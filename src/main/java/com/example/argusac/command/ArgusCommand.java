package com.example.argusac.command;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.data.PlayerData;
import com.example.argusac.i18n.I18n;
import com.example.argusac.log.core.AuditLogManager;
import com.example.argusac.log.model.LogEntry;
import com.example.argusac.log.model.LogSeverity;
import com.example.argusac.log.model.LogType;
import com.example.argusac.log.query.LogQueryService;
import com.example.argusac.log.replay.AsciiReplayRenderer;
import com.example.argusac.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ArgusAC 主命令处理器 (/argus, /ac, /argusac)
 * 全面接入 i18n 国际化多语言系统，支持根据操作者客户端语言自适应呈现
 */
public class ArgusCommand implements CommandExecutor {

    private final ArgusACPlugin plugin;
    private final AsciiReplayRenderer replayRenderer;

    public ArgusCommand(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.replayRenderer = new AsciiReplayRenderer();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        // 1. 玩家自身隐私与数据查询 (GDPR 个人透明度支持，无需 admin 权限)
        if ("my-data".equals(sub)) {
            if (!(sender instanceof Player p)) {
                I18n.sendPrefixed(sender, "system.only-player");
                return true;
            }
            int retention = plugin.getConfig().getInt("audit.archive.retention-days", 30);
            boolean hashed = plugin.getConfig().getBoolean("audit.privacy.hash-ip", false);
            I18n.send(p, "command.my-data.header");
            I18n.send(p, "command.my-data.retention", "days", retention);
            I18n.send(p, "command.my-data.anonymization", "status", hashed ? "SHA-256 Hashed" : "Disabled");
            I18n.send(p, "command.my-data.purge-hint", "player", p.getName());
            return true;
        }

        if ("alerts".equals(sub)) {
            handleAlerts(sender, args);
            return true;
        }

        // 以下管理命令必须持有 argusac.admin (兼顾大小写兼容)
        if (!sender.hasPermission("argusac.admin") && !sender.hasPermission("ArgusAC.admin")) {
            I18n.sendPrefixed(sender, "system.no-permission", "permission", "argusac.admin");
            return true;
        }

        switch (sub) {
            case "reload" -> {
                long start = System.currentTimeMillis();
                plugin.reloadAll();
                long elapsed = System.currentTimeMillis() - start;
                I18n.sendPrefixed(sender, "system.reload-success", "time", elapsed);
                recordAdminAction(sender, "RELOAD", "all", "SUCCESS");
            }
            case "vl" -> handleVl(sender, label, args);
            case "logs" -> handleLogs(sender, label, args);
            case "history" -> handleHistory(sender, label, args);
            case "export" -> handleExport(sender, label, args);
            case "top" -> handleTop(sender, label, args);
            case "status" -> handleStatus(sender);
            case "verify" -> handleVerify(sender);
            case "debug" -> handleDebug(sender, label, args);
            case "replay" -> handleReplay(sender, label, args);
            case "purge" -> handlePurge(sender, label, args);
            case "compat" -> handleCompat(sender);
            case "toggle" -> handleToggle(sender, label, args);
            case "setback" -> handleSetbackCommand(sender, label, args);
            default -> I18n.sendPrefixed(sender, "system.unknown-command", "subcommand", sub);
        }

        return true;
    }

    private void handleAlerts(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            I18n.sendPrefixed(sender, "system.only-player");
            return;
        }
        if (!player.hasPermission("argusac.alert") && !player.hasPermission("ArgusAC.alert")) {
            I18n.sendPrefixed(sender, "system.no-permission", "permission", "argusac.alert");
            return;
        }
        if (args.length > 2) {
            I18n.send(player, "command.alerts.usage");
            return;
        }

        boolean muted = plugin.getAlertManager().isMuted(player);
        String action = args.length == 1 ? (muted ? "on" : "off") : args[1].toLowerCase();
        switch (action) {
            case "on" -> {
                plugin.getAlertManager().setMuted(player, false);
                I18n.send(player, "command.alerts.enabled");
            }
            case "off" -> {
                plugin.getAlertManager().setMuted(player, true);
                I18n.send(player, "command.alerts.muted");
            }
            case "status" -> I18n.send(player, muted
                    ? "command.alerts.status-muted"
                    : "command.alerts.status-enabled");
            default -> I18n.send(player, "command.alerts.usage");
        }
    }

    private void handleCompat(CommandSender sender) {
        var adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        I18n.send(sender, "command.compat.header");
        I18n.send(sender, "command.compat.env",
                "env", com.example.argusac.compat.ServerVersion.get().getFriendlyName(),
                "code", com.example.argusac.compat.ServerVersion.getCode(),
                "core", com.example.argusac.compat.ServerVersion.isPaper() ? "Paper" : "Spigot");
        I18n.send(sender, "command.compat.adapter", "adapter", adapter.getClass().getSimpleName());
        I18n.send(sender, "command.compat.divider");

        com.example.argusac.compat.CompatibilityManager compatMgr = plugin.getCompatibilityManager();
        compatMgr.getAllStatus().forEach((checkName, status) -> {
            String reason = compatMgr.getReason(sender, checkName);
            adapter.sendMessage(sender, String.format(" &8- &b%-14s %s &8| &7%s",
                    checkName, status.getDisplay(sender), reason));
        });

        I18n.send(sender, "command.compat.divider");
        I18n.send(sender, "command.compat.legend");
    }

    private void handleVl(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.vl.usage", "label", label);
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            I18n.sendPrefixed(sender, "system.target-not-found", "target", args[1]);
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(target);
        int ping = com.example.argusac.compat.VersionAdapterFactory.getAdapter().getPing(target);
        I18n.send(sender, "command.vl.header", "player", target.getName());
        I18n.send(sender, "command.vl.summary", "ping", (ping >= 0 ? ping : 0), "total_vl", data.getTotalVl());

        Map<String, Integer> vls = data.getAllVls();
        if (vls.isEmpty()) {
            I18n.send(sender, "command.vl.clean");
        } else {
            vls.forEach((chk, vl) -> {
                Check c = plugin.getCheckManager().getCheck(chk);
                int max = c != null ? c.getMaxVl() : 15;
                I18n.send(sender, "command.vl.entry", "check", chk, "vl", vl, "max_vl", max);
            });
        }
    }

    private void handleLogs(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.logs.usage", "label", label);
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        String check = parseParam(args, "--check");
        long sinceMs = parseDuration(parseParam(args, "--since"));
        boolean setback = containsFlag(args, "--setback");
        int page = parseIntParam(args, "--page", 1);

        List<LogEntry> logs = plugin.getLogQueryService().queryMemoryLogs(uuid, check, sinceMs, setback);
        if (logs.isEmpty()) {
            I18n.sendPrefixed(sender, "command.logs.empty", "player", targetName);
            return;
        }

        renderLogEntries(sender, I18n.get(sender, "command.logs.title-memory", "count", logs.size()), logs, page, 8);
    }

    private void handleHistory(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            I18n.send(sender, "command.history.usage", "label", label);
            return;
        }
        String targetName = args[1];
        int days = 1;
        try {
            days = Integer.parseInt(args[2]);
        } catch (NumberFormatException ignored) {}

        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        String check = parseParam(args, "--check");
        boolean setback = containsFlag(args, "--setback");
        int page = parseIntParam(args, "--page", 1);

        I18n.sendPrefixed(sender, "command.history.searching");
        plugin.getLogQueryService().streamHistoricalLogs(uuid, days, entry -> {
            if (check != null && (entry.getCheck() == null || !entry.getCheck().equalsIgnoreCase(check))) return false;
            if (setback && (entry.getFrom() == null || entry.getTo() == null)) return false;
            return true;
        }, results -> {
            if (results.isEmpty()) {
                I18n.sendPrefixed(sender, "command.history.empty");
            } else {
                renderLogEntries(sender, I18n.get(sender, "command.logs.title-history", "count", results.size()), results, page, 8);
            }
        });
    }

    private void handleExport(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.export.usage", "label", label);
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        I18n.sendPrefixed(sender, "command.export.exporting", "player", targetName);
        plugin.getLogQueryService().exportPlayerData(targetName, uuid, file -> {
            if (file != null && file.exists()) {
                I18n.sendPrefixed(sender, "command.export.success", "path", file.getAbsolutePath(), "count", file.length());
                recordAdminAction(sender, "EXPORT", targetName, "SUCCESS");
            } else {
                I18n.sendPrefixed(sender, "command.export.failed");
                recordAdminAction(sender, "EXPORT", targetName, "FAILED");
            }
        });
    }

    private void handleTop(CommandSender sender, String label, String[] args) {
        long sinceMs = parseDuration(parseParam(args, "--since"));
        if (sinceMs <= 0) sinceMs = 24 * 60 * 60 * 1000L;

        plugin.getLogQueryService().queryTopViolators(sinceMs, list -> {
            I18n.send(sender, "command.top.header");
            if (list.isEmpty()) {
                I18n.send(sender, "command.top.empty");
                return;
            }
            int rank = 1;
            for (LogQueryService.TopRecord rec : list) {
                I18n.send(sender, "command.top.entry",
                        "rank", rank++,
                        "player", rec.player(),
                        "check", rec.mainCheck(),
                        "total_vl", rec.totalVl(),
                        "setbacks", rec.setbackCount());
            }
        });
    }

    private void handleStatus(CommandSender sender) {
        var adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        AuditLogManager logMgr = plugin.getAuditLogManager();
        long size = logMgr.getCurrentFileSize();
        int backlog = logMgr.getQueueBacklog();
        int cap = logMgr.getQueueCapacity();
        long dropped = logMgr.getDroppedCount();
        String hash = logMgr.getLatestHash();
        String flushTime = new SimpleDateFormat("HH:mm:ss").format(new Date(logMgr.getLastFlushTime()));

        I18n.send(sender, "command.status.header");
        I18n.send(sender, "command.status.env",
                "env", com.example.argusac.compat.ServerVersion.get().getFriendlyName(),
                "core", com.example.argusac.compat.ServerVersion.isPaper() ? "Paper" : "Spigot");
        I18n.send(sender, "command.status.adapter", "adapter", adapter.getClass().getSimpleName());

        com.example.argusac.compat.CompatibilityManager compatMgr = plugin.getCompatibilityManager();
        if (compatMgr.hasDegradedModules()) {
            I18n.send(sender, "command.status.degraded");
        } else {
            I18n.send(sender, "command.status.full");
        }

        String memStatus = backlog > cap * 0.5 
                ? I18n.get(sender, "command.status.memory-high") 
                : I18n.get(sender, "command.status.memory-healthy");
        I18n.send(sender, "command.status.queue", "queue_size", backlog, "capacity", cap, "memory_count", memStatus);
        I18n.send(sender, "command.status.chain", "chain_index", dropped, "hash", (hash != null ? hash.substring(0, Math.min(hash.length(), 16)) + "..." : "None"));
        I18n.send(sender, "command.status.archive", "archive_count", 0, "size", (size / 1024) + " KB");
        String droppedHint = dropped > 0 ? " " + I18n.get(sender, "command.status.dropped-hint", "dropped", dropped) : "";
        I18n.send(sender, "command.status.flush-time", "time", flushTime, "dropped", droppedHint);
    }

    private void handleVerify(CommandSender sender) {
        I18n.sendPrefixed(sender, "command.verify.running");
        plugin.getLogQueryService().verifyHashChainAsync(result -> {
            if (result.valid()) {
                I18n.sendPrefixed(sender, "command.verify.success");
            } else {
                I18n.sendPrefixed(sender, "command.verify.failed", "line", result.line());
            }
            recordAdminAction(sender, "VERIFY_HASH_CHAIN", "global", result.valid() ? "SUCCESS" : "FAILED");
        });
    }

    private void handleDebug(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.debug.usage", "label", label);
            return;
        }
        String check = args[1];
        boolean active = plugin.getCheckDebugLogger().toggle(check);
        String statusStr = active ? "<green>ON</green>" : "<red>OFF</red>";
        I18n.sendPrefixed(sender, "command.debug.result", "check", check, "status", statusStr);
        recordAdminAction(sender, "DEBUG_TOGGLE", check, active ? "ENABLED" : "DISABLED");
    }

    private void handleReplay(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.replay.usage", "label", label);
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
        boolean sideView = containsFlag(args, "--side");

        List<LogEntry> logs = plugin.getLogQueryService().queryMemoryLogs(uuid, null, 60_000L, false);
        if (logs.isEmpty()) {
            I18n.sendPrefixed(sender, "command.replay.empty");
            return;
        }

        LogEntry targetLog = logs.get(logs.size() - 1);
        List<String> rendered = replayRenderer.render(sender, targetLog, sideView);
        for (String line : rendered) {
            sender.sendMessage(ColorUtil.toComponent(line));
        }
    }

    private void handlePurge(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.purge.usage", "label", label);
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        plugin.getLogQueryService().purgePlayerData(targetName, uuid);
        I18n.sendPrefixed(sender, "command.purge.success", "player", targetName);
        recordAdminAction(sender, "PURGE_DATA", targetName, "SUCCESS");
    }

    private void handleToggle(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.toggle.usage", "label", label);
            return;
        }
        String checkName = args[1];
        Check check = plugin.getCheckManager().getCheck(checkName);
        if (check == null) {
            I18n.sendPrefixed(sender, "command.toggle.not-found", "check", checkName);
            return;
        }

        boolean newState = !check.isEnabled();
        check.setEnabled(newState);
        String statusStr = newState ? "<green>ENABLED</green>" : "<red>DISABLED</red>";
        I18n.sendPrefixed(sender, "command.toggle.result", "check", check.getName(), "status", statusStr);
        recordAdminAction(sender, "TOGGLE_CHECK", check.getName(), newState ? "ENABLED" : "DISABLED");
    }

    private void handleSetbackCommand(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            I18n.send(sender, "command.setback.usage", "label", label);
            return;
        }

        String targetStr = args[1];
        if ("global".equalsIgnoreCase(targetStr)) {
            if (args.length < 3) {
                I18n.send(sender, "command.setback.usage", "label", label);
                return;
            }
            if ("on".equalsIgnoreCase(args[2])) {
                plugin.getSetbackManager().setGlobalEnabled(true);
                I18n.sendPrefixed(sender, "command.setback.global-toggle", "status", "<green>ON</green>");
                recordAdminAction(sender, "SETBACK_GLOBAL", "global", "ON");
            } else if ("off".equalsIgnoreCase(args[2])) {
                plugin.getSetbackManager().setGlobalEnabled(false);
                I18n.sendPrefixed(sender, "command.setback.global-toggle", "status", "<red>OFF</red>");
                recordAdminAction(sender, "SETBACK_GLOBAL", "global", "OFF");
            } else {
                I18n.send(sender, "command.setback.param-error");
            }
            return;
        }

        Player target = Bukkit.getPlayer(targetStr);
        if (target == null) {
            I18n.sendPrefixed(sender, "system.target-not-found", "target", targetStr);
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(target);
        if (data == null) {
            I18n.send(sender, "command.setback.data-error");
            return;
        }

        if (args.length < 3) {
            I18n.send(sender, "command.setback.usage", "label", label);
            return;
        }

        String action = args[2].toLowerCase();
        switch (action) {
            case "disable" -> {
                data.setSetbackOverride(com.example.argusac.model.SetbackOverride.DISABLED);
                I18n.sendPrefixed(sender, "command.setback.override-set", "player", target.getName(), "override", "DISABLED");
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "DISABLED");
            }
            case "soft" -> {
                data.setSetbackOverride(com.example.argusac.model.SetbackOverride.SOFT_ONLY);
                I18n.sendPrefixed(sender, "command.setback.override-set", "player", target.getName(), "override", "SOFT_ONLY");
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "SOFT_ONLY");
            }
            case "hard" -> {
                data.setSetbackOverride(com.example.argusac.model.SetbackOverride.HARD_ONLY);
                I18n.sendPrefixed(sender, "command.setback.override-set", "player", target.getName(), "override", "HARD_ONLY");
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "HARD_ONLY");
            }
            case "default" -> {
                data.setSetbackOverride(com.example.argusac.model.SetbackOverride.DEFAULT);
                I18n.sendPrefixed(sender, "command.setback.override-set", "player", target.getName(), "override", "DEFAULT");
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "DEFAULT");
            }
            case "info" -> {
                I18n.send(sender, "command.setback.info-header", "player", target.getName());
                long immunity = Math.max(0, data.getSetbackImmunityUntil() - System.currentTimeMillis());
                I18n.send(sender, "command.setback.info-detail", "override", data.getSetbackOverride().name(), "immunity", immunity, "cooldown", plugin.getSetbackManager().isCheckOnCooldown(target.getUniqueId(), "Speed") ? "Active" : "None");
                com.example.argusac.model.LegitimateLocation last = data.getLastLegitimateLocation();
                I18n.send(sender, "command.setback.info-last", "last_loc", last != null ? last.toString() : "None");
            }
            case "now" -> {
                Check defaultCheck = plugin.getCheckManager().getSpeedCheck();
                boolean success = plugin.getSetbackManager().execute(
                        target, data, defaultCheck, data.getTotalVl(), "Admin forced setback", null);
                if (success) {
                    I18n.sendPrefixed(sender, "command.setback.triggered", "player", target.getName());
                    recordAdminAction(sender, "SETBACK_FORCE", target.getName(), "SUCCESS");
                } else {
                    I18n.send(sender, "command.setback.fail-blocked");
                    recordAdminAction(sender, "SETBACK_FORCE", target.getName(), "FAILED");
                }
            }
            default -> I18n.send(sender, "command.setback.usage", "label", label);
        }
    }

    private void recordAdminAction(CommandSender sender, String action, String target, String result) {
        String actor = sender.getName();
        UUID actorUuid = (sender instanceof Player p) ? p.getUniqueId() : null;
        LogEntry adminLog = new LogEntry.Builder(LogType.ADMIN, plugin.getAuditLogManager().getServerId())
                .actor(actor)
                .actorUuid(actorUuid)
                .action(action)
                .target(target)
                .result(result)
                .build();
        plugin.getAuditLogManager().submitLog(adminLog);
    }

    private void renderLogEntries(CommandSender sender, String title, List<LogEntry> logs, int page, int pageSize) {
        int total = logs.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
        int curPage = Math.min(Math.max(1, page), totalPages);

        I18n.send(sender, "command.logs.header", "title", title, "page", curPage, "total_pages", totalPages);

        int start = (curPage - 1) * pageSize;
        int end = Math.min(start + pageSize, total);
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

        for (int i = start; i < end; i++) {
            LogEntry e = logs.get(i);
            String timeStr = sdf.format(new Date(e.getTime()));

            String sevColor = "&a";
            if (e.getSeverity() == LogSeverity.MEDIUM) sevColor = "&e";
            else if (e.getSeverity() == LogSeverity.HIGH) sevColor = "&c";

            String line;
            if (e.getType() == LogType.VIOLATION || e.getType() == LogType.SETBACK_FAIL) {
                String coord = (e.getFrom() != null && e.getTo() != null)
                        ? " " + e.getFrom() + "→" + e.getTo()
                        : "";
                line = String.format("&8[%s] &b%s &7VL&c%d " + sevColor + "%s &3%s%s",
                        timeStr,
                        e.getCheck() != null ? e.getCheck() : "Unknown",
                        e.getVl() != null ? e.getVl() : 0,
                        e.getSeverity() != null ? e.getSeverity().name() : "LOW",
                        e.getMode() != null ? e.getMode() : "NONE",
                        coord);
            } else if (e.getType() == LogType.SESSION) {
                line = String.format("&8[%s] &6SESSION &7%s %s", timeStr, e.getSessionAction(), e.getExtra() != null ? e.getExtra() : "");
            } else if (e.getType() == LogType.ADMIN) {
                line = I18n.get(sender, "command.logs.admin-format",
                        "time", timeStr,
                        "actor", e.getActor(),
                        "action", e.getAction(),
                        "target", e.getTarget() != null ? e.getTarget() : "");
            } else {
                line = I18n.get(sender, "command.logs.integrity-format",
                        "time", timeStr,
                        "count", e.getLineCount());
            }

            sender.sendMessage(ColorUtil.toComponent(line));
        }
    }

    private String parseParam(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(flag)) return args[i + 1];
        }
        return null;
    }

    private int parseIntParam(String[] args, String flag, int def) {
        String p = parseParam(args, flag);
        if (p == null) return def;
        try { return Integer.parseInt(p); } catch (Exception e) { return def; }
    }

    private boolean containsFlag(String[] args, String flag) {
        for (String s : args) if (s.equalsIgnoreCase(flag)) return true;
        return false;
    }

    private long parseDuration(String str) {
        if (str == null || str.isEmpty()) return -1L;
        try {
            if (str.endsWith("h")) return Long.parseLong(str.substring(0, str.length() - 1)) * 3600_000L;
            if (str.endsWith("d")) return Long.parseLong(str.substring(0, str.length() - 1)) * 86400_000L;
            if (str.endsWith("m")) return Long.parseLong(str.substring(0, str.length() - 1)) * 60_000L;
        } catch (Exception ignored) {}
        return -1L;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(I18n.component(sender, "help.header"));
        for (Component entry : I18n.componentList(sender, "help.entries")) {
            sender.sendMessage(entry);
        }
        sender.sendMessage(I18n.component(sender, "help.footer"));
    }
}

