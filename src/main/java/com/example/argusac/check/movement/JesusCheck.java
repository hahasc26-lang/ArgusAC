package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import com.example.argusac.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * 水上行走与水面虚假悬浮作弊检测 (JesusCheck / WaterWalk)
 * 专门检测并拦截：
 * 1. 水面虚假接地 (Water Ground Spoof: 脚下全是深水却宣称接地)
 * 2. 水面异常平飞锁死 (Solid Jesus: 在水面水平移动但垂直位移绝对为 0，无原版水波浮沉)
 * 3. 水面超速奔跑 (WaterWalk Speed: 在水面未潜水游泳却以陆地疾跑速度奔跑)
 * 4. 持续水面停留 (Surface Linger: 长时间在水面移动而不下沉，即使模拟了微小波浪)
 *
 * 【P0 修复】检测-回弹间隙利用漏洞：
 * - 引入 waterSurfaceTicks 跨检测周期累积追踪，仅在真正离开水面/深潜时重置
 * - 回弹免疫期间继续累积水面停留计数，仅推迟触发惩罚，杜绝免疫窗口内的非法位移偷跑
 * - 收紧 deltaY 重置阈值，防止通过微小振荡反复重置检测器
 */
public class JesusCheck extends AbstractCheck {

    private boolean detectGroundSpoof = true;
    private double maxWaterSpeed = 0.28;
    private int maxSolidHoverTicks = 4;
    // 持续水面停留检测阈值：在水面上持续移动超过此 tick 数即触发 (配置项)
    private int maxWaterSurfaceTicks = 15;
    // 垂直振荡重置阈值：deltaY 绝对值超过此值才视为合法跳跃/下沉 (收紧以防微振规避)
    private double verticalResetThreshold = 0.12;

    public JesusCheck(ArgusACPlugin plugin) {
        super(plugin, "Jesus", CheckType.MOVEMENT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.detectGroundSpoof = getChecksConfig().getBoolean(path + "detect-ground-spoof", true);
        this.maxWaterSpeed = getChecksConfig().getDouble(path + "max-water-speed", 0.28);
        this.maxSolidHoverTicks = getChecksConfig().getInt(path + "max-solid-hover-ticks", 4);
        this.maxWaterSurfaceTicks = getChecksConfig().getInt(path + "max-water-surface-ticks", 15);
        this.verticalResetThreshold = getChecksConfig().getDouble(path + "vertical-reset-threshold", 0.12);
    }

    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        // 创造/旁观、允许飞行、坐船/矿车等载具豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) {
            resetAllWaterTracking(data);
            return;
        }

        // 鞘翅滑翔完全移交 ElytraFly
        if (plugin.getCheckManager().isGlidingExempt(player, data)) {
            resetAllWaterTracking(data);
            return;
        }

        // 合法高机动期、登录豁免期 — 同时重置所有追踪
        if (data.hasAnyLegalVelocity() || data.isInGracePeriod()) {
            resetAllWaterTracking(data);
            return;
        }

        // 【P0 修复】回弹自免疫期：不再直接返回！
        // 免疫期间仍然执行水面状态追踪和累积计数，只是暂不触发惩罚
        // 这样作弊者无法利用回弹-检测间隙在水面上自由行动
        boolean isSetbackImmune = data.isSetbackImmune();

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            resetAllWaterTracking(data);
            return;
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 具有三叉戟激流冲刺直接豁免
        if (adapter.isRiptiding(player)) {
            resetAllWaterTracking(data);
            return;
        }

        Block feetBlock = to.getBlock();
        Block underBlock = to.clone().subtract(0, 0.4, 0).getBlock();
        Block headBlock = to.clone().add(0, 1.0, 0).getBlock();
        double hDist = MathUtil.getHorizontalDistance(from, to);
        double deltaY = to.getY() - from.getY();

        Block fromUnderBlock = from.clone().subtract(0, 0.4, 0).getBlock();
        Block fromHeadBlock = from.clone().add(0, 1.0, 0).getBlock();
        boolean fromOnWaterSurface = adapter.isLiquid(fromUnderBlock)
                && !adapter.isLiquid(fromHeadBlock)
                && !adapter.isSwimming(player);

        boolean feetInLiquid = adapter.isLiquid(feetBlock);
        boolean underInLiquid = adapter.isLiquid(underBlock);
        boolean headInLiquid = adapter.isLiquid(headBlock);

