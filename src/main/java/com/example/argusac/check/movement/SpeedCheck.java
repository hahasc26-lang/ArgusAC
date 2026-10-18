package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.util.MathUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 移动速度检测 (SpeedCheck)
 * 检测水平移动距离 (BPS / per-tick offset)，综合考虑药水加速、冰面惯性滑行、天花板压头跳及空中起跳冲量
 * 内置 Speed Violation Buffer 机制，彻底消除单 tick 网络抖动与丢包误报
 */
public class SpeedCheck extends AbstractCheck {

    private double maxHorizontalSpeed;
    private double iceModifier;
    private double speedPotionMultiplier;
    private double speedBufferMax;
    private double speedBufferDecay;

    public SpeedCheck(ArgusACPlugin plugin) {
        super(plugin, "Speed", CheckType.MOVEMENT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.maxHorizontalSpeed = getChecksConfig().getDouble(path + "max-horizontal-speed", 0.65);
        this.iceModifier = getChecksConfig().getDouble(path + "ice-modifier", 1.45);
        this.speedPotionMultiplier = getChecksConfig().getDouble(path + "speed-potion-multiplier", 0.20);
        this.speedBufferMax = getChecksConfig().getDouble(path + "speed-buffer-max", 0.40);
        this.speedBufferDecay = getChecksConfig().getDouble(path + "speed-buffer-decay", 0.08);
    }

    /**
     * 处理玩家移动事件
     */
    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        long nowNanos = System.nanoTime();
        long previousSpeedCheckNanos = data.updateSpeedCheckTime(nowNanos);
        double elapsedTicks = previousSpeedCheckNanos == 0L
                ? 1.0
                : Math.max(1.0, Math.min(4.0,
                        (nowNanos - previousSpeedCheckNanos) / 50_000_000.0));

        // 创造/旁观模式、正在飞行、坐在载具中豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.isInsideVehicle()) {
            data.setSpeedBuffer(0.0);
            return;
        }

        // 鞘翅滑翔显式互斥：滑翔状态完全移交 ElytraFly 模块集中判定，Speed 模块不进行水平速度检测
        if (plugin.getCheckManager().isGlidingExempt(player, data)) {
            data.setSpeedBuffer(0.0);
            return;
        }

        // 处于传送或登录豁免缓冲期，或处于回退自免疫期、合法高机动期、近期 800ms 内受到击退/伤害
        if (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod() || data.hasRecentVelocity(800L)) {
            data.setSpeedBuffer(0.0);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        double distance = MathUtil.getHorizontalDistance(from, to);
        double verticalDistance = Math.abs(to.getY() - from.getY());

        boolean inLiquid = adapter.isInWater(player) || adapter.isSwimming(player);
        Block fromBlock = from.getBlock();
        Block toBlock = to.getBlock();
        Block fromHead = from.clone().add(0, 1.0, 0).getBlock();
        Block toHead = to.clone().add(0, 1.0, 0).getBlock();
        if (!inLiquid && (adapter.isLiquid(fromBlock) || adapter.isLiquid(toBlock) || adapter.isLiquid(fromHead) || adapter.isLiquid(toHead))) {
            inLiquid = true;
        }

        // 气泡柱 (Bubble Column) 剧烈垂直与紊流喷涌，豁免宏观速度阈值检测
        if (isBubbleColumn(fromBlock) || isBubbleColumn(toBlock) || isBubbleColumn(fromHead) || isBubbleColumn(toHead)) {
            data.setSpeedBuffer(0.0);
            return;
        }

        // 1. 判断真实冰面与粘液块物理环境 (必须是脚下 0.5 格内有冰方块，或在 4 tick 惯性记忆期内)
        Block underFrom = from.clone().subtract(0, 0.5, 0).getBlock();
        Block underTo = to.clone().subtract(0, 0.5, 0).getBlock();
        boolean onIce = adapter.isIce(underFrom) || adapter.isIce(underTo)
                || adapter.isSlime(underFrom) || adapter.isSlime(underTo)
                || data.getIceTicks() > 0;

        // 2. 原版真实物理基准速度分类 (彻底阻断在普通泥土/草地上伪造滑冰 0.45+ 的 Slippery 外挂)
        double baseLimit;
        boolean sprinting = player.isSprinting();
        boolean airborne = !data.isOnGround();
        int airTicks = data.getAirTicks();

        if (airborne || verticalDistance > 0.05) {
            // 空中移动阶段：
            if (airTicks <= 1) {
                // 首帧疾跑起跳冲量 (Sprint Jump Launch)
                baseLimit = onIce ? Math.max(0.75, maxHorizontalSpeed * (iceModifier / 1.45 * 1.15)) : Math.min(maxHorizontalSpeed, 0.61);
            } else {
                // 滞空中期：空气阻力 0.91 强制衰减
                baseLimit = onIce ? (0.38 * iceModifier) : 0.36;
            }
        } else {
            // 纯地面平跑阶段 (On Ground)：
            if (onIce) {
                // 真实冰面：允许滑动加速 (原版冰面疾跑约 0.40)
                baseLimit = sprinting ? (0.29 * iceModifier) : 0.28;
            } else {
                // 普通非冰地面 (泥土、石头、木板等)：
                // 原版绝对物理极限制：走路 0.215，疾跑 0.287！
                // 给予 0.02 宽限容差，绝不允许普通地面达到 0.4+ 的滑冰加速！
                baseLimit = sprinting ? 0.31 : 0.23;
            }
        }

        // 灵魂沙与蜂蜜块减速
        if (adapter.isSoulSand(underFrom) || adapter.isHoney(underFrom)) {
            baseLimit *= 0.65;
        }

        // 天花板压头跳 (Ceiling Sprint-Jump) 容差
        Block ceilingBlock = from.clone().add(0, 2.0, 0).getBlock();
        if (adapter.isSolidBlock(ceilingBlock)) {
            baseLimit *= 1.25;
        }

        double allowedLimit = baseLimit;

        // 水中物理环境：深海眷顾、游泳姿态及向上游泳/水面起跳跃升
        if (inLiquid) {
            int depthStrider = getDepthStriderLevel(player);
            if (depthStrider > 0) {
                allowedLimit += depthStrider * 0.08;
            }
            if (adapter.isSwimming(player)) {
                allowedLimit *= 1.25;
            }
            if ((to.getY() - from.getY()) > 0.05) {
                allowedLimit *= 1.20; // 向上游泳或冲出水面海豚跳
            }
        }

        // 速度药水加成 (按比例加成)
        int speedAmp = adapter.getPotionAmplifier(player, CompatPotionType.SPEED);
        if (speedAmp >= 0) {
            allowedLimit += (speedAmp + 1) * (baseLimit * speedPotionMultiplier);
        }

        // 缓慢药水减速约束 (防无视缓慢效果 Anti-Slowness)
        int slownessAmp = adapter.getPotionAmplifier(player, CompatPotionType.SLOWNESS);
        if (slownessAmp >= 0) {
            allowedLimit *= Math.max(0.40, 1.0 - ((slownessAmp + 1) * 0.15));
        }

        // 水中海豚的恩惠或三叉戟加速保守容差
        if (adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE) || adapter.isRiptiding(player)) {
            allowedLimit *= 2.0;
        }

