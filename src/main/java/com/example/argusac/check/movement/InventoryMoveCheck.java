package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.example.argusac.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 背包移动检测 (InventoryMoveCheck)
 * 检查玩家在打开背包或容器状态下异常疾跑、跳跃或长距离移动作弊行为
 */
public class InventoryMoveCheck extends AbstractCheck {

    private double maxDistanceWhileOpen;

    public InventoryMoveCheck(ArgusACPlugin plugin) {
        super(plugin, "InventoryMove", CheckType.MOVEMENT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        this.maxDistanceWhileOpen = plugin.getConfig().getDouble("checks." + name + ".max-distance-while-open", 0.15);
    }

    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isInsideVehicle() || com.example.argusac.compat.VersionAdapterFactory.getAdapter().isGliding(player)) {
            return;
        }

        if (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod()) {
            return;
        }

        // 仅在玩家处于打开容器/背包状态下进行检测
        if (!data.isInventoryOpen()) {
            return;
        }

        long now = System.currentTimeMillis();
        // 打开容器前 350ms 属于奔跑逼近容器的残余惯性缓冲，受流水冲刷、冰面惯性滑行或受到伤害击退亦豁免
        if (now - data.getInventoryOpenTime() < 350L || data.getIceTicks() > 0
                || com.example.argusac.compat.VersionAdapterFactory.getAdapter().isInWater(player) || data.hasRecentVelocity(800L)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        double distance = MathUtil.getHorizontalDistance(from, to);

        // 如果在开包状态下出现疾跑或水平移动距离明显大于滑行惯性
        if (distance > maxDistanceWhileOpen && (player.isSprinting() || distance > 0.28)) {
            flag(player, 1, String.format("Moving while inv open dist=%.3f", distance), event);
        }
    }
}
