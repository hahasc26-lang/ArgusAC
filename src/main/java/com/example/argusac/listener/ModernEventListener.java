package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.data.PlayerData;
import com.example.argusac.model.VelocitySourceType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRiptideEvent;

/**
 * 现代版本 (1.13+) 专属事件监听器
 * 仅在当前运行环境 >= 1.13 时动态注册，杜绝 1.12.2 等低版本在类加载时因缺少 PlayerRiptideEvent 抛出 NoClassDefFoundError
 */
public class ModernEventListener implements Listener {

    private final ArgusACPlugin plugin;

    public ModernEventListener(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerRiptide(PlayerRiptideEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            // 激流三叉戟赋予合法高机动豁免
            data.grantLegalVelocity(VelocitySourceType.RIPTIDE, 2000L);
            data.getElytraData().setLastRiptideTime(System.currentTimeMillis());
        }
    }
}
