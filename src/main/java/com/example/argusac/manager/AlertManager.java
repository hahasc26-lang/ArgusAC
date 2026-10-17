package com.example.argusac.manager;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.i18n.I18n;
import com.example.argusac.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 警报通知管理器 (AlertManager)
 * 负责向在线管理员分发违规告警消息，全面集成 i18n 客户端多语言自适应与 Adventure 文本交互
 */
public class AlertManager {

    private final ArgusACPlugin plugin;

    private boolean enabled;
    private boolean soundEnabled;
    private Sound soundType;
    private float soundVolume;
    private float soundPitch;
    private long cooldownMs;

    // 针对每个玩家+检测项的告警冷却映射 (Key: "PlayerName:CheckName")
    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();
    private final java.util.Set<java.util.UUID> mutedPlayers = ConcurrentHashMap.newKeySet();

    public AlertManager(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.reloadConfig();
    }

    public void reloadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("alerts.enabled", true);
        this.cooldownMs = config.getLong("alerts.cooldown-ms", 1000L);

        this.soundEnabled = config.getBoolean("alerts.sound.enabled", true);
        String soundName = config.getString("alerts.sound.type", "BLOCK_NOTE_BLOCK_BASS");
        try {
            this.soundType = Sound.valueOf(soundName);
        } catch (IllegalArgumentException e) {
            this.soundType = Sound.BLOCK_NOTE_BLOCK_BASS;
        }
        this.soundVolume = (float) config.getDouble("alerts.sound.volume", 1.0);
        this.soundPitch = (float) config.getDouble("alerts.sound.pitch", 1.2);
    }

    /**
     * 发送检测违规警报给所有拥有 ArgusAC.alert 权限的管理员 (全面接入 i18n 客户端自适应)
     */
    public void sendAlert(Player cheater, String checkName, int currentVl, int maxVl, String details) {
        if (!enabled || cheater == null) {
            return;
        }

        // 检查冷却
        String coolKey = cheater.getName() + ":" + checkName;
        long now = System.currentTimeMillis();
        Long lastTime = alertCooldowns.get(coolKey);
        if (lastTime != null && now - lastTime < cooldownMs) {
            return;
        }
        alertCooldowns.put(coolKey, now);

        int ping = com.example.argusac.compat.VersionAdapterFactory.getAdapter().getPing(cheater);
        int displayPing = Math.max(0, ping);
        double[] tpsArr = com.example.argusac.compat.VersionAdapterFactory.getAdapter().getTPS();
        String tpsStr = (tpsArr != null && tpsArr.length > 0) ? String.format("%.1f", Math.min(20.0, tpsArr[0])) : "20.0";
        var checkObj = plugin.getCheckManager().getCheck(checkName);

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if ((admin.hasPermission("argusac.alert") || admin.hasPermission("ArgusAC.alert")) && !isMuted(admin)) {
                String checkType = (checkObj != null) ? checkObj.getType().getDisplayName(admin) : "OTHER";
                // 基于每个管理员的客户端语言偏好渲染警报文本
                Component baseComponent = I18n.component(admin, "alert.format",
                        "player", cheater.getName(),
                        "check", checkName,
                        "type", checkType,
                        "vl", currentVl,
                        "max_vl", maxVl,
                        "ping", displayPing,
                        "tps", tpsStr,
                        "detail", details);

                Component hoverComponent = I18n.component(admin, "alert.hover-details",
                        "player", cheater.getName(),
                        "detail", details);

                Component finalComponent = baseComponent
                        .hoverEvent(HoverEvent.showText(hoverComponent))
                        .clickEvent(ClickEvent.runCommand("/tp " + cheater.getName()));

                admin.sendMessage(finalComponent);

                if (soundEnabled && soundType != null) {
                    admin.playSound(admin.getLocation(), soundType, soundVolume, soundPitch);
                }
            }
        }

        // 发送给控制台 (使用服务端基准语言)
        String consoleCheckType = (checkObj != null) ? checkObj.getType().getDisplayName(Bukkit.getConsoleSender()) : "OTHER";
        Component consoleComp = I18n.component(Bukkit.getConsoleSender(), "alert.format",
                "player", cheater.getName(),
                "check", checkName,
                "type", consoleCheckType,
                "vl", currentVl,
                "max_vl", maxVl,
                "ping", displayPing,
                "tps", tpsStr,
                "detail", details);
        Bukkit.getConsoleSender().sendMessage(consoleComp);
    }

    /**
     * 向管理员广播系统信息
     */
    public void broadcastAdmin(String message) {
        Component comp = ColorUtil.toComponent(message);
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if ((admin.hasPermission("argusac.alert") || admin.hasPermission("ArgusAC.alert")) && !isMuted(admin)) {
                admin.sendMessage(comp);
            }
        }
        Bukkit.getConsoleSender().sendMessage(comp);
    }

    public boolean isMuted(Player player) {
        return player != null && mutedPlayers.contains(player.getUniqueId());
    }

    public void setMuted(Player player, boolean muted) {
        if (player == null) {
            return;
        }
        if (muted) {
            mutedPlayers.add(player.getUniqueId());
        } else {
            mutedPlayers.remove(player.getUniqueId());
        }
    }

    /**
     * 玩家退出时清除会话状态与警报冷却记录
     */
    public void clearCooldowns(String playerName) {
        if (playerName == null) {
            return;
        }
        String prefix = playerName.toLowerCase() + ":";
        alertCooldowns.keySet().removeIf(k -> k.toLowerCase().startsWith(prefix));
    }

    public void clearSessionState(Player player) {
        if (player != null) {
            mutedPlayers.remove(player.getUniqueId());
            clearCooldowns(player.getName());
        }
    }
}