        // 发包聚合时间约束：最大仅允许 2.0 倍聚合，防止长断网爆发偷跑
        double boundedElapsed = Math.max(1.0, Math.min(2.0, elapsedTicks));
        allowedLimit *= boundedElapsed;

        // 速度阈值判定与平滑缓冲区 (Speed Violation Buffer)
        if (distance > allowedLimit) {
            double diff = distance - allowedLimit;
            double previousBuffer = data.getSpeedBuffer();
            double currentBuffer = previousBuffer + diff;

            // 优化：低 VL 与轻微网络抖动平滑过渡 (Ping-Adaptive Jitter Smoothing)
            // 当超出量极小且未达到处罚阈值时，不强制原地急刹，而是将位移矢量等比截断至合法极值，保持前行顺畅！
            if (diff < 0.08 && currentBuffer < speedBufferMax) {
                double ratio = allowedLimit / distance;
                Location clampedTo = from.clone();
                clampedTo.setX(from.getX() + (to.getX() - from.getX()) * ratio);
                clampedTo.setZ(from.getZ() + (to.getZ() - from.getZ()) * ratio);
                clampedTo.setY(to.getY());
                clampedTo.setYaw(to.getYaw());
                clampedTo.setPitch(to.getPitch());
                event.setTo(clampedTo);
                data.setSpeedBuffer(currentBuffer);
                return;
            }

            // 像 GrimAC 一样，一旦检测到蓄意超速或达到阈值，当帧立即在事件内直接取消！
            event.setCancelled(true);

            if (currentBuffer > speedBufferMax) {
                String details = String.format("dist=%.3f, max=%.3f, buf=%.3f, onIce=%b, airTicks=%d",
                        distance, allowedLimit, currentBuffer, onIce, airTicks);
                flag(player, 1, details, event);
                data.setSpeedBuffer(currentBuffer - (speedBufferMax * 0.5));
            } else {
                data.setSpeedBuffer(currentBuffer);
            }
        } else {
            // 正常合法移动，平滑衰减速度缓冲区
            data.setSpeedBuffer(Math.max(0.0, data.getSpeedBuffer() - speedBufferDecay));
        }
    }

    private boolean isBubbleColumn(Block b) {
        return b != null && b.getType().name().contains("BUBBLE_COLUMN");
    }

    private int getDepthStriderLevel(Player player) {
        if (player == null) return 0;
        try {
            org.bukkit.inventory.ItemStack boots = player.getInventory().getBoots();
            if (boots != null && boots.hasItemMeta()) {
                return boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
