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

        // 检查是否处于流体环境 (水、岩浆) 或攀爬方块 (梯子、藤蔓、脚手架、蜘蛛网)
        // 只要玩家处于液体中 (isInWater、脚或身体接触液体、游泳或在水面浮水踩水)，均享有原版流体浮力；
        // 流体环境下的水面行走、虚假接地由 JesusCheck 专项高精检测，FlyCheck 严禁误判流体浮动
        if (isInLiquidOrClimbable(player, adapter, from, to)) {
            data.setAirTicks(0);
            data.resetFlatHoverTicks();
            data.resetAntiGravityTicks();
            return;
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
            // 允许首帧合法上升高度：原版物理台阶跨越 (Step-up 最大 0.60 格) + 跳跃初速度 (0.42 格) 叠加为 1.02 格
            // 给予 1.05 基准容差，药水每级 +0.20 格，彻底杜绝半砖/台阶跳跃 (Step-up Jump，deltaY 约 0.86~0.92) 首帧误判
            double baseLaunchLimit = 1.05 + (jumpAmp >= 0 ? (jumpAmp + 1) * 0.20 : 0.0);
            double maxAllowedLaunchAscent = baseLaunchLimit * elapsedTicks;

            // 1. 非法垂直向上飞升检测 (IllegalAscent)
            // 首帧起跳 (airTicks <= 1) 允许正常起跳冲量 (包含台阶/坡度起跳 Step-up，容差放宽至 1.05 以容纳原版 0.86~0.92 台阶跳跃);
            // 升空中期严格按原版重力递减衰减：跳跃第 5 tick 达到最高点后垂直速度必定转为负值 (下落)；
            // 若 airTicks >= 5 仍持续向上拔高 (> 0.001)，或在滞空中期垂直冲量超出递减包络线，即判定为反重力/喷气飞升作弊
            com.example.argusac.setback.PlayerSetbackContext setbackContext =
                    plugin.getSetbackManager() != null ? plugin.getSetbackManager().getContext(player.getUniqueId()) : null;
            boolean underViolationLock = setbackContext != null && (setbackContext.isTeleportInFlight() || setbackContext.isUnderViolationLock());

            boolean hasAscentExemption = !underViolationLock && (adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)
                    || adapter.isRiptiding(player)
                    || adapter.isGliding(player)
                    || data.isElytraGliding()
                    || data.hasAnyLegalVelocity()
                    || data.hasRecentVelocity(1000L)
                    || (recentlyPlacedBlock && hasBlockBelow(to, adapter, 1.20)));
            if (!hasAscentExemption && !data.isSetbackImmune()) {
                if (airTicks <= 1 && deltaY > maxAllowedLaunchAscent) {
                    flag(player, 2, String.format("IllegalAscent (Launch) deltaY=%.3f, max=%.3f", deltaY, maxAllowedLaunchAscent), event);
                    return;
                } else if (airTicks > 1) {
                    double allowedAscent;
                    if (airTicks >= 7) {
                        // 原版跳跃在第 6 Tick 走完最高点，第 7 Tick 起垂直速度必定彻底转为负值 (下落)；
                        // 除非拥有跳跃提升药水，否则第 7 Tick 及以后严禁持续向上拔高 (> 0.001)
                        allowedAscent = (jumpAmp >= 0 ? (jumpAmp + 1) * 0.10 : 0.001);
                    } else {
                        // airTicks 2~6：平滑重力抛物线递减包络线 (原版: 2:0.333, 3:0.248, 4:0.165, 5:0.083, 6:0.003)
                        double decayAllowance = Math.max(0.02, (verticalAscentThreshold + 0.06) - (airTicks - 1) * 0.075);
                        if (jumpAmp >= 0) {
                            decayAllowance += (jumpAmp + 1) * 0.15;
                        }
                        allowedAscent = decayAllowance * elapsedTicks;
                    }
                    if (deltaY > allowedAscent) {
                        flag(player, 2, String.format("IllegalAscent (Air) airTicks=%d, deltaY=%.3f, maxAllowed=%.3f", airTicks, deltaY, allowedAscent), event);
                        return;
                    }
                }
            }

            // 2. 悬空滞空与异常重力下坠检测 (Hover & Anti-Gravity)
            // A. 完全平飞悬空：连续 3 tick (150ms) 保持绝对平飞即刻定身回退，杜绝跳跃顶点 1~2 tick 物理微调误判
            // B. 慢速反重力漂浮：原版跳跃在第 5 tick 之后必定加速下坠；滞空 5 tick 且连续 3 tick 未维持正常重力下坠当场拉回
            boolean slowingBlockOrBounce = isSlowingOrBounceBlock(from) || isSlowingOrBounceBlock(to) || data.getSlimeTicks() > 0;
            if (!hasAscentExemption
                    && !slowingBlockOrBounce
                    && !adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)
                    && !adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)) {
                if (Math.abs(deltaY) < 0.006) {
                    data.incrementFlatHoverTicks();
                    if (data.getFlatHoverTicks() >= 3 && airTicks >= 3) {
                        flag(player, 2, String.format("Hover (Flat) airTicks=%d, hoverTicks=%d, deltaY=%.4f",
                                airTicks, data.getFlatHoverTicks(), deltaY), event);
                        return;
                    }
                } else {
                    data.resetFlatHoverTicks();
                }

                if (deltaY > -0.05 && deltaY <= 0.0) {
                    data.incrementAntiGravityTicks();
                    if (data.getAntiGravityTicks() >= 3 && airTicks >= 7) {
                        flag(player, 2, String.format("Hover (Anti-Gravity) airTicks=%d, antiGravTicks=%d, deltaY=%.3f",
                                airTicks, data.getAntiGravityTicks(), deltaY), event);
                        return;
                    }
                } else if (deltaY < -0.05) {
                    data.resetAntiGravityTicks();
                }

                // C. 超长无支撑滞空检测 (MaxAirTicks，即时拦截超长悬停)
                if (airTicks > maxAirTicks && deltaY >= -0.05) {
                    flag(player, 2, String.format("Hover (MaxAirTicks) airTicks=%d, max=%d, deltaY=%.3f",
                            airTicks, maxAirTicks, deltaY), event);
                    return;
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

    private boolean isInLiquidOrClimbable(Player player, VersionAdapter adapter, Location from, Location to) {
        if (adapter.isInWater(player) || adapter.isSwimming(player)) {
            return true;
        }
        Location[] locs = {player.getLocation(), from, to};
        for (Location l : locs) {
            if (l != null && l.getWorld() != null) {
                Block feet = l.getBlock();
                Block head = l.clone().add(0, 1.0, 0).getBlock();
                Block under = l.clone().subtract(0, 0.4, 0).getBlock();
                // 梯子、藤蔓、脚手架、蜘蛛网始终豁免
                if (adapter.isClimbable(feet) || adapter.isClimbable(head)) {
                    return true;
                }
                // 身体、头部处于液体中，或双脚浸水/在水面踩水起跳
                if (adapter.isLiquid(feet) || adapter.isLiquid(head) || adapter.isLiquid(under)) {
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
            for (double yOffset = 0.0; yOffset >= -maxDepth; yOffset -= 0.15) {
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

    public int getMaxAirTicks() {
        return maxAirTicks;
    }

    public double getVerticalAscentThreshold() {
        return verticalAscentThreshold;
    }
}
