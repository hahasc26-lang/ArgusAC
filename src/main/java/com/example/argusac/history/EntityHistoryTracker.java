package com.example.argusac.history;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.compat.CompatBoundingBox;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 实体历史碰撞盒回溯追踪器 (EntityHistoryTracker)
 * 面向战斗延迟补偿 (Lag Compensation) 与 Netty 数据包层拦截的核心工业级组件：
 * 1. 记录目标实体近 1000ms 内的每个 tick 碰撞盒快照，攻击发生时精准回溯至攻击者视角对应的时间帧；
 * 2. 维护线程安全的 EntityId -> Entity/UUID 映射索引，支撑 Netty 线程在 PacketPlayInUseEntity 阶段毫秒级定位目标实体。
 */
public class EntityHistoryTracker {

    private final ArgusACPlugin plugin;
    private final Map<UUID, Deque<HistoricalBox>> historyMap = new ConcurrentHashMap<>();
    private final Map<Integer, UUID> entityIdToUuidMap = new ConcurrentHashMap<>();
    private final Map<UUID, Entity> uuidToEntityMap = new ConcurrentHashMap<>();
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
        entityIdToUuidMap.clear();
        uuidToEntityMap.clear();
    }

    /**
     * 每 tick 自动采样所有在线玩家及周围活跃生物的实际碰撞盒与位置
     */
    private void tick() {
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            recordEntity(player, adapter, now);
            // 同步索引玩家周围 8 格内的战斗目标生物，确保 PVE 与 PVP 均可在 Netty 层精准拦截
            try {
                for (Entity nearby : player.getNearbyEntities(8.0, 6.0, 8.0)) {
                    if (nearby instanceof LivingEntity && !(nearby instanceof Player)) {
                        recordEntity(nearby, adapter, now);
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 定期清理已离线或超过时效的记录
        cleanup(now);
    }

    /**
     * 手动记录指定实体当次快照 (支持玩家及非玩家实体如马、铁傀儡等)
     */
    public void recordEntity(Entity entity, VersionAdapter adapter, long timestamp) {
        if (entity == null || !entity.isValid()) return;

        CompatBoundingBox box = adapter.getEntityBoundingBox(entity);
        Location center = entity.getLocation();
        double eyeHeight = (entity instanceof LivingEntity living) ? living.getEyeHeight() : (box.getHeight() * 0.85);
        boolean dead = entity.isDead() || (entity instanceof LivingEntity living && living.getHealth() <= 0.0);

        UUID uuid = entity.getUniqueId();
        entityIdToUuidMap.put(entity.getEntityId(), uuid);
        uuidToEntityMap.put(uuid, entity);

        Deque<HistoricalBox> deque = historyMap.computeIfAbsent(uuid, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new HistoricalBox(box, center, timestamp, eyeHeight, dead));

        // 淘汰超期快照
        while (!deque.isEmpty() && timestamp - deque.peekFirst().getTimestamp() > MAX_HISTORY_AGE_MS) {
            deque.pollFirst();
        }
    }

    /**
     * 根据网络包中的 Entity ID 跨线程安全解析目标实体对象
     */
    public Entity resolveEntity(World world, int entityId) {
        UUID uuid = entityIdToUuidMap.get(entityId);
        if (uuid != null) {
            Entity cached = uuidToEntityMap.get(uuid);
            if (cached != null) {
                return cached;
            }
        }
        // 在线玩家快速遍历兜底
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getEntityId() == entityId) {
                entityIdToUuidMap.put(entityId, online.getUniqueId());
                uuidToEntityMap.put(online.getUniqueId(), online);
                return online;
            }
        }
        // 若处于主线程且缓存未命中，尝试在同世界实体列表中查找
        if (Bukkit.isPrimaryThread() && world != null) {
            try {
                for (Entity ent : world.getEntities()) {
                    if (ent.getEntityId() == entityId) {
                        entityIdToUuidMap.put(entityId, ent.getUniqueId());
                        uuidToEntityMap.put(ent.getUniqueId(), ent);
                        return ent;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 获取目标实体最新的历史快照 (供 Netty 线程无锁安全读取碰撞盒与视线高度)
     */
    public HistoricalBox getLatestSnapshot(UUID uuid) {
        if (uuid == null) return null;
        Deque<HistoricalBox> deque = historyMap.get(uuid);
        return (deque != null) ? deque.peekLast() : null;
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

        Deque<HistoricalBox> deque = historyMap.get(target.getUniqueId());
        double minDistance = Double.MAX_VALUE;

        // 优先从线程安全的最新快照获取当前盒，若无快照或处于主线程再调用 adapter
        HistoricalBox latest = (deque != null) ? deque.peekLast() : null;
        if (latest != null && latest.getBox() != null) {
            minDistance = latest.getBox().distanceTo(eyeLoc);
        }
        if (Bukkit.isPrimaryThread() || minDistance == Double.MAX_VALUE) {
            try {
                VersionAdapter adapter = VersionAdapterFactory.getAdapter();
                CompatBoundingBox currentBox = adapter.getEntityBoundingBox(target);
                minDistance = Math.min(minDistance, currentBox.distanceTo(eyeLoc));
            } catch (Throwable ignored) {
            }
        }

        // 如果没有延迟或无历史记录，直接返回当前盒距离
        if (pingMs <= 10 || deque == null || deque.isEmpty()) {
            return minDistance;
        }

        long now = System.currentTimeMillis();
        // 攻击者看到受害者画面的理论历史时间点 (考虑网络单向延迟并给予 60ms 采样时间窗)
        long targetTimestamp = now - pingMs;
        long windowStart = targetTimestamp - 60L;
        long windowEnd = targetTimestamp + 60L;

        // 2. 遍历延迟回溯时间窗口内的所有历史快照
        for (HistoricalBox historical : deque) {
            long ts = historical.getTimestamp();
            if (ts >= windowStart && ts <= windowEnd && historical.getBox() != null) {
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

        Deque<HistoricalBox> deque = historyMap.get(target.getUniqueId());
        HistoricalBox latest = (deque != null) ? deque.peekLast() : null;
        Location fallbackLoc = (latest != null && latest.getCenterLocation() != null)
                ? latest.getCenterLocation()
                : target.getLocation();

        if (pingMs <= 15 || deque == null || deque.isEmpty()) {
            return fallbackLoc;
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

        return (closestBox != null && minDiff < 200L && closestBox.getCenterLocation() != null)
                ? closestBox.getCenterLocation()
                : fallbackLoc;
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
        if (uuid == null) return;
        historyMap.remove(uuid);
        Entity removed = uuidToEntityMap.remove(uuid);
        if (removed != null) {
            entityIdToUuidMap.remove(removed.getEntityId());
        }
    }

    private void cleanup(long now) {
        historyMap.entrySet().removeIf(entry -> {
            UUID uuid = entry.getKey();
            Deque<HistoricalBox> deque = entry.getValue();
            while (!deque.isEmpty() && now - deque.peekFirst().getTimestamp() > MAX_HISTORY_AGE_MS) {
                deque.pollFirst();
            }
            if (deque.isEmpty()) {
                Entity removed = uuidToEntityMap.remove(uuid);
                if (removed != null) {
                    entityIdToUuidMap.remove(removed.getEntityId());
                }
                return true;
            }
            return false;
        });
    }
}
