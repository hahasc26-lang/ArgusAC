package com.example.aegisac.data;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家数据管理器 (PlayerManager)
 * 负责玩家上线注册、下线回收以及线程安全的数据检索
 */
public class PlayerManager {

    private final Map<UUID, PlayerData> dataMap = new ConcurrentHashMap<>();

    public PlayerData getPlayerData(Player player) {
        if (player == null) {
            return null;
        }
        return dataMap.computeIfAbsent(player.getUniqueId(), k -> new PlayerData(player));
    }

    public PlayerData getPlayerData(UUID uuid) {
        return dataMap.get(uuid);
    }

    public void removePlayerData(UUID uuid) {
        dataMap.remove(uuid);
    }

    public Collection<PlayerData> getAllData() {
        return dataMap.values();
    }

    public void clear() {
        dataMap.clear();
    }
}
