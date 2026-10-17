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
        this.maxHorizontalSpeed = plugin.getConfig().getDouble(path + "max-horizontal-speed", 0.65);
        this.iceModifier = plugin.getConfig().getDouble(path + "ice-modifier", 1.45);
        this.speedPotionMultiplier = plugin.getConfig().getDouble(path + "speed-potion-multiplier", 0.20);
        this.speedBufferMax = plugin.getConfig().getDouble(path + "speed-buffer-max", 0.25);
        this.speedBufferDecay = plugin.getConfig().getDouble(path + "speed-buffer-decay", 0.05);
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

        // Bukkit may coalesce movement over multiple server ticks during lag. Scale the
        // per-tick limit by elapsed event time, with a bounded allowance for short stalls.
        double allowedLimit = maxHorizontalSpeed;

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

        // 速度药水加成
        int speedAmp = adapter.getPotionAmplifier(player, CompatPotionType.SPEED);
        if (speedAmp >= 0) {
            allowedLimit += (speedAmp + 1) * speedPotionMultiplier;
        }

        // 缓慢药水减速约束 (防无视缓慢效果 Anti-Slowness)
        int slownessAmp = adapter.getPotionAmplifier(player, CompatPotionType.SLOWNESS);
        if (slownessAmp >= 0) {
            allowedLimit *= Math.max(0.40, 1.0 - ((slownessAmp + 1) * 0.15));
        }

        // 冰面与粘液块加速修正 (包含当前踩踏以及冰面滑行惯性记忆)
        Block under = from.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isIce(under) || adapter.isSlime(under) || data.getIceTicks() > 0) {
            allowedLimit *= iceModifier;
        } else if (adapter.isSoulSand(under) || adapter.isHoney(under)) {
            // 灵魂沙与蜂蜜块减速
            allowedLimit *= 0.65;
        }

        // 水中海豚的恩惠或三叉戟加速保守容差
        if (adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE) || adapter.isRiptiding(player)) {
            allowedLimit *= 2.0;
        }

        // 天花板压头跳 (Ceiling Sprint-Jump) 容差：头顶 2 格内有方块时碰撞导致高频弹地疾跑加速
        Block ceilingBlock = from.clone().add(0, 2.0, 0).getBlock();
        if (adapter.isSolidBlock(ceilingBlock)) {
            allowedLimit *= 1.35;
        }

        // Vertical collision/step transitions can produce short horizontal bursts even
        // while the ground probe reports grounded. Give those moves a wider envelope.
        if (verticalDistance > 0.01) {
            allowedLimit *= 1.50;
        } else if (player.isSprinting() || !data.isOnGround()) {
            allowedLimit *= 1.30;
        }
        allowedLimit *= elapsedTicks;

        // 速度阈值判定与平滑缓冲区 (Speed Violation Buffer)
        if (distance > allowedLimit) {
            double diff = distance - allowedLimit;
            double previousBuffer = data.getSpeedBuffer();
            double currentBuffer = previousBuffer + diff;

            // A single coalesced Bukkit move can be a lag/terrain outlier. Reject that
            // move immediately, but require accumulated evidence before adding violation.
            if (diff > 0.35 && previousBuffer < speedBufferMax * 0.6) {
                event.setCancelled(true);
                data.setSpeedBuffer(speedBufferMax * 0.6);
                return;
            }

            if (currentBuffer > speedBufferMax) {
                String details = String.format("dist=%.3f, max=%.3f, buf=%.3f, dy=%.3f, ticks=%.2f",
                        distance, allowedLimit, currentBuffer, to.getY() - from.getY(), elapsedTicks);
                event.setCancelled(true);
                flag(player, 1, details, event);
                // Reduce the buffer after a report to avoid one alert per move.
                data.setSpeedBuffer(currentBuffer - (speedBufferMax * 0.6));
            } else {
                // Accumulate small excesses to tolerate isolated movement jitter.
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
