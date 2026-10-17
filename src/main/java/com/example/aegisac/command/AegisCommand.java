package com.example.aegisac.command;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.Check;
import com.example.aegisac.data.PlayerData;
import com.example.aegisac.log.core.AuditLogManager;
import com.example.aegisac.log.model.LogEntry;
import com.example.aegisac.log.model.LogSeverity;
import com.example.aegisac.log.model.LogType;
import com.example.aegisac.log.query.LogQueryService;
import com.example.aegisac.log.replay.AsciiReplayRenderer;
import com.example.aegisac.util.ColorUtil;
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
 * AegisAC 主命令处理器 (/aegis, /ac)
 * 集成统一日志审计系统全部查询、验证、回放与管理命令
 */
public class AegisCommand implements CommandExecutor {

    private final AegisACPlugin plugin;
    private final AsciiReplayRenderer replayRenderer;

    public AegisCommand(AegisACPlugin plugin) {
        this.plugin = plugin;
        this.replayRenderer = new AsciiReplayRenderer();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = plugin.getConfig().getString("audit.prefix", "&8[&bAegis&3AC&8] &r");

        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();

        // 1. 玩家自身隐私与数据查询 (GDPR 个人透明度支持，无需 admin 权限)
        if ("my-data".equals(sub)) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ColorUtil.toComponent(prefix + "&c控制台不可调用此命令。"));
                return true;
            }
            int retention = plugin.getConfig().getInt("audit.archive.retention-days", 30);
            p.sendMessage(ColorUtil.toComponent(prefix + "&a您的数据由 AegisAC 统一合规引擎保护:"));
            p.sendMessage(ColorUtil.toComponent("&7- 日志保存周期: &e" + retention + " 天 (到期自动安全清理)"));
            p.sendMessage(ColorUtil.toComponent("&7- IP 匿名化记录: &e" + (plugin.getConfig().getBoolean("audit.privacy.hash-ip", false) ? "已脱敏哈希" : "未启用记录")));
            p.sendMessage(ColorUtil.toComponent("&7- 申请注销或删除数据请联系管理员执行 &e/aegis purge " + p.getName()));
            return true;
        }

        // 以下管理命令必须持有 aegisac.admin
        if (!sender.hasPermission("aegisac.admin")) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c权限不足！执行反作弊管理命令需要 &eaegisac.admin &c权限。"));
            return true;
        }

        switch (sub) {
            case "reload" -> {
                plugin.reloadAll();
                sender.sendMessage(ColorUtil.toComponent(prefix + "&a配置文件与所有检测模块已成功重载！"));
                recordAdminAction(sender, "RELOAD", "all", "SUCCESS");
            }
            case "vl" -> handleVl(sender, label, args, prefix);
            case "logs" -> handleLogs(sender, label, args, prefix);
            case "history" -> handleHistory(sender, label, args, prefix);
            case "export" -> handleExport(sender, label, args, prefix);
            case "top" -> handleTop(sender, label, args, prefix);
            case "status" -> handleStatus(sender, prefix);
            case "verify" -> handleVerify(sender, prefix);
            case "debug" -> handleDebug(sender, label, args, prefix);
            case "replay" -> handleReplay(sender, label, args, prefix);
            case "purge" -> handlePurge(sender, label, args, prefix);
            case "compat" -> handleCompat(sender, prefix);
            case "toggle" -> handleToggle(sender, label, args, prefix);
            case "setback" -> handleSetbackCommand(sender, label, args, prefix);
            default -> sender.sendMessage(ColorUtil.toComponent(prefix + "&c未知子命令，请输入 &e/" + label + " help &c查看帮助。"));
        }

        return true;
    }

    private void handleCompat(CommandSender sender, String prefix) {
        var adapter = com.example.aegisac.compat.VersionAdapterFactory.getAdapter();
        adapter.sendMessage(sender, prefix + "&e=== AegisAC 多版本运行能力诊断矩阵 ===");
        adapter.sendMessage(sender, String.format("&7当前服务器环境: &b%s &8(编码: &f%d&8) | 核心: &3%s",
                com.example.aegisac.compat.ServerVersion.get().getFriendlyName(),
                com.example.aegisac.compat.ServerVersion.getCode(),
                com.example.aegisac.compat.ServerVersion.isPaper() ? "Paper" : "Spigot"));
        adapter.sendMessage(sender, "&7当前适配器实现: &f" + com.example.aegisac.compat.VersionAdapterFactory.getAdapter().getClass().getSimpleName());
        adapter.sendMessage(sender, "&8--------------------------------------------------");

        com.example.aegisac.compat.CompatibilityManager compatMgr = plugin.getCompatibilityManager();
        compatMgr.getAllStatus().forEach((checkName, status) -> {
            String reason = compatMgr.getReason(checkName);
            adapter.sendMessage(sender, String.format(" &8- &b%-14s %s &8| &7%s",
                    checkName, status.getDisplay(), reason));
        });

        adapter.sendMessage(sender, "&8--------------------------------------------------");
        adapter.sendMessage(sender, "&aFULL: 满血防护  &eDEGRADED: 几何/旧版平替  &cDISABLED: 机制缺失静默挂起");
    }

    private void handleVl(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " vl <玩家名>"));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c找不到目标在线玩家: " + args[1]));
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(target);
        int ping = com.example.aegisac.compat.VersionAdapterFactory.getAdapter().getPing(target);
        sender.sendMessage(ColorUtil.toComponent(prefix + "&e=== 玩家 &b" + target.getName() + " &e当前违规信息 ==="));
        sender.sendMessage(ColorUtil.toComponent("&7延迟 (Ping): &f" + (ping >= 0 ? ping + "ms" : "未知") + " &8| &7总 VL: &c" + data.getTotalVl()));

        Map<String, Integer> vls = data.getAllVls();
        if (vls.isEmpty()) {
            sender.sendMessage(ColorUtil.toComponent("&a当前没有任何违规记录，良好！"));
        } else {
            vls.forEach((chk, vl) -> {
                Check c = plugin.getCheckManager().getCheck(chk);
                int max = c != null ? c.getMaxVl() : 15;
                sender.sendMessage(ColorUtil.toComponent("  &8- &7" + chk + ": &c" + vl + "&8/&7" + max));
            });
        }
    }

    private void handleLogs(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " logs <玩家名> [--check <name>] [--since <1h|24h|7d>] [--setback] [--page <n>]"));
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
            sender.sendMessage(ColorUtil.toComponent(prefix + "&7玩家 &e" + targetName + " &7在内存快速缓冲中无匹配记录。"));
            return;
        }

        renderLogEntries(sender, prefix, "内存快速日志 (近 " + logs.size() + " 条)", logs, page, 8);
    }

    private void handleHistory(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 3) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " history <玩家名> <查询天数> [--check <name>] [--setback] [--page <n>]"));
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

        sender.sendMessage(ColorUtil.toComponent(prefix + "&7正在异步流式检索历史日志档案 (含 .jsonl.gz)..."));
        plugin.getLogQueryService().streamHistoricalLogs(uuid, days, entry -> {
            if (check != null && (entry.getCheck() == null || !entry.getCheck().equalsIgnoreCase(check))) return false;
            if (setback && (entry.getFrom() == null || entry.getTo() == null)) return false;
            return true;
        }, results -> {
            if (results.isEmpty()) {
                sender.sendMessage(ColorUtil.toComponent(prefix + "&7未在历史日志归档中找到匹配记录。"));
            } else {
                renderLogEntries(sender, prefix, "历史审计档案 (共 " + results.size() + " 条)", results, page, 8);
            }
        });
    }

    private void handleExport(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " export <玩家名>"));
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        sender.sendMessage(ColorUtil.toComponent(prefix + "&7正在导出玩家 &e" + targetName + " &7的全部审计日志..."));
        plugin.getLogQueryService().exportPlayerData(targetName, uuid, file -> {
            if (file != null && file.exists()) {
                sender.sendMessage(ColorUtil.toComponent(prefix + "&a导出完成！文件存放路径: &f" + file.getAbsolutePath()));
                recordAdminAction(sender, "EXPORT", targetName, "SUCCESS");
            } else {
                sender.sendMessage(ColorUtil.toComponent(prefix + "&c导出日志失败，请查看控制台。"));
                recordAdminAction(sender, "EXPORT", targetName, "FAILED");
            }
        });
    }

    private void handleTop(CommandSender sender, String label, String[] args, String prefix) {
        long sinceMs = parseDuration(parseParam(args, "--since"));
        if (sinceMs <= 0) sinceMs = 24 * 60 * 60 * 1000L; // 默认 24h

        sender.sendMessage(ColorUtil.toComponent(prefix + "&7正在聚合分析违规 Top10 榜单..."));
        long hours = sinceMs / 3600000L;
        plugin.getLogQueryService().queryTopViolators(sinceMs, list -> {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&e=== AegisAC 违规 Top10 (最近 " + hours + " 小时) ==="));
            if (list.isEmpty()) {
                sender.sendMessage(ColorUtil.toComponent("&a当前周期内无任何玩家发生违规记录！"));
                return;
            }
            int rank = 1;
            for (LogQueryService.TopRecord rec : list) {
                sender.sendMessage(ColorUtil.toComponent(String.format(
                        "&8[&6#%d&8] &b%-16s &7主要检测: &e%-10s &7总VL: &c%-4d &7回退拦截: &6%d 次",
                        rank++, rec.player(), rec.mainCheck(), rec.totalVl(), rec.setbackCount()
                )));
            }
        });
    }

    private void handleStatus(CommandSender sender, String prefix) {
        var adapter = com.example.aegisac.compat.VersionAdapterFactory.getAdapter();
        AuditLogManager logMgr = plugin.getAuditLogManager();
        long size = logMgr.getCurrentFileSize();
        int backlog = logMgr.getQueueBacklog();
        int cap = logMgr.getQueueCapacity();
        long dropped = logMgr.getDroppedCount();
        String hash = logMgr.getLatestHash();
        String flushTime = new SimpleDateFormat("HH:mm:ss").format(new Date(logMgr.getLastFlushTime()));

        adapter.sendMessage(sender, prefix + "&e=== AegisAC 运行状态与审计看板 ===");
        adapter.sendMessage(sender, "&7运行内核环境: &b" + com.example.aegisac.compat.ServerVersion.get().getFriendlyName() + " &8(" + (com.example.aegisac.compat.ServerVersion.isPaper() ? "Paper" : "Spigot") + ")");
        adapter.sendMessage(sender, "&7活跃适配实现: &f" + adapter.getClass().getSimpleName());

        com.example.aegisac.compat.CompatibilityManager compatMgr = plugin.getCompatibilityManager();
        if (compatMgr.hasDegradedModules()) {
            adapter.sendMessage(sender, "&7版本降级状态: &e存在降级/挂起模块 &8(输入 &f/aegis compat &8查看)");
        } else {
            adapter.sendMessage(sender, "&7版本降级状态: &a全模块完整原生支持 (Full Performance)");
        }

        adapter.sendMessage(sender, "&7今日日志文件大小: &f" + (size / 1024) + " KB");
        adapter.sendMessage(sender, "&7异步落盘队列积压: &e" + backlog + " &7/ &f" + cap + (backlog > cap * 0.5 ? " &c[积压偏高!]" : " &a[健康]"));
        adapter.sendMessage(sender, "&7背压抛弃日志总数: " + (dropped > 0 ? "&c" + dropped : "&a0"));
        adapter.sendMessage(sender, "&7最近一次写盘 Flush: &f" + flushTime);
        adapter.sendMessage(sender, "&7最新哈希链校验点: &b" + (hash != null ? hash.substring(0, Math.min(hash.length(), 16)) + "..." : "无"));
    }

    private void handleVerify(CommandSender sender, String prefix) {
        sender.sendMessage(ColorUtil.toComponent(prefix + "&7正在异步核验全局日志链 SHA-256 防篡改签名..."));
        plugin.getLogQueryService().verifyHashChainAsync(result -> {
            if (result.valid()) {
                sender.sendMessage(ColorUtil.toComponent(prefix + "&a哈希链防篡改校验通过！共核验 &e" + result.line() + " &a行日志，数据完整无破坏。"));
            } else {
                String alert = prefix + "&4[严重篡改警告] 日志哈希链断裂！在文件 &e" + result.filename() + " &4第 &c" + result.line() + " &4行检测到哈希不匹配！";
                sender.sendMessage(ColorUtil.toComponent(alert));
                Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("aegisac.alert"))
                        .forEach(p -> p.sendMessage(ColorUtil.toComponent(alert)));
            }
            recordAdminAction(sender, "VERIFY_HASH_CHAIN", "global", result.valid() ? "SUCCESS" : "FAILED");
        });
    }

    private void handleDebug(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " debug <检测模块名>"));
            return;
        }
        String check = args[1];
        boolean active = plugin.getCheckDebugLogger().toggle(check);
        sender.sendMessage(ColorUtil.toComponent(prefix + "&7检测项 &b" + check + " &7独立调试日志: " + (active ? "&a已开启 (独立文件落盘)" : "&c已关闭")));
        recordAdminAction(sender, "DEBUG_TOGGLE", check, active ? "ENABLED" : "DISABLED");
    }

    private void handleReplay(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " replay <玩家名> [--side]"));
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();
        boolean sideView = containsFlag(args, "--side");

        List<LogEntry> memory = plugin.getLogQueryService().queryMemoryLogs(uuid, null, 0, false);
        LogEntry lastViolation = null;
        for (int i = memory.size() - 1; i >= 0; i--) {
            LogEntry e = memory.get(i);
            if (e.getType() == LogType.VIOLATION && e.getFrom() != null && e.getTo() != null) {
                lastViolation = e;
                break;
            }
        }

        if (lastViolation == null) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c未能在内存缓冲中找到玩家 &e" + targetName + " &c有效回退位移记录。"));
            return;
        }

        List<String> rendered = replayRenderer.render(lastViolation, sideView);
        for (String line : rendered) {
            sender.sendMessage(ColorUtil.toComponent(line));
        }
    }

    private void handlePurge(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " purge <玩家名>"));
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(targetName).getUniqueId();

        plugin.getAuditLogManager().removeMemoryBuffer(uuid);
        recordAdminAction(sender, "PURGE_GDPR", targetName, "SUCCESS");

        sender.sendMessage(ColorUtil.toComponent(prefix + "&a已安全清除玩家 &e" + targetName + " &a的内存审计索引，并生成 GDPR 审计注销备案。"));
    }

    private void handleToggle(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " toggle <检测项名>"));
            return;
        }
        Check check = plugin.getCheckManager().getCheck(args[1]);
        if (check == null) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c未找到该检测模块: " + args[1]));
            return;
        }
        boolean newState = !check.isEnabled();
        check.setEnabled(newState);
        sender.sendMessage(ColorUtil.toComponent(prefix + "&7检测项 &b" + check.getName() + " &7已" + (newState ? "&a开启" : "&c禁用")));
        recordAdminAction(sender, "TOGGLE_CHECK", check.getName(), newState ? "ENABLED" : "DISABLED");
    }

    private void handleSetbackCommand(CommandSender sender, String label, String[] args, String prefix) {
        if (args.length < 2) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c用法: /" + label + " setback <玩家名/global> <disable|soft|hard|default|now|info>"));
            return;
        }

        String targetStr = args[1];

        if ("global".equalsIgnoreCase(targetStr)) {
            if (args.length < 3) {
                boolean cur = plugin.getSetbackManager().isGlobalEnabled();
                sender.sendMessage(ColorUtil.toComponent(prefix + "&7全局回退机制当前状态: " + (cur ? "&a已开启" : "&c已关闭")));
                return;
            }
            if ("on".equalsIgnoreCase(args[2])) {
                plugin.getSetbackManager().setGlobalEnabled(true);
                sender.sendMessage(ColorUtil.toComponent(prefix + "&a全局状态回退 (Setback) 机制已开启！"));
                recordAdminAction(sender, "SETBACK_GLOBAL", "global", "ON");
            } else if ("off".equalsIgnoreCase(args[2])) {
                plugin.getSetbackManager().setGlobalEnabled(false);
                sender.sendMessage(ColorUtil.toComponent(prefix + "&c全局状态回退 (Setback) 机制已关闭！"));
                recordAdminAction(sender, "SETBACK_GLOBAL", "global", "OFF");
            } else {
                sender.sendMessage(ColorUtil.toComponent(prefix + "&c参数错误，请使用 on 或 off"));
            }
            return;
        }

        Player target = Bukkit.getPlayer(targetStr);
        if (target == null) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c找不到目标在线玩家: " + targetStr));
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(target);
        if (data == null) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c无法获取该玩家运行时数据。"));
            return;
        }

        if (args.length < 3) {
            sender.sendMessage(ColorUtil.toComponent(prefix + "&c请指定操作: disable / soft / hard / default / now / info"));
            return;
        }

        String action = args[2].toLowerCase();
        switch (action) {
            case "disable" -> {
                data.setSetbackOverride(com.example.aegisac.model.SetbackOverride.DISABLED);
                sender.sendMessage(ColorUtil.toComponent(prefix + "&c已完全禁用玩家 &b" + target.getName() + " &c的回退机制。"));
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "DISABLED");
            }
            case "soft" -> {
                data.setSetbackOverride(com.example.aegisac.model.SetbackOverride.SOFT_ONLY);
                sender.sendMessage(ColorUtil.toComponent(prefix + "&e已设置玩家 &b" + target.getName() + " &e仅允许 SOFT 原地撤销模式。"));
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "SOFT_ONLY");
            }
            case "hard" -> {
                data.setSetbackOverride(com.example.aegisac.model.SetbackOverride.HARD_ONLY);
                sender.sendMessage(ColorUtil.toComponent(prefix + "&6已设置玩家 &b" + target.getName() + " &6仅允许 HARD 强制传送模式。"));
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "HARD_ONLY");
            }
            case "default" -> {
                data.setSetbackOverride(com.example.aegisac.model.SetbackOverride.DEFAULT);
                sender.sendMessage(ColorUtil.toComponent(prefix + "&a已恢复玩家 &b" + target.getName() + " &a的回退策略为默认继承配置。"));
                recordAdminAction(sender, "SETBACK_OVERRIDE", target.getName(), "DEFAULT");
            }
            case "info" -> {
                sender.sendMessage(ColorUtil.toComponent(prefix + "&e=== 玩家 &b" + target.getName() + " &e回退状态诊断 ==="));
                sender.sendMessage(ColorUtil.toComponent("&7个人覆写状态: &f" + data.getSetbackOverride()));
                sender.sendMessage(ColorUtil.toComponent("&7回退自免疫状态: " + (data.isSetbackImmune() ? "&a生效中 (剩余 " + (data.getSetbackImmunityUntil() - System.currentTimeMillis()) + "ms)" : "&7未激活")));
                com.example.aegisac.model.LegitimateLocation last = data.getLastLegitimateLocation();
                com.example.aegisac.model.LegitimateLocation prev = data.getPreviousLegitimateLocation();
                sender.sendMessage(ColorUtil.toComponent("&7最后合法位置: &b" + (last != null ? last.toString() : "&c未记录")));
                sender.sendMessage(ColorUtil.toComponent("&7前序备用位置: &3" + (prev != null ? prev.toString() : "&c无")));
            }
            case "now" -> {
                Check defaultCheck = plugin.getCheckManager().getSpeedCheck();
                boolean success = plugin.getSetbackManager().execute(
                        target, data, defaultCheck, data.getTotalVl(), "Admin forced setback", null);
                if (success) {
                    sender.sendMessage(ColorUtil.toComponent(prefix + "&a已立即将玩家 &b" + target.getName() + " &a回退到最后合法位置！"));
                    recordAdminAction(sender, "SETBACK_FORCE", target.getName(), "SUCCESS");
                } else {
                    sender.sendMessage(ColorUtil.toComponent(prefix + "&c回退失败，该玩家暂无可用合法位置或目标已被阻挡。"));
                    recordAdminAction(sender, "SETBACK_FORCE", target.getName(), "FAILED");
                }
            }
            default -> sender.sendMessage(ColorUtil.toComponent(prefix + "&c未知操作，有效选项: disable, soft, hard, default, now, info"));
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

    private void renderLogEntries(CommandSender sender, String prefix, String title, List<LogEntry> logs, int page, int pageSize) {
        int total = logs.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
        int curPage = Math.min(Math.max(1, page), totalPages);

        sender.sendMessage(ColorUtil.toComponent(prefix + "&e=== " + title + " (第 " + curPage + "/" + totalPages + " 页) ==="));

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
                line = String.format("&8[%s] &dADMIN &7%s 执行 &e%s &7目标:&b%s", timeStr, e.getActor(), e.getAction(), e.getTarget());
            } else {
                line = String.format("&8[%s] &5INTEGRITY &7行数:&f%d", timeStr, e.getLineCount());
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
        sender.sendMessage(ColorUtil.toComponent("&b====== &3AegisAC (神盾反作弊) 帮助指南 &b======"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " help &7- 查看反作弊命令列表"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " reload &7- 热重载配置文件与检测模块"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " vl <玩家> &7- 查看指定玩家实时违规数值 (VL)"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " logs <玩家> [--check <n>] [--since <1h|24h|7d>] [--setback] &7- 审查内存快速日志"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " history <玩家> <天数> &7- 异步流式检索历史日志文件 (含压缩包)"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " replay <玩家> [--side] &7- ASCII 轻量轨迹与高度可视化"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " export <玩家> &7- 导出该玩家完整审计数据 Zip"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " top [--since <1h|24h|7d>] &7- 违规次数 Top10 榜单"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " status &7- 查看队列积压、文件大小及最新哈希"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " verify &7- 异步核验全局日志链 SHA-256 签名完整性"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " debug <检测项> &7- 开关独立模块调试日志"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " my-data &7- 查看自身数据保留策略 (GDPR)"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " purge <玩家> &7- 删除玩家数据记录并生成注销审计"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " compat &7- 查看多版本跨环境运行能力矩阵与降级状态"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " toggle <检测项> &7- 实时开关单项反作弊检测"));
        sender.sendMessage(ColorUtil.toComponent("&e/" + label + " setback <玩家|global> <disable|soft|hard|default|now|info> &7- 运维控制与诊断回退"));
        sender.sendMessage(ColorUtil.toComponent("&7别名: &f/ac"));
    }
}
