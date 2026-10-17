package com.example.argusac.manager;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;
import java.util.logging.Level;

/**
 * 惩罚调度管理器 (PunishmentManager)
 * <p>
 * 【惩罚分级体系】：
 * 1. 默认处罚手段 (Tier 1 Default Punishment)：回退 (Setback) 机制。
 *    作为反作弊系统第一道防线，默认对所有确认违规立即执行回退与动量撤销，阻止任何非法收益。
 * 2. 升级惩罚手段 (Tier 2 Escalated Punishment)：控制台指令 (Kick/Ban)。
 *    当玩家在经历默认回退处罚后仍恶意持续作弊、VL 达到高阈值时，自动升级执行指令惩戒。
 */
public class PunishmentManager {

    private final ArgusACPlugin plugin;
    private boolean enabled;
    private boolean setbackAsDefault;
    private int defaultActionMinVl;
    private final List<PunishmentRule> rules = new ArrayList<>();

    public PunishmentManager(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.reloadConfig();
    }

    public void reloadConfig() {
        rules.clear();
        FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("punishments.enabled", true);
        this.setbackAsDefault = config.getBoolean("punishments.default-action.setback-as-default", true);
        this.defaultActionMinVl = config.getInt("punishments.default-action.min-vl", 1);

        List<Map<?, ?>> actionList = config.getMapList("punishments.actions");
        for (Map<?, ?> item : actionList) {
            try {
                Object vlObj = item.get("vl");
                Object cmdObj = item.get("command");
                if (vlObj != null && cmdObj != null) {
                    int vl = Integer.parseInt(vlObj.toString());
                    String cmd = cmdObj.toString();
                    rules.add(new PunishmentRule(vl, cmd));
                }
            } catch (Exception e) {
                plugin.getLogger().warning("解析惩罚规则失败: " + item);
            }
        }
        // 按 VL 从小到大排序
        rules.sort(Comparator.comparingInt(r -> r.getVlThreshold()));
    }

    /**
     * 执行默认处罚手段 (Default Punishment: Setback 回退机制)
     *
     * @param player 违规玩家
     * @param data 玩家运行时数据
     * @param check 触发的检测项
     * @param currentVl 当前违规等级
     * @param details 违规详情
     * @param moveEvent 移动事件 (可为 null)
     * @return 是否成功触发并执行了默认回退处罚
     */
    public boolean executeDefaultPunishment(Player player, PlayerData data, Check check,
                                           int currentVl, String details, PlayerMoveEvent moveEvent) {
        if (!setbackAsDefault || player == null || check == null) {
            return false;
        }

        SetbackManager setbackManager = plugin.getSetbackManager();
        if (setbackManager.shouldSetback(player, check, currentVl)) {
            return setbackManager.mitigate(player, check, currentVl, details, moveEvent);
        }
        return false;
    }

    /**
     * 校验玩家违规等级，并执行对应阶段的升级惩罚命令 (如 Kick/Ban)
     */
    public void checkAndPunish(Player player, String checkName, int currentVl) {
        if (!enabled || player == null || !player.isOnline()) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null) {
            return;
        }

        for (PunishmentRule rule : rules) {
            // 使用 >= 避免单次违规加分跳过临界值 (例如 19 -> 21) 导致惩罚失效
            if (currentVl >= rule.getVlThreshold() && data.getLastPunishedVl() < rule.getVlThreshold()) {
                data.setLastPunishedVl(rule.getVlThreshold());
                String cmd = rule.getCommand()
                        .replace("{player}", player.getName())
                        .replace("{check}", checkName)
                        .replace("{vl}", String.valueOf(currentVl));

                if (!Bukkit.isPrimaryThread()) {
                    Bukkit.getScheduler().runTask(plugin, () -> executeCommand(cmd, player.getName()));
                } else {
                    executeCommand(cmd, player.getName());
                }
            }
        }
    }

    private void executeCommand(String command, String playerName) {
        try {
            plugin.getLogger().log(Level.INFO, "[Punishment] 正在对玩家 {0} 执行自动升级惩罚命令: {1}",
                    new Object[]{playerName, command});
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "执行惩罚指令失败: " + command, ex);
        }
    }

    public boolean isSetbackAsDefault() {
        return setbackAsDefault;
    }

    public int getDefaultActionMinVl() {
        return defaultActionMinVl;
    }

    private static class PunishmentRule {
        private final int vlThreshold;
        private final String command;

        public PunishmentRule(int vlThreshold, String command) {
            this.vlThreshold = vlThreshold;
            this.command = command;
        }

        public int getVlThreshold() {
            return vlThreshold;
        }

        public String getCommand() {
            return command;
        }
    }
}
