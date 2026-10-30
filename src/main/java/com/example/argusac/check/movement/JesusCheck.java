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
 * 3. 水面超速奔跑 (WaterWalk Speed: 在深水水面未潜水游泳却持续以陆地疾跑速度奔跑)
 * 4. 持续水面停留 (Surface Linger: 长时间在水面上方凌空移动而不下沉，即使模拟了微小波浪)
 */
public class JesusCheck extends AbstractCheck {

    private boolean detectGroundSpoof = true;
    private double maxWaterSpeed = 0.28;
    private int maxSolidHoverTicks = 4;
    // 持续水面停留检测阈值：在水面上持续凌空移动超过此 tick 数即触发
    private int maxWaterSurfaceTicks = 15;
    // 垂直振荡重置阈值：deltaY 绝对值超过此值才视为合法跳跃/下沉
    private double verticalResetThreshold = 0.12;

    // 岸边实体支撑脱离后的冲水惯性宽限期 (毫秒)，防止从岸上对着水疾跑入水瞬间因残余初速度误判
    private static final long SOLID_SUPPORT_GRACE_MS = 250L;

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

        // 合法高机动期、登录豁免期、近期受击退 — 同时重置所有追踪
        if (data.hasAnyLegalVelocity() || data.isInGracePeriod() || data.hasRecentVelocity(1000L)) {
            resetAllWaterTracking(data);
            return;
        }

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
        Block deepUnderBlock = to.clone().subtract(0, 1.05, 0).getBlock();
        Block headBlock = to.clone().add(0, 1.0, 0).getBlock();
        double hDist = MathUtil.getHorizontalDistance(from, to);
        double deltaY = to.getY() - from.getY();

        Block fromFeetBlock = from.getBlock();
        Block fromUnderBlock = from.clone().subtract(0, 0.4, 0).getBlock();
        Block fromHeadBlock = from.clone().add(0, 1.0, 0).getBlock();
        boolean fromOnWaterSurface = adapter.isLiquid(fromUnderBlock)
                && !adapter.isLiquid(fromHeadBlock)
                && !adapter.isSwimming(player);

        boolean feetInLiquid = adapter.isLiquid(feetBlock);
        boolean underInLiquid = adapter.isLiquid(underBlock);
        boolean headInLiquid = adapter.isLiquid(headBlock);

        // 如果脚下根本没有液体，玩家已经真正离开水面 — 标记地面支撑并重置所有追踪
        if (!feetInLiquid && !underInLiquid) {
            if (data.isOnGround() || adapter.isStandable(underBlock) || adapter.isStandable(fromUnderBlock)) {
                data.markSolidSupportNow();
            }
            resetAllWaterTracking(data);
            return;
        }

        // 如果头部也在水中（深潜/全身入水）或处于气泡柱中，重置所有追踪 — 这是正常的水下活动
        if ((headInLiquid && feetInLiquid)
                || isBubbleColumn(feetBlock) || isBubbleColumn(underBlock) || isBubbleColumn(headBlock)) {
            resetAllWaterTracking(data);
            return;
        }

        // 正在游泳（1.13+ swim 姿态），重置所有追踪
        if (adapter.isSwimming(player)) {
            resetAllWaterTracking(data);
            return;
        }

        // 若脚下、水底（一格浅水）或落足点为睡莲、脚手架、活板门、地毯、半砖等可落足方块，直接判定为合法物理踩踏
        if (data.isOnGround()
                || adapter.isStandable(feetBlock) || adapter.isStandable(underBlock)
                || adapter.isStandable(deepUnderBlock) || adapter.isStandable(fromFeetBlock)
                || adapter.isStandable(fromUnderBlock)) {
            data.markSolidSupportNow();
            resetAllWaterTracking(data);
            return;
        }

        // 检查起点与终点周围及一格水深水底是否有物理承托方块 (岸边方块、一格浅水底、荷叶、半砖、台阶、船等)
        boolean hasSupport = hasSupportingSurface(to, adapter) || hasSupportingSurface(from, adapter);
        // 检查靴子是否带有冰霜行者附魔 (Frost Walker)
        boolean frostWalker = hasFrostWalker(player);

        if (hasSupport || frostWalker) {
            data.markSolidSupportNow();
            resetAllWaterTracking(data);
            return;
        }

        // 深度确认：仅在脚下 1.25 格内全为液体（非一格浅水）时才执行水上漂判定
        boolean deepWaterBelow = isAllLiquidBelow(to, adapter, 1.25);
        if (!deepWaterBelow) {
            data.markSolidSupportNow();
            resetAllWaterTracking(data);
            return;
        }

        boolean recentlyLeftSolid = (System.currentTimeMillis() - data.getLastSolidSupportTime()) < SOLID_SUPPORT_GRACE_MS;
        // 刚离开岸边冲入水中且正在正常下沉 (deltaY < -0.005) 时，属于正常入水过渡
        if (recentlyLeftSolid && deltaY < -0.005) {
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
            data.resetWaterSpeedTicks();
            return;
        }

        // ==============================================================================
        // 1. 水面虚假接地检测 (Water Ground Spoof)
        // 客户端声明处于地面 (onGround=true)，但脚下和落足点全为深水，且无荷叶/船/冰霜行者/岸边支撑
        // ==============================================================================
        if (detectGroundSpoof && fromOnWaterSurface && !adapter.isInWater(player)
                && !adapter.isSwimming(player)) {
            boolean clientClaimGround = data.isClientOnGround();
            if (clientClaimGround && !data.isOnGround() && (feetInLiquid || underInLiquid)) {
                if (!isSetbackImmune) {
                    flag(player, 2, "Water ground spoof (claiming onGround on water)", event);
                }
                data.resetWaterHoverTicks();
                return;
            }
        }

