package com.example.argusac.command;

import com.example.argusac.ArgusACPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 命令补全器 (ArgusTabCompleter)
 * 完整覆盖重构后的所有子命令及其标志参数
 */
public class ArgusTabCompleter implements TabCompleter {

    private final ArgusACPlugin plugin;
    private final List<String> subCommands = Arrays.asList(
            "help", "reload", "vl", "logs", "history", "export",
            "top", "status", "verify", "debug", "replay", "my-data",
            "purge", "compat", "toggle", "setback", "alerts"
    );
    private final List<String> setbackActions = Arrays.asList("disable", "soft", "hard", "default", "now", "info");
    private final List<String> logFilterFlags = Arrays.asList("--check", "--since", "--setback", "--page");

    public ArgusTabCompleter(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("ArgusAC.admin")) {
            if (args.length == 1) {
                List<String> available = new ArrayList<>(Arrays.asList("help", "my-data"));
                if (sender.hasPermission("ArgusAC.alert")) {
                    available.add("alerts");
                }
                return available.stream()
                        .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (sender.hasPermission("ArgusAC.alert") && args.length == 2
                    && "alerts".equalsIgnoreCase(args[0])) {
                return Arrays.asList("on", "off", "status").stream()
                        .filter(action -> action.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return subCommands.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase();

        if (args.length == 2) {
            if ("alerts".equals(sub)) {
                return Arrays.asList("on", "off", "status").stream()
                        .filter(action -> action.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if ("vl".equals(sub) || "logs".equals(sub) || "history".equals(sub) ||
                "export".equals(sub) || "replay".equals(sub) || "purge".equals(sub)) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(p -> p.getName())
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if ("toggle".equals(sub) || "debug".equals(sub)) {
                return plugin.getCheckManager().getAllChecks().stream()
                        .map(c -> c.getName())
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }

            if ("top".equals(sub)) {
                return Collections.singletonList("--since");
            }

            if ("setback".equals(sub)) {
                List<String> list = new ArrayList<>();
                list.add("global");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    list.add(p.getName());
                }
                return list.stream()
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3) {
            if ("setback".equalsIgnoreCase(sub)) {
                List<String> actions = "global".equalsIgnoreCase(args[1]) ? Arrays.asList("on", "off") : setbackActions;
                return actions.stream()
                        .filter(act -> act.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if ("history".equalsIgnoreCase(sub)) {
                return Arrays.asList("1", "3", "7", "30");
            }
            if ("top".equalsIgnoreCase(sub) && "--since".equalsIgnoreCase(args[1])) {
                return Arrays.asList("1h", "6h", "24h", "7d");
            }
            if ("replay".equalsIgnoreCase(sub)) {
                return Collections.singletonList("--side");
            }
            if ("logs".equalsIgnoreCase(sub)) {
                return logFilterFlags.stream()
                        .filter(f -> f.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length > 3 && ("logs".equalsIgnoreCase(sub) || "history".equalsIgnoreCase(sub))) {
            String prev = args[args.length - 2].toLowerCase();
            if ("--check".equals(prev)) {
                return plugin.getCheckManager().getAllChecks().stream()
                        .map(c -> c.getName())
                        .filter(name -> name.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if ("--since".equals(prev)) {
                return Arrays.asList("1h", "24h", "7d");
            }
            return logFilterFlags.stream()
                    .filter(f -> f.toLowerCase().startsWith(args[args.length - 1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}
