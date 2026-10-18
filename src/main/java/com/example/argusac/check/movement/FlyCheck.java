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
        this.maxAirTicks = getChecksConfig().getInt(path + "max-air-ticks", 25);
        this.verticalAscentThreshold = getChecksConfig().getDouble(path + "vertical-ascent-threshold", 0.42);
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

        // 登录/传送宽限期豁免
        if (data.isInGracePeriod()) {
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

        // 检查是否接触爬梯、藤蔓、脚手架、蜘蛛网等攀爬缓冲方块，或全身深潜入水/正常游泳
        if (isFullySubmergedOrClimbable(player, adapter, from, to)) {
            data.setAirTicks(0);
            return;
        }

        // 【P0 修复】若双脚接触水面但头部未入水且未深潜，不再无条件重置滞空计数
        // 旧逻辑：脚碰到水就重置 → 作弊者可以在水面悬浮而逃脱飞行检测
        // 新逻辑：仅在全身深潜入水（脚和头都在水中）或正在游泳时才重置
        // 水面行走状态（脚在水面、头在空中）继续作为「滞空」累积 airTicks
        if (isFullySubmergedInLiquid(player, adapter, from, to)) {
            data.setAirTicks(0);
        }

        // 检查脚下及周围是否有物理支撑面
        boolean hasGroundNear = data.isOnGround() || hasBlockBelow(from, adapter) || hasBlockBelow(to, adapter);

        // 放置方块/搭路 (Scaffold / Jump-Place) 宽限判定：
        // 玩家最近放置方块 (1200ms 内) 且脚下 1.2 格内存在实体支撑面
        boolean recentlyPlacedBlock = (System.currentTimeMillis() - data.getLastPlaceTime() < 1200L);
        if (recentlyPlacedBlock && (hasGroundNear || hasBlockBelow(to, adapter, 1.20) || hasBlockBelow(from, adapter, 1.20))) {
            hasGroundNear = true;
            data.setAirTicks(0);
        }

        if (!hasGroundNear) {
            double elapsedTicks = data.incrementAirTicks();
            if (elapsedTicks == 0.0) {
                return;
            }
            int airTicks = data.getAirTicks();

            int jumpAmp = adapter.getPotionAmplifier(player, CompatPotionType.JUMP_BOOST);
            double maxAllowedLaunchAscent = (0.65 + Math.max(0, jumpAmp + 1) * 0.15) * elapsedTicks;
            double maxAllowedAirAscent = verticalAscentThreshold
                    + (jumpAmp >= 0 ? (jumpAmp + 1) * 0.15 : 0.0);
            maxAllowedAirAscent *= elapsedTicks;

            // 1. 非法垂直向上飞升检测 (IllegalAscent)
            // 首帧起跳 (airTicks <= 1) 允许正常起跳冲量 (包含台阶/坡度起跳 Step-up，容差放宽至 0.65+);
            // 升空中期严格按原版重力递减衰减：跳跃第 5 tick 达到最高点后垂直速度必定转为负值 (下落)；
            // 若 airTicks >= 5 仍持续向上拔高 (> 0.001)，或在滞空中期垂直冲量超出递减包络线，首帧即判定为反重力/喷气飞升作弊
            boolean hasAscentExemption = adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)
                    || adapter.isRiptiding(player)
                    || adapter.isGliding(player)
                    || data.isElytraGliding()
                    || data.hasAnyLegalVelocity()
                    || data.hasRecentVelocity(1000L)
                    || (recentlyPlacedBlock && hasBlockBelow(to, adapter, 1.20));
            if (!hasAscentExemption && !data.isSetbackImmune()) {
                if (airTicks <= 1 && deltaY > maxAllowedLaunchAscent) {
                    flag(player, 2, String.format("IllegalAscent (Launch) deltaY=%.3f, max=%.3f", deltaY, maxAllowedLaunchAscent), event);
                    return;
                } else if (airTicks > 1) {
                    double allowedAscent = (airTicks >= 5)
                            ? (jumpAmp >= 0 ? (jumpAmp + 1) * 0.10 : 0.001)
                            : (Math.max(0.0, 0.42 - (airTicks - 1) * 0.08) + (jumpAmp >= 0 ? (jumpAmp + 1) * 0.15 : 0.0)) * elapsedTicks;
                    if (deltaY > allowedAscent) {
                        flag(player, 2, String.format("IllegalAscent (Air) airTicks=%d, deltaY=%.3f, maxAllowed=%.3f", airTicks, deltaY, allowedAscent), event);
                        return;
                    }
                }
            }

            // 2. 悬空滞空与异常重力下坠检测 (Hover & Anti-Gravity)
            // A. 完全平飞悬空：原版跳跃顶点仅能维持 1 tick 瞬时零点 (deltaY ~ 0.003)，必须连续 3 tick 以上滞空方才判定悬空
            // B. 慢速反重力漂浮：原版跳跃在第 5 tick 之后必定加速下坠 (第 6 tick -0.078, 第 7 tick -0.155)；
            //    必须连续 4 tick 以上未维持正常重力下坠且排除蜘蛛网、缓降、蜂蜜块、粘液反弹方可判定
            boolean slowingBlockOrBounce = isSlowingOrBounceBlock(from) || isSlowingOrBounceBlock(to) || data.getSlimeTicks() > 0;
            if (!hasAscentExemption
                    && !slowingBlockOrBounce
                    && !adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)
                    && !adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)) {
                if (Math.abs(deltaY) < 0.006) {
                    data.incrementFlatHoverTicks();
                    if (data.getFlatHoverTicks() >= 3 && airTicks >= 4) {
                        flag(player, 2, String.format("Hover (Flat) airTicks=%d, hoverTicks=%d, deltaY=%.4f",
                                airTicks, data.getFlatHoverTicks(), deltaY), event);
                        return;
                    }
                } else {
                    data.resetFlatHoverTicks();
                }

                if (deltaY > -0.05 && deltaY <= 0.0) {
                    data.incrementAntiGravityTicks();
                    if (data.getAntiGravityTicks() >= 4 && airTicks >= 6) {
                        flag(player, 2, String.format("Hover (Anti-Gravity) airTicks=%d, antiGravTicks=%d, deltaY=%.3f",
                                airTicks, data.getAntiGravityTicks(), deltaY), event);
                        return;
                    }
                } else if (deltaY < -0.05) {
                    data.resetAntiGravityTicks();
                }
            } else {
                data.resetFlatHoverTicks();
                data.resetAntiGravityTicks();
            }

        } else {
            data.setAirTicks(0);
            data.resetFlatHoverTicks();
            data.resetAntiGravityTicks();
        }
    }

    private boolean isFullySubmergedOrClimbable(Player player, VersionAdapter adapter, Location from, Location to) {
        Location[] locs = {player.getLocation(), from, to};
        for (Location l : locs) {
            if (l != null && l.getWorld() != null) {
                Block feet = l.getBlock();
                Block head = l.clone().add(0, 1.0, 0).getBlock();
                // 梯子、藤蔓、脚手架、蜘蛛网始终豁免
                if (adapter.isClimbable(feet) || adapter.isClimbable(head)) {
                    return true;
                }
                // 正常 3D 游泳或全身浸入深水豁免
                if (adapter.isSwimming(player) || (adapter.isLiquid(feet) && adapter.isLiquid(head))) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * 判断玩家是否真正深潜入水（脚和头都在液体中）或处于游泳姿态
     * 与 isFeetInLiquid 的区别：水面行走（脚在水面、头在空中）不被认定为深潜
     */
    private boolean isFullySubmergedInLiquid(Player player, VersionAdapter adapter, Location from, Location to) {
        // 游泳姿态直接认定
        if (adapter.isSwimming(player)) {
            return true;
        }
        Location[] locs = {player.getLocation(), from, to};
        for (Location l : locs) {
            if (l != null && l.getWorld() != null) {
                Block feet = l.getBlock();
                Block head = l.clone().add(0, 1.0, 0).getBlock();
                // 脚和头都在液体中 = 真正的深潜/全身入水
                if (adapter.isLiquid(feet) && adapter.isLiquid(head)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasBlockBelow(Location loc, VersionAdapter adapter) {
        return hasBlockBelow(loc, adapter, 0.60);
    }

    private boolean hasBlockBelow(Location loc, VersionAdapter adapter, double maxDepth) {
        if (loc == null || loc.getWorld() == null) return false;
        // 探测中心及边缘四角，防止方块边缘悬空误判
        double[][] points = {
                {0, 0},
                {-0.28, -0.28},
                {0.28, -0.28},
                {-0.28, 0.28},
                {0.28, 0.28}
        };
        for (double[] p : points) {
            Location checkLoc = loc.clone().add(p[0], 0, p[1]);
            for (double yOffset = -0.05; yOffset >= -maxDepth; yOffset -= 0.15) {
                Block b = checkLoc.clone().add(0, yOffset, 0).getBlock();
                if (adapter.isStandable(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSlowingOrBounceBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block b = loc.getBlock();
        String name = b.getType().name();
        if (name.contains("COBWEB") || name.contains("WEB")
                || name.contains("POWDER_SNOW")
                || name.contains("BERRY")
                || name.contains("HONEY")
                || name.contains("BUBBLE")
                || name.contains("SLIME")) {
            return true;
        }
        Block under = loc.clone().subtract(0, 0.5, 0).getBlock();
        String underName = under.getType().name();
        return underName.contains("HONEY") || underName.contains("SLIME") || underName.contains("BUBBLE");
    }
}
