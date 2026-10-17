package com.example.aegisac.check.combat;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.CheckType;
import com.example.aegisac.check.AbstractCheck;
import com.example.aegisac.data.PlayerData;
import com.example.aegisac.util.MathUtil;
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

    public AutoClickerCheck(AegisACPlugin plugin) {
        super(plugin, "AutoClicker", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxCps = plugin.getConfig().getInt(path + "max-cps", 18);
        this.minStdDeviation = plugin.getConfig().getDouble(path + "min-std-deviation", 4.5);
    }

    /**
     * 在玩家左键点击交互或攻击实体时记录点击频率
     */
    public void handleClick(Player player) {
        if (!enabled || player == null) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        long now = System.currentTimeMillis();

        // 过滤单次物理点击由 Bukkit 同时触发 Interact 与 Damage 事件导致的短时间 (<15ms) 重复计数
        if (now - data.getLastClickTimestamp() < 15L) {
            return;
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
            flag(player, 1, String.format("High CPS=%d, max=%d", currentCps, maxCps));
            return;
        }

        // 2. 采样 10 次以上点击，计算点击时间间隔的一致性 (宏连点器检测)
        if (clicks.size() >= 12) {
            List<Long> timeList = new ArrayList<>(clicks);
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < timeList.size(); i++) {
                intervals.add(timeList.get(i) - timeList.get(i - 1));
            }

            double stdDev = MathUtil.getStandardDeviation(intervals);
            // CPS > 12 且时间间隔几乎完全机械一致 (方差过小)
            if (currentCps > 12 && stdDev < minStdDeviation) {
                flag(player, 1, String.format("Robot Click CPS=%d, stdDev=%.2f", currentCps, stdDev));
            }
        }
    }
}
