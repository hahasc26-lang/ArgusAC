package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 飞行与非法滞空检测 (FlyCheck)
 * 检测长时间悬空不掉落 (Hover) 及在空中无跳跃效果持续上升 (Illegal Ascent)
 */
public class FlyCheck extends AbstractCheck {

    private int maxAirTicks;
    private double verticalAscentThreshold;

    public FlyCheck(ArgusACPlugin plugin) {
        super(plugin, "Fly", CheckType.MOVEMENT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxAirTicks = plugin.getConfig().getInt(path + "max-air-ticks", 25);
        this.verticalAscentThreshold = plugin.getConfig().getDouble(path + "vertical-ascent-threshold", 0.42);
    }

    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        // 创造/旁观、允许飞行、载具豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) {
            data.setAirTicks(0);
            return;
        }

        // 鞘翅滑翔显式互斥：滑翔状态完全移交 ElytraFly 模块集中判定，Fly 模块直接跳过
        if (plugin.getCheckManager().isGlidingExempt(player, data)) {
            data.setAirTicks(0);
            return;
        }

        if (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod() || data.hasRecentVelocity(1000L)) {
            data.setAirTicks(0);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        double deltaY = to.getY() - from.getY();

        // 检查是否接触爬梯、藤蔓、脚手架、蜘蛛网、水等缓冲方块
        if (isInClimbableOrLiquid(player, adapter)) {
            data.setAirTicks(0);
            return;
        }

        // 检查脚下及周围是否有可支撑方块
        boolean hasGroundNear = hasBlockBelow(from, adapter);

        if (!hasGroundNear) {
            data.incrementAirTicks();
            int airTicks = data.getAirTicks();

            // 1. 非法垂直向上飞升检测 (在空中持续攀升且无飘浮/跳跃提升效果)
            if (deltaY > verticalAscentThreshold
                    && !adapter.hasPotionEffect(player, CompatPotionType.JUMP_BOOST)
                    && !adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)
                    && !adapter.isRiptiding(player)) {
                flag(player, 2, String.format("IllegalAscent deltaY=%.3f", deltaY), event);
                return;
            }

            // 2. 长时间悬空滞空检测 (Hover)
            if (airTicks > maxAirTicks && Math.abs(deltaY) < 0.08) {
                flag(player, 1, String.format("Hover airTicks=%d, deltaY=%.3f", airTicks, deltaY), event);
            }

            // 3. 凌空踩空气行走 (AirWalk / 平飞外挂)：滞空且无缓降药水下，垂直位移完全为 0
            if (airTicks > 8 && Math.abs(deltaY) < 0.005 && !adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)) {
                flag(player, 2, String.format("AirWalk airTicks=%d, deltaY=%.4f", airTicks, deltaY), event);
            }
        } else {
            data.setAirTicks(0);
        }
    }

    private boolean isInClimbableOrLiquid(Player player, VersionAdapter adapter) {
        Location loc = player.getLocation();
        Block b = loc.getBlock();
        return adapter.isClimbable(b) || adapter.isLiquid(b);
    }

    private boolean hasBlockBelow(Location loc, VersionAdapter adapter) {
        Location checkLoc = loc.clone();
        for (double yOffset = -0.1; yOffset >= -1.0; yOffset -= 0.3) {
            Block b = checkLoc.clone().add(0, yOffset, 0).getBlock();
            if (adapter.isSolidBlock(b)) {
                return true;
            }
        }
        return false;
    }
}
