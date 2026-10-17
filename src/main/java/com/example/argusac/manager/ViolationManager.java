package com.example.argusac.manager;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.data.PlayerData;
import com.example.argusac.model.ViolationLog;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * 违规事务综合调度管理器 (ViolationManager)
 * 统一处理违规判定、VL增加、衰减任务、回退执行、警报分发及惩罚执行
 */
public class ViolationManager {

    private final ArgusACPlugin plugin;
    private BukkitTask decayTask;

    public ViolationManager(ArgusACPlugin plugin) {
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

        // 1. 拆分回退网络校正豁免与全局检测盲区：仅在移动检测项处于回退坐标校正时豁免位移累加，并记录豁免日志
        if (check.getType() == com.example.argusac.api.CheckType.MOVEMENT && data.hasLegalVelocity(com.example.argusac.model.VelocitySourceType.SETBACK_CORRECTION)) {
            com.example.argusac.log.model.LogEntry graceEntry = new com.example.argusac.log.model.LogEntry.Builder(
                    com.example.argusac.log.model.LogType.VIOLATION,
                    plugin.getAuditLogManager().getServerId()
            )
                    .player(player.getName())
                    .uuid(player.getUniqueId())
                    .check(check.getName())
                    .vl(data.getVl(check.getName()))
                    .severity(com.example.argusac.log.model.LogSeverity.LOW)
                    .reason("[GRACE_EXEMPTED] " + details)
                    .mode("GRACE_EXEMPTED")
                    .from(com.example.argusac.log.model.CoordPoint.fromLocation(player.getLocation(), false))
                    .build();
            plugin.getAuditLogManager().submitLog(graceEntry);
            return;
        }

        // 2. 累加违规等级
        int currentVl = data.addVl(check.getName(), vlAmount);

        // 3. 区分检测类别进行处置：
        // 移动违规 (MOVEMENT)：调用 SetbackManager 执行回退或原地取消
        // 战斗/方块违规 (COMBAT/WORLD)：由各 Check 取消非法交互事件，此处仅记录日志并触发升级惩戒，绝不传送
        boolean defaultPunishmentTriggered = false;
        String logMode = "LOG_ONLY";

        if (check.getType() == com.example.argusac.api.CheckType.MOVEMENT) {
            if (plugin.getSetbackManager().shouldSetback(player, check, currentVl)) {
                defaultPunishmentTriggered = plugin.getSetbackManager().mitigate(
                        player, check, currentVl, details, moveEvent
                );
            } else if (plugin.getSetbackManager().isCheckOnCooldown(player.getUniqueId(), check.getName())) {
                // P2 改法：处于回退冷却期间连续违规，若存在移动事件，优先取消当前非法移动，防止作弊者借冷却缝隙位移
                if (moveEvent != null && moveEvent.getFrom() != null) {
                    moveEvent.setTo(moveEvent.getFrom());
                    defaultPunishmentTriggered = true;
                    logMode = "COOLDOWN_CANCEL";
                } else {
                    logMode = "COOLDOWN_SKIP";
                }
            }
        } else {
            // 战斗、方块及数据包类违规，处置方式为取消非法交互并记录日志
            logMode = "CANCEL_INTERACTION";
        }

        // 4. 若未被 SetbackManager 内部日志覆盖，在此记录统一结构化违规日志
        if (!defaultPunishmentTriggered) {
            com.example.argusac.log.model.LogSeverity severity = (currentVl >= check.getMaxVl())
                    ? com.example.argusac.log.model.LogSeverity.HIGH
                    : (currentVl > check.getMaxVl() / 2 ? com.example.argusac.log.model.LogSeverity.MEDIUM : com.example.argusac.log.model.LogSeverity.LOW);

            com.example.argusac.log.model.LogEntry entry = new com.example.argusac.log.model.LogEntry.Builder(
                    com.example.argusac.log.model.LogType.VIOLATION,
                    plugin.getAuditLogManager().getServerId()
            )
                    .player(player.getName())
                    .uuid(player.getUniqueId())
                    .check(check.getName())
                    .vl(currentVl)
                    .severity(severity)
                    .reason(details)
                    .mode(logMode)
                    .from(com.example.argusac.log.model.CoordPoint.fromLocation(player.getLocation(), false))
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
                    logMode,
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