        // ==============================================================================
        // 2. 水面异常平飞锁死检测 (Solid Jesus / Flat Water Hover)
        // 玩家在水面水平移动但垂直位移锁死近零，原版水面移动必有流体浮沉起伏，绝不可能绝对平飞
        // ==============================================================================
        boolean onWaterSurface = fromOnWaterSurface && underInLiquid
                && !adapter.isLiquid(headBlock) && !adapter.isSwimming(player);
        Block ceilingBlock = to.clone().add(0, 1.85, 0).getBlock();
        boolean headBlocked = adapter.isSolidBlock(ceilingBlock);

        if (onWaterSurface && !headBlocked && hDist > 0.08
                && !adapter.hasPotionEffect(player, CompatPotionType.LEVITATION)
                && !adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)) {
            if (Math.abs(deltaY) < 0.002) {
                data.incrementWaterHoverTicks();
                if (data.getWaterHoverTicks() > maxSolidHoverTicks) {
                    if (!isSetbackImmune) {
                        flag(player, 1, String.format("Solid Jesus: flat water walk ticks=%d, hDist=%.3f, deltaY=%.4f",
                                data.getWaterHoverTicks(), hDist, deltaY), event);
                        data.setWaterHoverTicks(maxSolidHoverTicks / 2);
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
        // 处于深水水面移动时未潜水冲刺，无海豚恩惠，移动速度却持续超越水面极限速度
        // ==============================================================================
        if (onWaterSurface && !recentlyLeftSolid && !adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE)) {
            int depthStrider = adapter.getDepthStriderLevel(player);
            double allowedSpeed = (depthStrider > 0)
                    ? (0.28 + depthStrider * 0.06)
                    : maxWaterSpeed;
            // 原版玩家对着水面按住疾跑时，客户端碰水瞬间可能取消服务端 sprinting 标记但仍保持疾跑冲量，
            // 因此统一给予水面疾跑涉水系数与波浪冲击余量，并通过连续超速帧数过滤单帧冲水惯性与网络合并包
            allowedSpeed *= 1.35;
            int speedAmp = adapter.getPotionAmplifier(player, CompatPotionType.SPEED);
            if (speedAmp >= 0) {
                allowedSpeed += (speedAmp + 1) * 0.06;
            }
            allowedSpeed += 0.05;

            if (hDist > allowedSpeed && hDist > 0.36) {
                data.incrementWaterSpeedTicks();
                if (data.getWaterSpeedTicks() >= 3) {
                    if (!isSetbackImmune) {
                        flag(player, 1, String.format("WaterWalk Speed: hDist=%.3f, limit=%.3f (DS=%d, ticks=%d)",
                                hDist, allowedSpeed, depthStrider, data.getWaterSpeedTicks()), event);
                        data.setWaterSpeedTicks(1);
                    }
                }
            } else {
                data.resetWaterSpeedTicks();
            }
        } else {
            data.resetWaterSpeedTicks();
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
     * 统一重置所有水面追踪状态（仅在玩家确认离开深水水面或有实体支撑时调用）
     */
    private void resetAllWaterTracking(PlayerData data) {
        data.resetWaterTrackingState();
    }

    private boolean isBubbleColumn(Block b) {
        return b != null && b.getType().name().contains("BUBBLE_COLUMN");
    }

    private boolean isAllLiquidBelow(Location loc, VersionAdapter adapter, double maxDepth) {
        if (loc == null || loc.getWorld() == null) return false;
        for (double dy = -0.15; dy >= -maxDepth; dy -= 0.25) {
            Block b = loc.clone().add(0, dy, 0).getBlock();
            if (!adapter.isLiquid(b) || adapter.isStandable(b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查玩家周围（水平半径 0.45 格，覆盖岸边方块边缘与一格水坑）及脚下（垂直向下 1.35 格，覆盖一格深浅水水底）
     * 是否存在任何实体支撑面、台阶、睡莲、地毯或船只。
     * 解决当玩家在 Y=62.94 对着一格水疾跑时，旧逻辑仅向下检查 0.8 格 (62.14) 无法探测到 blockY=61 水底方块的 Bug。
     */
    private boolean hasSupportingSurface(Location loc, VersionAdapter adapter) {
        if (loc == null || loc.getWorld() == null) return false;
        double[][] offsets = {
                {0, 0},
                {-0.45, 0}, {0.45, 0}, {0, -0.45}, {0, 0.45},
                {-0.45, -0.45}, {0.45, -0.45}, {-0.45, 0.45}, {0.45, 0.45}
        };
        for (double[] off : offsets) {
            Location checkLoc = loc.clone().add(off[0], 0, off[1]);
            for (double dy = 0.0; dy >= -1.35; dy -= 0.27) {
                Block b = checkLoc.clone().add(0, dy, 0).getBlock();
                if (adapter.isStandable(b) && !adapter.isLiquid(b)) {
                    return true;
                }
                String name = b.getType().name();
                if (name.contains("LILY") || name.contains("CARPET") || name.contains("SLAB")
                        || name.contains("SNOW") || name.contains("FARMLAND")
                        || name.contains("PATH") || name.contains("SOUL_SAND") || name.contains("MUD")) {
                    return true;
                }
            }
        }
        // 检查周围是否存在船只 (Boat / ChestBoat / Raft) 或潜影贝 (Shulker) 等实体物理碰撞箱支撑
        try {
            for (org.bukkit.entity.Entity entity : loc.getWorld().getNearbyEntities(loc, 1.6, 1.5, 1.6)) {
                String typeName = entity.getType().name();
                if (typeName.contains("BOAT") || typeName.contains("RAFT") || typeName.contains("SHULKER")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
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
}