        // 如果脚下根本没有液体，玩家已经真正离开水面 — 重置所有追踪（含累积计数）
        if (!feetInLiquid && !underInLiquid) {
            resetAllWaterTracking(data);
            return;
        }

        // 如果头部也在水中（深潜/全身入水），重置所有追踪 — 这是正常的水下活动
        if (headInLiquid && adapter.isLiquid(feetBlock)) {
            resetAllWaterTracking(data);
            return;
        }

        // 正在游泳（1.13+ swim 姿态），重置所有追踪
        if (adapter.isSwimming(player)) {
            resetAllWaterTracking(data);
            return;
        }

        // 若脚下或落足点为睡莲、脚手架、活板门、地毯、半砖等可落足方块，直接判定为合法物理踩踏，重置水面追踪
        if (adapter.isStandable(feetBlock) || adapter.isStandable(underBlock) || adapter.isStandable(fromUnderBlock)) {
            resetAllWaterTracking(data);
            return;
        }

        // 检查是否有物理承托方块 (荷叶、睡莲、半砖、地毯、雪片、船、脚手架等)
        boolean hasSupport = hasSupportingSurface(to, adapter);
        // 检查靴子是否带有冰霜行者附魔 (Frost Walker)
        boolean frostWalker = hasFrostWalker(player);

        // 如果有荷叶或冰霜行者支撑，完全豁免并重置
        if (hasSupport || frostWalker) {
            resetAllWaterTracking(data);
            return;
        }

        // 检查双脚是否完全未浸入水中（踏水浮空特征：feetBlock 为空气，仅脚下方块 underBlock 为水）
        boolean unsubmergedOnSurface = !feetInLiquid && underInLiquid;

        // 如果双脚已浸入水中，属于正常的水中游动/踩水下沉，重置水面凌空停留计数，避免正常游泳被误判
        if (!unsubmergedOnSurface) {
            data.resetWaterSurfaceTicks();
        } else {
            // 双脚完全不入水却能在水面上持续水平位移，属于典型的水上行走凌空特征
            data.incrementWaterSurfaceTicks();
        }

        // 收紧垂直振荡重置阈值：仅当 deltaY 超过更高的阈值时，才认定为真正的跳跃/下沉
        if (Math.abs(deltaY) > verticalResetThreshold) {
            data.resetWaterHoverTicks();
            return;
        }

        // ==============================================================================
        // 1. 水面虚假接地检测 (Water Ground Spoof)
        // 客户端声明处于地面 (onGround=true)，但脚下和落足点全为深水，且无荷叶/船/冰霜行者
        // ==============================================================================
        if (detectGroundSpoof && fromOnWaterSurface && !adapter.isInWater(player)
                && !adapter.isSwimming(player)) {
            boolean clientClaimGround = data.isClientOnGround();
            if (clientClaimGround && !data.isOnGround() && (feetInLiquid || underInLiquid)) {
                // 深度检查脚底 1.2 格内是否全为液体
                boolean allLiquidBelow = isAllLiquidBelow(to, adapter, 1.2);
                if (allLiquidBelow) {
                    if (!isSetbackImmune) {
                        flag(player, 2, "Water ground spoof (claiming onGround on water)", event);
                    }
                    data.resetWaterHoverTicks();
                    return;
                }
            }
        }

