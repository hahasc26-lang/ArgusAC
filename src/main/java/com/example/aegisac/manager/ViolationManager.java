package com.example.aegisac.manager;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.Check;
import com.example.aegisac.data.PlayerData;
import com.example.aegisac.model.ViolationLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * 违规事务综合调度管理器 (ViolationManager)
 * 统一处理违规判定、VL增加、衰减任务、回退执行、警报分发及惩罚执行
 */
public class ViolationManager {

    private final AegisACPlugin plugin;
    private BukkitTask decayTask;

    public ViolationManager(AegisACPlugin plugin) {
        this.plugin = plugin;
        this.startDecayTask();
    }

    /**
     * 常规违规处理入口 (无 MoveEvent 上下文)
     */
    public void handleViolation(Player player, Check check, int vlAmount, String details) {
        handleViolation(player, check, vlAmount, details, null);
    }

    /**
     * 核心违规处理方法 (支持传入 PlayerMoveEvent 用于 SOFT 模式精准取消)
     *
     * @param player 违规玩家
     * @param check 触发的检测模块
     * @param vlAmount 增加的 VL 点数
     * @param details 违规细节数据
     * @param moveEvent 移动事件 (可为 null)
     */
    public void handleViolation(Player player, Check check, int vlAmount, String details, PlayerMoveEvent moveEvent) {
        if (player == null || check == null) {
            return;
        }

        // 检查 bypass 权限及创造模式全局豁免
        if (plugin.isPlayerExempt(player)) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null) {
            return;
        }

        // 若当前处于回退自免疫期，直接忽略，不累加 VL
        if (data.isSetbackImmune()) {
            return;
        }

        // 1. 累加违规等级
        int currentVl = data.addVl(check.getName(), vlAmount);

        // 2. 执行默认处罚手段 (Tier 1 Default Mitigation: 回退 Setback)
        // 回退作为反作弊系统默认第一防线与处罚手段，直接撤销异常移动或操作，拉回最后合法位置
        boolean defaultPunishmentTriggered = false;
        if (plugin.getSetbackManager().shouldSetback(player, check, currentVl)) {
            defaultPunishmentTriggered = plugin.getSetbackManager().mitigate(
                    player, check, currentVl, details, moveEvent
            );
        }

        // 3. 若默认回退处罚未触发 (如处于回退冷却或单人关闭)，在此记录普通违规日志 (若已触发回退，SetbackManager 内部会写入带回退坐标的日志)
        if (!defaultPunishmentTriggered) {
            com.example.aegisac.log.model.LogSeverity severity = (currentVl >= check.getMaxVl())
                    ? com.example.aegisac.log.model.LogSeverity.HIGH
                    : (currentVl > check.getMaxVl() / 2 ? com.example.aegisac.log.model.LogSeverity.MEDIUM : com.example.aegisac.log.model.LogSeverity.LOW);

            com.example.aegisac.log.model.LogEntry entry = new com.example.aegisac.log.model.LogEntry.Builder(
                    com.example.aegisac.log.model.LogType.VIOLATION,
                    plugin.getAuditLogManager().getServerId()
            )
                    .player(player.getName())
                    .uuid(player.getUniqueId())
                    .check(check.getName())
                    .vl(currentVl)
                    .severity(severity)
                    .reason(details)
                    .mode("LOG_ONLY")
                    .from(com.example.aegisac.log.model.CoordPoint.fromLocation(player.getLocation(), false))
                    .build();

            plugin.getAuditLogManager().submitLog(entry);

            // 兼顾向后兼容内存缓存
            int maxLogs = plugin.getConfig().getInt("settings.logs.max-per-player", 50);
            ViolationLog log = new ViolationLog(
                    player.getName(),
                    player.getUniqueId(),
                    check.getName(),
                    currentVl,
                    details,
                    false,
                    "NONE",
                    "-",
                    "-",
                    false
            );
            data.addLog(log, maxLogs);
        }

        // 若开启临时模块调试，写入独立调试日志
        plugin.getCheckDebugLogger().log(check.getName(), player.getName(), "VL:" + currentVl + " " + details);

        // 4. 发送管理员警报
        plugin.getAlertManager().sendAlert(player, check.getName(), currentVl, check.getMaxVl(), details);

        // 5. 校验执行二级升级惩罚 (Tier 2 Escalated Punishment: 严重恶意作弊指令惩罚，如 Kick/Ban)
        plugin.getPunishmentManager().checkAndPunish(player, check.getName(), currentVl);
    }

    /**
     * 启动 VL 周期性自动衰减任务
     */
    public void startDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
        }

        int intervalSeconds = plugin.getConfig().getInt("settings.vl-decay.interval-seconds", 15);
        int amount = plugin.getConfig().getInt("settings.vl-decay.amount", 1);
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);

        decayTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (PlayerData data : plugin.getPlayerManager().getAllData()) {
                data.decayVl(amount);
            }
        }, intervalTicks, intervalTicks);
    }

    public void stopDecayTask() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }
}
