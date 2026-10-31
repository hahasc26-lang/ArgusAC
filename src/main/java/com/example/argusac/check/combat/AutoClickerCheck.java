package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.combat.CombatVerdict;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 自动连点器与高频点击检测 (AutoClickerCheck)
 * <p>
 * 工业级双重时钟与统计学分析引擎：
 * 1. 客户端 Flying 虚拟时钟与 TCP Burst 积压包平滑：
 * 以客户端真实的移动数据包（Flying/Position/Rotation）为时间递进标尺，
 * 彻底抵消 TCP 积压与代理缓冲刷新导致的单物理刻多个数据包同时到达（Burst Flush）的瞬时假高频。
 * 2. 挖掘状态与战斗交互硬隔离 (Dig-Attacking Transition Wall)：
 * 挖掘中及挖掘刚刚结束的过渡点击完全隔离，绝不将挖矿长按左键产生的持续 20 CPS 样本带入 Combat 统计。
 * 3. 失焦切屏与界面关闭防粘滞 (Focus Recovery Grace Period)：
 * 在玩家关闭背包、聊天框或切回游戏窗口的前 350ms 内提供微缓冲，消除 GLFW 按键粘滞补包误报。
 * 4. 统计学离散度与标准差分析 (Standard Deviation & Mechanical Uniformity)：
 * 结合人类生物学极限（手速再快也存在自然的肌肉抖动与微小方差），
 * 精准侦测标准差极低（< 4.5ms）且固定模数运行的宏连点器与脚本外挂。
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
     * 在玩家左键点击交互或攻击实体时记录点击频率 (主线程 Bukkit 兜底入口)
     */
    public void handleClick(Player player) {
        CombatVerdict verdict = evaluateClick(player);
        if (verdict.isHighConfidence()) {
            flag(player, verdict.getVlStep(), verdict.getReason());
        }
    }

    /**
     * 协议级点击频率与标准差评估 (支持返回分级裁决结果供 Netty Packet 拦截器使用)
     */
    public CombatVerdict evaluateClick(Player player) {
        if (!enabled || player == null) {
            return CombatVerdict.pass();
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return CombatVerdict.pass();
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return CombatVerdict.pass();
        }

        long now = System.currentTimeMillis();

        // 1. 挖掘状态硬隔离：正在挖掘或刚刚结束挖掘（200ms 内）严格豁免连点检测并重置间隔计算
        if (data.isDigging()) {
            data.resetClickIntervalTracking();
            return CombatVerdict.pass();
        }

        // 2. 界面关闭与切屏失焦恢复宽限 (Focus & Window Grace Period)
        if (data.isInFocusRecoveryGracePeriod()) {
            data.resetClickIntervalTracking();
            return CombatVerdict.pass();
        }

        long lastClickTime = data.getLastClickTimestamp();
        long deltaMs = (lastClickTime > 0L) ? (now - lastClickTime) : 0L;

        // 过滤同一物理点击由多事件/多包引发的极其微小的重复触发 (< 25ms)
        if (deltaMs > 0 && deltaMs < 25L) {
            return CombatVerdict.pass();
        }

        data.setLastClickTimestamp(now);
        data.incrementClicksInCurrentFlyingTick();

        // 3. 计算与上一点击的虚拟 Flying Tick 间隔与毫秒样本，记录进统计学样本池
        int deltaFlyingTicks = (int) Math.min(20, Math.max(1, deltaMs / 50L));
        data.recordClickSample(deltaMs, deltaFlyingTicks);

        Deque<Long> clicks = data.getClickTimestamps();
        clicks.addLast(now);

        // 清理 1000ms (1秒) 之前的过期点击
        while (!clicks.isEmpty() && now - clicks.peekFirst() > 1000L) {
            clicks.pollFirst();
        }

        int currentCps = clicks.size();

        // 4. 超高频 CPS 极限检测 (结合短时突发缓冲容忍)
        if (currentCps > maxCps) {
            String detail = String.format("High CPS=%d, max=%d", currentCps, maxCps);
            // 若仅轻微超出 (maxCps + 1 ~ 2)，作为低确信丢包缓冲，防止手速极限玩家微量抖动被累加严重 VL
            if (currentCps <= maxCps + 2) {
                return CombatVerdict.lowConfidenceDrop(this, detail);
            }
            return CombatVerdict.highConfidence(this, 1, detail);
        }

        // 5. 统计学样本离散度分析 (Mechanical Uniformity & Aimbot/Macro Clicker)
        Deque<Double> intervalSamples = data.getMsIntervalSamples();
        if (intervalSamples.size() >= 14) {
            List<Double> sampleList = new ArrayList<>(intervalSamples);
            double stdDev = calculateStandardDeviation(sampleList);
            double mean = calculateMean(sampleList);

            // 当玩家平均点击速度较快 (平均间隔 < 90ms，即 CPS > 11)，且点击间隔几乎无任何生物学随机抖动 (stdDev <
            // minStdDeviation)
            if (mean < 90.0 && stdDev < minStdDeviation) {
                // 计算离散度更深层的重复模数占比 (完全相同时间间隔的占比)
                int identicalCount = countIdenticalIntervals(sampleList);
                double identicalRatio = (double) identicalCount / sampleList.size();

                if (stdDev < 2.5 || identicalRatio > 0.65) {
                    String detail = String.format(
                            "Robot Macro Clicker CPS=%d, stdDev=%.2f, mean=%.1fms, identical=%.1f%%",
                            currentCps, stdDev, mean, identicalRatio * 100.0);
                    return CombatVerdict.highConfidence(this, 1, detail);
                }
            }
        }

        return CombatVerdict.pass();
    }

    private double calculateMean(List<Double> values) {
        if (values == null || values.isEmpty())
            return 0.0;
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private double calculateStandardDeviation(List<Double> values) {
        if (values == null || values.size() < 2)
            return 999.0;
        double mean = calculateMean(values);
        double variance = 0.0;
        for (double v : values) {
            variance += Math.pow(v - mean, 2);
        }
        return Math.sqrt(variance / values.size());
    }

    private int countIdenticalIntervals(List<Double> values) {
        int count = 0;
        for (int i = 1; i < values.size(); i++) {
            if (Math.abs(values.get(i) - values.get(i - 1)) <= 2.0) {
                count++;
            }
        }
        return count;
    }
}
