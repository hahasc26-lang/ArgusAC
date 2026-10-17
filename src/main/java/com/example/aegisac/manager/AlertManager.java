package com.example.aegisac.manager;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.util.ColorUtil;
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
 * 负责向在线管理员分发违规告警消息，支持 Paper Adventure 文本交互与音效提示
 */
public class AlertManager {

    private final AegisACPlugin plugin;

    private boolean enabled;
    private String format;
    private boolean soundEnabled;
    private Sound soundType;
    private float soundVolume;
    private float soundPitch;
    private long cooldownMs;

    // 针对每个玩家+检测项的告警冷却映射 (Key: "PlayerName:CheckName")
    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();

    public AlertManager(AegisACPlugin plugin) {
        this.plugin = plugin;
        this.reloadConfig();
    }

    public void reloadConfig() {
        FileConfiguration config = plugin.getConfig();
        this.enabled = config.getBoolean("alerts.enabled", true);
        this.format = config.getString("alerts.format",
                "&c[!] &e{player} &7触发 &b{check} &8(VL: &c{vl}&8/&7{max_vl}&8) &7- &f{details} &8[&7{ping}ms&8]");
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
     * 发送检测违规警报给所有拥有 aegisac.alert 权限的管理员
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

        int ping = com.example.aegisac.compat.VersionAdapterFactory.getAdapter().getPing(cheater);
        int displayPing = Math.max(0, ping);
        String rawMsg = format
                .replace("{player}", cheater.getName())
                .replace("{check}", checkName)
                .replace("{vl}", String.valueOf(currentVl))
                .replace("{max_vl}", String.valueOf(maxVl))
                .replace("{details}", details)
                .replace("{ping}", String.valueOf(displayPing));

        Component baseComponent = ColorUtil.toComponent(rawMsg);

        // 增加点击快速传送到嫌疑玩家的高级功能
        Component finalComponent = baseComponent
                .hoverEvent(HoverEvent.showText(ColorUtil.toComponent("&a点击快速传送到 &e" + cheater.getName())))
                .clickEvent(ClickEvent.runCommand("/tp " + cheater.getName()));

        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("aegisac.alert")) {
                admin.sendMessage(finalComponent);
                if (soundEnabled && soundType != null) {
                    admin.playSound(admin.getLocation(), soundType, soundVolume, soundPitch);
                }
            }
        }

        // 发送给控制台
        Bukkit.getConsoleSender().sendMessage(ColorUtil.toComponent(rawMsg));
    }

    /**
     * 向管理员广播普通系统信息（例如回退通知）
     */
    public void broadcastAdmin(String message) {
        Component comp = ColorUtil.toComponent(message);
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("aegisac.alert")) {
                admin.sendMessage(comp);
            }
        }
        Bukkit.getConsoleSender().sendMessage(comp);
    }

    /**
     * 玩家退出时清除警报冷却记录，防止内存泄漏
     */
    public void clearCooldowns(String playerName) {
        if (playerName == null) {
            return;
        }
        String prefix = playerName.toLowerCase() + ":";
        alertCooldowns.keySet().removeIf(k -> k.toLowerCase().startsWith(prefix));
    }
}
