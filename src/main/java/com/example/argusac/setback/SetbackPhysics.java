package com.example.argusac.setback;

import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 原版运动学物理曲线拟合与重力仿真计算器 (SetbackPhysics)
 * 提供真实 Minecraft 自由落体动力学模拟，防止空中回退形成伪悬浮平台。
 */
public class SetbackPhysics {

    /**
     * 拟合 Minecraft 原版自由落体加速度曲线：
     * v_Y = (v_{Y,prev} - 0.08) * 0.98，最大终端速度 -3.92
     */
    public static double computeVanillaGravityFall(int airTicks) {
        if (airTicks <= 0) return -0.08;
        double v = 0.0;
        int ticks = Math.min(60, airTicks);
        for (int i = 0; i < ticks; i++) {
            v = (v - 0.08) * 0.98;
            if (v < -3.92) {
                v = -3.92;
                break;
            }
        }
        return Math.max(-3.92, v);
    }

    /**
     * 对空中移动进行安全拦截与重力保全：
     * 1. 严格锁定 X、Z 水平位移至起点 from，阻止非法平移飞行；
     * 2. 垂直 Y 轴严禁锁定为恒定值 (杜绝空中悬停)，并防范微量下落慢速悬浮 (Slow-Fall Bypass)；
     *    根据玩家滞空时间动态计算原版自由落体期望加速度 (Vanilla Fall Curve)，
     *    若试图反重力上升、静止悬停或慢飘伪装，强制施加自然重力加速度衰减！
     */
    public static void applySafeAirCancellation(PlayerMoveEvent moveEvent, PlayerData data) {
        if (moveEvent == null || moveEvent.getFrom() == null) return;
        Location from = moveEvent.getFrom();
        Location to = moveEvent.getTo();
        if (to == null) {
            moveEvent.setTo(from.clone());
            return;
        }

        Location safeTo = from.clone();
        safeTo.setYaw(to.getYaw());
        safeTo.setPitch(to.getPitch());

        Player player = moveEvent.getPlayer();
        boolean slowFalling = (player != null) && VersionAdapterFactory.getAdapter().hasPotionEffect(player, CompatPotionType.SLOW_FALLING);

        double deltaY = to.getY() - from.getY();
        if (slowFalling) {
            // 拥有缓降药水：允许慢速滑翔
            if (deltaY < 0) {
                safeTo.setY(to.getY());
            } else {
                safeTo.setY(from.getY() - 0.02);
            }
        } else {
            int airTicks = (data != null) ? data.getAirTicks() : 1;
            double expectedFall = computeVanillaGravityFall(airTicks);

            // 若原本自然下落且下落位移符合或超出重力加速度 (deltaY <= expectedFall)，保留其自然下落
            // 若试图反重力上升、静止悬停或微量下坠伪装悬浮 (deltaY > expectedFall)，强制以原版重力加速度下坠！
            if (deltaY <= expectedFall) {
                safeTo.setY(to.getY());
            } else {
                safeTo.setY(from.getY() + expectedFall);
            }
        }
        moveEvent.setTo(safeTo);
    }
}
