package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import com.example.argusac.setback.PlayerSetbackContext;
import com.example.argusac.util.MathUtil;
import com.example.argusac.util.MovementPhysicsUtil;
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
        this.speedBufferMax = getChecksConfig().getDouble(path + "speed-buffer-max", 0.25);
        this.speedBufferDecay = getChecksConfig().getDouble(path + "speed-buffer-decay", 0.10);
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

        com.example.argusac.setback.PlayerSetbackContext setbackContext =
                plugin.getSetbackManager() != null ? plugin.getSetbackManager().getContext(player.getUniqueId()) : null;
        boolean underViolationLock = setbackContext != null && (setbackContext.isTeleportInFlight() || setbackContext.isUnderViolationLock());

        // 对标 GrimAC shouldBlockMovement：处于违规锁定期 (2000ms) 或在途传送时，严禁享受任何击退或免检豁免！
        if (!underViolationLock) {
            // 处于传送或登录豁免缓冲期，或合法高机动期、近期 1000ms 内受到真实击退/伤害
            if (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod() || data.hasRecentVelocity(1000L)) {
                data.setSpeedBuffer(0.0);
                return;
            }
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

        boolean airborne = !data.isOnGround();
        int airTicks = data.getAirTicks();

        // 原版真实物理：按空格起跳瞬间，或前一帧刚离地，或跳跃上升阶段
        boolean jumping = (to.getY() - from.getY()) > 0.03 || verticalDistance > 0.05;

        // 1. 判断真实冰面与粘液块物理环境 (必须是脚下 0.5 格内有冰方块，在 4 tick 惯性记忆期内，或处于冰面起跳滞空中)
        Block underFrom = from.clone().subtract(0, 0.5, 0).getBlock();
        Block underTo = to.clone().subtract(0, 0.5, 0).getBlock();
        boolean onIce = adapter.isIce(underFrom) || adapter.isIce(underTo)
                || adapter.isSlime(underFrom) || adapter.isSlime(underTo)
                || data.getIceTicks() > 0
                || (airborne && data.isAirborneFromIce());

        // 缓慢药水动力学衰减系数 (原版每级削减 15% 基础地面属性速度)
        int slownessAmp = adapter.getPotionAmplifier(player, CompatPotionType.SLOWNESS);
        double slownessFactor = MovementPhysicsUtil.calculateSlownessFactor(slownessAmp);

        // 2. 原版真实物理基准速度分类 (彻底阻断在普通泥土/草地上伪造滑冰 0.45+ 的 Slippery 外挂)
        double baseLimit;

        if (airborne || jumping) {
            // 空中移动阶段或起跳瞬间：
            if (airTicks <= 1 || jumping) {
                // 原版疾跑起跳冲量 (Sprint Jump Launch): 
                // 原版物理核心机制：起跳瞬间的 0.20F 水平冲量由玩家空格键物理跳跃直接产生，完全不受缓慢药水减速削减！
                // 真正受缓慢削减的仅为起跳前地面的初速度 (0.22~0.24 * slownessFactor)
                // 对标 GrimAC 物理包络：起跳首帧必须放行原版恒定 0.20F 跳跃冲量与起跳初速度，最低保障 0.40 格
                baseLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, true, airTicks, 0, slownessAmp);
                baseLimit = onIce ? Math.max(0.68, maxHorizontalSpeed * (iceModifier / 1.45 * 1.15)) : Math.min(maxHorizontalSpeed, baseLimit);
            } else if (airTicks == 2) {
                // 滞空第 2 帧：首帧经原版 0.91 空气阻力衰减与空中前进加速度，最低保障 0.36 格
                baseLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, false, airTicks, 0, slownessAmp);
                baseLimit = onIce ? (0.42 * iceModifier) : baseLimit;
            } else {
                // 滞空中后期：空气动力学主导，空中无地面摩擦力阻滞 (空气阻力恒为 0.91)，缓慢药水在空中仅弱阻尼，最低保障 0.33 格
                baseLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, false, airTicks, 0, slownessAmp);
                baseLimit = onIce ? (0.36 * iceModifier) : baseLimit;
            }
        } else {
            // 纯地面平跑阶段 (On Ground)：
            if (onIce) {
                // 真实冰面：允许滑动加速 (原版冰面疾跑约 0.40)
                baseLimit = Math.max(0.28 * slownessFactor + 0.08, 0.29 * iceModifier * slownessFactor);
            } else {
                // 普通非冰地面 (泥土、石头、木板等)：
                baseLimit = MovementPhysicsUtil.calculateSpeedLimit(player.isSprinting(), false, false, 0, data.getGroundTicks(), slownessAmp);
            }
        }

        // 灵魂沙与灵魂土减速 / 灵魂疾行 (Soul Speed) 魔咒放行
        if (adapter.isSoulSand(underFrom) || adapter.isSoulSand(underTo)) {
            int soulSpeed = adapter.getSoulSpeedLevel(player);
            if (soulSpeed > 0) {
                baseLimit *= (1.0 + soulSpeed * 0.25);
            } else {
                baseLimit *= 0.65;
            }
        } else if (adapter.isHoney(underFrom) || adapter.isHoney(underTo)) {
            baseLimit *= 0.65;
        }

        // 天花板压头跳 (Ceiling Sprint-Jump) 容差 (检测 1.8 格与 2.0 格高的压头通道，如活板门/下半砖)
        Block ceilingBlock1 = from.clone().add(0, 1.8, 0).getBlock();
        Block ceilingBlock2 = from.clone().add(0, 2.0, 0).getBlock();
        if (adapter.isSolidBlock(ceilingBlock1) || adapter.isSolidBlock(ceilingBlock2)) {
            baseLimit *= 1.35;
        }

        double allowedLimit = baseLimit;

        // 水中物理环境：深海疾行 (Depth Strider)、海豚恩惠、游泳姿态及向上游泳跃升
        if (inLiquid) {
            int depthStrider = adapter.getDepthStriderLevel(player);
            if (depthStrider > 0) {
                allowedLimit += depthStrider * 0.10;
            }
            if (adapter.isSwimming(player)) {
                allowedLimit *= 1.25;
            }
            if ((to.getY() - from.getY()) > 0.05) {
                allowedLimit *= 1.20; // 向上游泳或冲出水面海豚跳
            }
        }

        // 下台阶 / 下坡重力势能转水平动量容差 (如 64.00 -> 63.84 下降 0.16 格)
        double fallDelta = from.getY() - to.getY();
        if (fallDelta > 0.05) {
            // 原版下楼梯/半砖台阶每级下落约 0.15~0.16 格，水平位移瞬时可达 0.39~0.42
            if (fallDelta >= 0.12) {
                allowedLimit *= 1.30;
            } else {
                allowedLimit *= 1.20;
            }
        }

        // 速度药水加成 (按比例加成)
        int speedAmp = adapter.getPotionAmplifier(player, CompatPotionType.SPEED);
        if (speedAmp >= 0) {
            allowedLimit += (speedAmp + 1) * (baseLimit * speedPotionMultiplier);
        }

        // 水中海豚的恩惠或三叉戟加速保守容差
        if (adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE) || adapter.isRiptiding(player)) {
            allowedLimit *= 2.0;
        }

        // 发包聚合时间约束：最大仅允许 2.0 倍聚合，防止长断网爆发偷跑
        double boundedElapsed = Math.max(1.0, Math.min(2.0, elapsedTicks));
        allowedLimit *= boundedElapsed;

        // 速度阈值判定与平滑缓冲区 (Speed Violation Buffer)
        // 对齐 GrimAC：处于违规锁定期 (2000ms) 内执行收紧容限 (0.05)，杜绝蹭步偷跑且避免浮点微动连环拉回死循环
        double currentBufferMax = underViolationLock ? Math.max(0.05, speedBufferMax * 0.4) : speedBufferMax;
        if (distance > allowedLimit) {
            double diff = distance - allowedLimit;
            double previousBuffer = underViolationLock ? 0.0 : data.getSpeedBuffer();
            double currentBuffer = previousBuffer + diff;

            // 核心直通硬拦截机制 (Instant Hard Cut):
            // 仅对明显脱离原版物理极限的恶劣超速 (例如单帧超速 diff >= 0.18 格且总位移 >= 0.38 格，或超出允许上限 60% 且位移 >= 0.42 格)
            // 认定为确凿蓄意作弊，首帧直接拦截并回退；
            // 对于低速挪动、起步或轻微抖动 (distance < 0.38 或 diff < 0.18)，坚决由平滑缓冲区 (speedBufferMax = 0.12) 进行平滑累加判定，
            // 杜绝缓慢效果/潜行低速下因相对倍数误判首步瞬发回退！
            boolean instantViolation = MovementPhysicsUtil.isInstantViolation(distance, allowedLimit);

            if (currentBuffer > currentBufferMax || instantViolation) {
                // 超出平滑缓冲容限或触发瞬发直通拦截：坚决原地拦截，绝对不帮作弊者裁剪放行！
                event.setCancelled(true);
                String details = String.format("dist=%.3f, max=%.3f, diff=%.3f, buf=%.3f, onIce=%b, airTicks=%d, lock=%b, inst=%b",
                        distance, allowedLimit, diff, currentBuffer, onIce, airTicks, underViolationLock, instantViolation);
                flag(player, 1, details, event);
                data.setSpeedBuffer(underViolationLock ? 0.0 : Math.max(0.0, currentBuffer - (currentBufferMax * 0.5)));
            } else {
                // 仅偶发微弱抖动 (diff < 0.18) 才累加缓冲区
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
}
