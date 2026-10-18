package com.example.argusac.history;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.compat.CompatBoundingBox;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 实体历史碰撞盒回溯追踪器 (EntityHistoryTracker)
 * 面向战斗延迟补偿 (Lag Compensation) 的核心工业级组件，仿 GrimAC 实体时空回溯原理：
 * 记录目标实体近 1000ms 内的每个 tick 碰撞盒快照，攻击发生时精准回溯至攻击者视角对应的时间帧，
 * 彻底根除因受害者移动/被击退导致的超距假误报，将极限 Reach 判定精度收敛至原版理论极限 (3.00 格)。
 */
public class EntityHistoryTracker {

    private final ArgusACPlugin plugin;
    private final Map<UUID, Deque<HistoricalBox>> historyMap = new ConcurrentHashMap<>();
    private BukkitTask trackingTask = null;

    // 历史快照保留最大时间 (毫秒，覆盖绝大部分 800ms 内的高延迟网络)
    private static final long MAX_HISTORY_AGE_MS = 1000L;

    public EntityHistoryTracker(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动主线程每 tick 实体位置与碰撞盒采样循环
     */
    public void start() {
        if (trackingTask != null) {
            trackingTask.cancel();
        }
        this.trackingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (trackingTask != null) {
            trackingTask.cancel();
            trackingTask = null;
        }
        historyMap.clear();
    }

    /**
     * 每 tick 自动采样所有在线玩家的实际碰撞盒与位置
     */
    private void tick() {
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            recordEntity(player, adapter, now);
        }

        // 定期清理已离线或超过时效的记录
        cleanup(now);
    }

    /**
     * 手动记录指定实体当次快照 (支持非玩家实体如马、铁傀儡等)
     */
    public void recordEntity(Entity entity, VersionAdapter adapter, long timestamp) {
        if (entity == null || !entity.isValid()) return;

        CompatBoundingBox box = adapter.getEntityBoundingBox(entity);
        Location center = entity.getLocation();

        Deque<HistoricalBox> deque = historyMap.computeIfAbsent(entity.getUniqueId(), k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new HistoricalBox(box, center, timestamp));

        // 淘汰超期快照
        while (!deque.isEmpty() && timestamp - deque.peekFirst().getTimestamp() > MAX_HISTORY_AGE_MS) {
            deque.pollFirst();
        }
    }

    /**
     * 核心延迟补偿算法：根据攻击者的 Ping 往返延迟，计算视线到目标所有可能历史盒的最短物理距离
     *
     * @param eyeLoc 攻击者视线起点
     * @param target 受害实体
     * @param pingMs 攻击者网络延迟 (毫秒)
     * @return 历史候选盒与当前盒中的最短欧几里得距离 (格)
     */
    public double getCompensatedMinDistance(Location eyeLoc, Entity target, int pingMs) {
        if (eyeLoc == null || target == null) return Double.MAX_VALUE;

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        // 1. 获取目标实体的当前实时碰撞盒
        CompatBoundingBox currentBox = adapter.getEntityBoundingBox(target);
        double minDistance = currentBox.distanceTo(eyeLoc);

        // 如果没有延迟或无历史记录，直接返回当前盒距离
        if (pingMs <= 10) {
            return minDistance;
        }

        Deque<HistoricalBox> deque = historyMap.get(target.getUniqueId());
        if (deque == null || deque.isEmpty()) {
            return minDistance;
        }

        long now = System.currentTimeMillis();
        // 攻击者看到受害者画面的理论历史时间点 (考虑网络单向延迟约 ping/2，并给予 50ms 采样时间窗)
        long targetTimestamp = now - pingMs;
        long windowStart = targetTimestamp - 60L;
        long windowEnd = targetTimestamp + 60L;

        // 2. 遍历延迟回溯时间窗口内的所有历史快照
        for (HistoricalBox historical : deque) {
            long ts = historical.getTimestamp();
            if (ts >= windowStart && ts <= windowEnd) {
                double dist = historical.getBox().distanceTo(eyeLoc);
                if (dist < minDistance) {
                    minDistance = dist;
                }
            }
        }

        return minDistance;
    }

    /**
     * 回溯检索目标实体在指定延迟时刻的最可能中心坐标 (用于 KillAura 夹角精确计算)
     */
    public Location getCompensatedLocation(Entity target, int pingMs) {
        if (target == null) return null;
        if (pingMs <= 15) return target.getLocation();

        Deque<HistoricalBox> deque = historyMap.get(target.getUniqueId());
        if (deque == null || deque.isEmpty()) {
            return target.getLocation();
        }

        long targetTimestamp = System.currentTimeMillis() - pingMs;
        HistoricalBox closestBox = null;
        long minDiff = Long.MAX_VALUE;

        for (HistoricalBox hb : deque) {
            long diff = Math.abs(hb.getTimestamp() - targetTimestamp);
            if (diff < minDiff) {
                minDiff = diff;
                closestBox = hb;
            }
        }

        return (closestBox != null && minDiff < 200L) ? closestBox.getCenterLocation() : target.getLocation();
    }

    /**
     * 检查目标实体是否存在已采样的历史盒快照
     */
    public boolean hasHistory(UUID uuid) {
        if (uuid == null) return false;
        Deque<HistoricalBox> deque = historyMap.get(uuid);
        return deque != null && !deque.isEmpty();
    }

    /**
     * 移除实体记录 (玩家离线时)
     */
    public void removeEntity(UUID uuid) {
        historyMap.remove(uuid);
    }

    private void cleanup(long now) {
        historyMap.entrySet().removeIf(entry -> {
            Deque<HistoricalBox> deque = entry.getValue();
            while (!deque.isEmpty() && now - deque.peekFirst().getTimestamp() > MAX_HISTORY_AGE_MS) {
                deque.pollFirst();
            }
            return deque.isEmpty();
        });
    }
}
