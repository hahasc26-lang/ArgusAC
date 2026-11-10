package com.example.argusac.setback.handler;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.data.PlayerData;
import com.example.argusac.manager.SetbackManager;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.setback.PlayerSetbackContext;
import com.example.argusac.setback.SafeLocationFinder;
import com.example.argusac.setback.SetbackNotifier;
import com.example.argusac.setback.SetbackPhysics;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * SOFT 模式处理器 (Smooth Mitigation)
 * <p>
 * 核心机制：
 * 1. 在 PlayerMoveEvent 事件内部平滑重写目标位置 (moveEvent.setTo)；
 * 2. 不取消移动事件，不触发主线程强制传送，消除传统橡皮筋 (Rubberband) 拉扯感；
 * 3. 锁定水平 X/Z 轴位移至起点，垂直 Y 轴保留自然重力或模拟自由落体下坠。
 */
public class SoftSetbackHandler implements SetbackHandler {

    private final ArgusACPlugin plugin;
    private final SetbackManager setbackManager;
    private final SetbackNotifier setbackNotifier;

    public SoftSetbackHandler(ArgusACPlugin plugin,
                              SetbackManager setbackManager,
                              SetbackNotifier setbackNotifier) {
        this.plugin = plugin;
        this.setbackManager = setbackManager;
        this.setbackNotifier = setbackNotifier;
    }

    @Override
    public boolean handle(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        if (player == null || check == null || moveEvent == null) {
            return false;
        }

        applySafeAirCancellation(moveEvent);
        Location from = moveEvent.getFrom();
        Location target = moveEvent.getTo() != null ? moveEvent.getTo() : from;

        PlayerSetbackContext context = setbackManager.getContext(player.getUniqueId());
        context.setLastSetbackTimestamp(check.getName(), System.currentTimeMillis());

        boolean isAirborne = !setbackManager.isGrounded(player, from);
        boolean targetGrounded = setbackManager.isGrounded(player, target);

        setbackNotifier.postSetbackSuccess(player, context, check.getName(), currentVl, reason, "SOFT",
                SafeLocationFinder.formatLoc(from), SafeLocationFinder.formatLoc(target), isAirborne,
                LegitimateLocation.from(target, null, targetGrounded, false, null),
                setbackManager.getNotifyPlayerChannel(), setbackManager.getNotifyPlayerCooldownMs(), setbackManager.isNotifyAdminEnabled());
        return true;
    }

    /**
     * 在空中安全平滑取消位移：保留原版重力加速度，锁定水平平移
     */
    public void applySafeAirCancellation(PlayerMoveEvent moveEvent) {
        PlayerData data = (moveEvent != null && moveEvent.getPlayer() != null && plugin.getPlayerManager() != null)
                ? plugin.getPlayerManager().getPlayerData(moveEvent.getPlayer()) : null;
        SetbackPhysics.applySafeAirCancellation(moveEvent, data);
    }
}

