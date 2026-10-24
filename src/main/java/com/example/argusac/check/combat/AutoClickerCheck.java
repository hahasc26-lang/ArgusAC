package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.combat.CombatVerdict;
import com.example.argusac.data.PlayerData;
import com.example.argusac.util.MathUtil;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 自动连点器检测 (AutoClickerCheck)
 * 监控每秒点击数 (CPS) 以及点击间隔时间差的标准差 (Standard Deviation)
 * 精确识别人类难以达到的超高频连点与脚本宏极其均匀的无抖动点击
 */
public class AutoClickerCheck extends AbstractCheck {

    private int maxCps;
    private double minStdDeviation;

    public AutoClickerCheck(ArgusACPlugin plugin) {
        super(plugin, "AutoClicker", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxCps = getChecksConfig().getInt(path + "max-cps", 18);
        this.minStdDeviation = getChecksConfig().getDouble(path + "min-std-deviation", 4.5);
    }

    /**
     * 在玩家左键点击交互或攻击实体时记录点击频率
     */
    public void handleClick(Player player) {
        evaluateClick(player);
    }

    /**
     * 协议级点击频率与标准差评估 (支持返回分级裁决结果供 Packet 拦截器使用)
     */
    public CombatVerdict evaluateClick(Player player) {
        if (!enabled || player == null) {
            return CombatVerdict.pass();
        }

        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return CombatVerdict.pass();
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return CombatVerdict.pass();
        }

        long now = System.currentTimeMillis();

        // 核心过滤：若玩家正在挖掘方块（或近期 400ms 内处于挖掘状态），
        // 客户端长按左键每 tick 均会发出手臂挥舞包 (Animation)，严格豁免 Combat 连点检测并清空队列
        if (data.isDigging() || (now - data.getLastDiggingTime() < 400L)) {
            data.getClickTimestamps().clear();
            return CombatVerdict.pass();
        }

        // 过滤单次物理点击由 Bukkit 同时触发 Interact 与 Damage 事件导致的短时间 (<30ms) 重复计数
        if (now - data.getLastClickTimestamp() < 30L) {
            return CombatVerdict.pass();
        }
        data.setLastClickTimestamp(now);

        Deque<Long> clicks = data.getClickTimestamps();
        clicks.addLast(now);

        // 清理 1000 毫秒 (1秒) 之前的过期点击
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) {
            clicks.pollFirst();
        }

        int currentCps = clicks.size();

        // 1. 超过最大 CPS 极限阈值
        if (currentCps > maxCps) {
            String detail = String.format("High CPS=%d, max=%d", currentCps, maxCps);
            if (currentCps <= maxCps + 2) {
                return CombatVerdict.lowConfidenceDrop(this, detail);
            }
            flag(player, 1, detail);
            return CombatVerdict.highConfidence(this, 1, detail);
        }

        // 2. 采样 12 次以上点击，计算点击时间间隔的一致性 (宏连点器检测)
        if (clicks.size() >= 12) {
            List<Long> timeList = new ArrayList<>(clicks);
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < timeList.size(); i++) {
                intervals.add(timeList.get(i) - timeList.get(i - 1));
            }

            double stdDev = MathUtil.getStandardDeviation(intervals);
            // CPS > 12 且时间间隔几乎完全机械一致 (方差过小)
            if (currentCps > 12 && stdDev < minStdDeviation) {
                String detail = String.format("Robot Click CPS=%d, stdDev=%.2f", currentCps, stdDev);
                flag(player, 1, detail);
                return CombatVerdict.highConfidence(this, 1, detail);
            }
        }
        return CombatVerdict.pass();
    }
}