        // ==============================================================================
        // 2. 水面异常平飞锁死检测 (Solid Jesus / Flat Water Hover)
        // 玩家在水面水平移动但垂直位移锁死近零，原版水面移动必有流体浮沉起伏，绝不可能绝对平飞
        // ==============================================================================
        boolean onWaterSurface = fromOnWaterSurface && underInLiquid
                && !adapter.isLiquid(headBlock) && !adapter.isSwimming(player);
        if (onWaterSurface && hDist > 0.08
                && !adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)
                && !adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)) {
            if (Math.abs(deltaY) < 0.003) {
                data.incrementWaterHoverTicks();
                if (data.getWaterHoverTicks() > maxSolidHoverTicks) {
                    if (!isSetbackImmune) {
                        flag(player, 1, String.format("Solid Jesus: flat water walk ticks=%d, hDist=%.3f, deltaY=%.4f",
                                data.getWaterHoverTicks(), hDist, deltaY), event);
                    }
                    return;
                }
            } else {
                data.resetWaterHoverTicks();
            }
        } else if (!onWaterSurface) {
            data.resetWaterHoverTicks();
        }

        // ==============================================================================
        // 3. 水面超速行走检测 (WaterWalk Speed)
        // 处于水面移动时未潜水冲刺，无海豚恩惠，移动速度却达到甚至超越陆地疾跑速度
        // ==============================================================================
        if (onWaterSurface && !adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE)) {
            int depthStrider = getDepthStriderLevel(player);
            double allowedSpeed = (depthStrider > 0)
                    ? (0.28 + depthStrider * 0.05)
                    : maxWaterSpeed;
            if (player.isSprinting()) {
                allowedSpeed *= 1.35;
            }
            int speedAmp = adapter.getPotionAmplifier(player, CompatPotionType.SPEED);
            if (speedAmp >= 0) {
                allowedSpeed += (speedAmp + 1) * 0.06;
            }
            // 流水冲刷与表面浮沉轻微冲击容差
            allowedSpeed += 0.05;

            if (hDist > allowedSpeed && hDist > 0.32) {
                if (!isSetbackImmune) {
                    flag(player, 1, String.format("WaterWalk Speed: hDist=%.3f, limit=%.3f (DS=%d)",
                            hDist, allowedSpeed, depthStrider), event);
                }
            }
        }

        // ==============================================================================
        // 4. 持续水面凌空停留检测 (Surface Linger / WaterSurface)
        // 双脚完全不沉入水中（unsubmergedOnSurface），在水面上持续水平位移超过阈值 tick 数即判定为水上行走
        // 正常玩家涉水时双脚必沉入水中；唯有 Jesus 外挂会将碰撞箱锁在水面方块上方漂浮
        // ==============================================================================
        if (unsubmergedOnSurface && onWaterSurface && hDist > 0.04
                && !adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)
                && !adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)
                && data.getWaterSurfaceTicks() > maxWaterSurfaceTicks) {
            if (!isSetbackImmune) {
                flag(player, 2, String.format("WaterSurface: lingering on water surfaceTicks=%d, hDist=%.3f, deltaY=%.4f",
                        data.getWaterSurfaceTicks(), hDist, deltaY), event);
                // 惩罚触发后保留半数计数作为追踪缓冲，防止利用回弹间隙逃脱
                data.setWaterSurfaceTicks(maxWaterSurfaceTicks / 2);
            }
        }
    }

    /**
     * 统一重置所有水面追踪状态（仅在玩家确认完全离开水面时调用）
     */
    private void resetAllWaterTracking(PlayerData data) {
        data.resetWaterHoverTicks();
        data.resetWaterSurfaceTicks();
    }




    private boolean isAllLiquidBelow(Location loc, VersionAdapter adapter, double maxDepth) {
        if (loc == null || loc.getWorld() == null) return false;
        for (double dy = 0.0; dy >= -maxDepth; dy -= 0.25) {
            Block b = loc.clone().add(0, dy, 0).getBlock();
            if (!adapter.isLiquid(b)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasSupportingSurface(Location loc, VersionAdapter adapter) {
        if (loc == null || loc.getWorld() == null) return false;
        double[][] offsets = {
                {0, 0},
                {-0.35, 0}, {0.35, 0}, {0, -0.35}, {0, 0.35},
                {-0.35, -0.35}, {0.35, -0.35}, {-0.35, 0.35}, {0.35, 0.35}
        };
        for (double[] off : offsets) {
            Location checkLoc = loc.clone().add(off[0], 0, off[1]);
            for (double dy = 0.0; dy >= -0.8; dy -= 0.2) {
                Block b = checkLoc.clone().add(0, dy, 0).getBlock();
                if (adapter.isStandable(b) && !adapter.isLiquid(b)) {
                    return true;
                }
                String name = b.getType().name();
                if (name.contains("LILY") || name.contains("CARPET") || name.contains("SLAB")
                        || name.contains("SNOW") || name.contains("BOAT")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasFrostWalker(Player player) {
        if (player == null) return false;
        try {
            ItemStack boots = player.getInventory().getBoots();
            if (boots != null && boots.hasItemMeta()) {
                for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : boots.getEnchantments().entrySet()) {
                    String enchKey = entry.getKey().toString().toUpperCase();
                    if (enchKey.contains("FROST")) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private int getDepthStriderLevel(Player player) {
        if (player == null) return 0;
        try {
            ItemStack boots = player.getInventory().getBoots();
            if (boots != null && boots.hasItemMeta()) {
                return boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
