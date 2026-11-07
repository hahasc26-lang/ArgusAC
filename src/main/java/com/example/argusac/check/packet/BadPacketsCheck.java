package com.example.argusac.check.packet;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 畸形与异常数据包检测 (BadPacketsCheck)
 * 检测非法俯仰角越界 (Derp / Head Roll)、NaN/无穷大坐标以及异常冗余包
 */
public class BadPacketsCheck extends AbstractCheck {

    private boolean strictPitch;
    private boolean checkDuplicateMove;

    public BadPacketsCheck(ArgusACPlugin plugin) {
        super(plugin, "BadPackets", CheckType.PACKET);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.strictPitch = getChecksConfig().getBoolean(path + "strict-pitch", true);
        this.checkDuplicateMove = getChecksConfig().getBoolean(path + "check-duplicate-move", true);
    }

    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null || data.isSetbackImmune()) {
            return;
        }

        Location to = event.getTo();
        if (to == null) {
            return;
        }

        // 1. 浮点数异常检测 (NaN / Infinite)
        if (Double.isNaN(to.getX()) || Double.isNaN(to.getY()) || Double.isNaN(to.getZ())
                || Double.isNaN(to.getYaw()) || Double.isNaN(to.getPitch())
                || Double.isInfinite(to.getX()) || Double.isInfinite(to.getY()) || Double.isInfinite(to.getZ())
                || Double.isInfinite(to.getYaw()) || Double.isInfinite(to.getPitch())) {
            event.setCancelled(true);
            flag(player, 5, "NaN or Infinite coordinates/rotation", event);
            return;
        }

        // 2. 视角俯仰角越界检测 (Pitch [-90.0, 90.0])
        // 原生客户端无法发送超过正负 90 度的俯仰角，大于90度属于 Derp / 旋转外挂
        if (strictPitch) {
            float pitch = to.getPitch();
            if (pitch > 90.001f || pitch < -90.001f) {
                flag(player, 2, String.format("Illegal Pitch pitch=%.2f", pitch), event);
            }
        }

        // 3. 冲突状态检测：常规站姿潜行下强开疾跑且实际产生超出潜行上限的高速位移
        //    (Sneak-Sprint Exploit，排除 1 格高爬行模式、水流/滑翔以及客户端同时按住 Ctrl+Shift 切状态但实际移速已受潜行限制的正常情况)
        boolean gliding = com.example.argusac.compat.VersionAdapterFactory.getAdapter().isGliding(player);
        boolean swimming = com.example.argusac.compat.VersionAdapterFactory.getAdapter().isSwimming(player);
        Location from = event.getFrom();
        double horizDist = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        if (player.isSneaking() && player.isSprinting() && !gliding && !swimming
                && !player.isFlying() && !player.isInsideVehicle()
                && player.getEyeHeight() >= 1.4 && horizDist > 0.20) {
            data.incrementSneakSprintTicks();
            if (data.getSneakSprintTicks() >= 3) {
                flag(player, 1, String.format("Sneak-Sprint Conflicting State speed=%.3f", horizDist));
                player.setSprinting(false);
            }
        } else {
            data.resetSneakSprintTicks();
        }

        // 4. 睡眠状态异常移动检测 (Sleep-Move)
        if (player.isSleeping() && (from.distanceSquared(to) > 0.05)) {
            event.setCancelled(true);
            flag(player, 2, "Moving while sleeping", event);
        }
    }

    public boolean isStrictPitch() {
        return strictPitch;
    }

    public boolean isCheckDuplicateMove() {
        return checkDuplicateMove;
    }
}
