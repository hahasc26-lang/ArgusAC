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

        // 1. 确定性物理移动模拟预测 (Deterministic Prediction Simulation)
        // 关键修复：仅根据水平方向是否违规 (isHorizontalInvalid) 判定，杜绝垂直方向台阶/下坡残差误导 Speed 判定
        com.example.argusac.prediction.DeterministicMovePredictor predictor = plugin.getMovePredictor();
        if (predictor != null && predictor.isEnabled()) {
            com.example.argusac.prediction.PredictionResult result = predictor.predict(
                    player, data, to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch(), data.isOnGround());
            if (!result.isHorizontalValid() && result.getHorizontalResidual() > predictor.getHorizontalTolerance() * 2.0) {
                flag(player, 1, "Predict: " + result.getReason(), event);
            }
        }

        // 2. 宏观物理阈值与环境判定 (第二道防线兜底)
        double allowedLimit = maxHorizontalSpeed;

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

        // 3. 空中加速度反常检测 (Mid-Air Acceleration / BHop)
        // 关键修复：排除疾跑跳跃起跳初期 (airTicks <= 5) 原版跳跃给予的合法 0.2 水平冲量加速区间，
        // 以及冰面滑行起跳后的长程惯性
        double lastDistance = data.getLastHorizontalDistance();
        if (data.getAirTicks() > 5 && data.getIceTicks() <= 0 && lastDistance > 0.15
                && !adapter.isRiptiding(player)
                && !adapter.isInWater(player)
                && !adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE)) {
            double maxAllowedAirSpeed = lastDistance * 0.985 + 0.08;
            if (distance > maxAllowedAirSpeed && distance > 0.48) {
                flag(player, 1, String.format("AirAccelerate dist=%.3f, prev=%.3f, limit=%.3f", distance, lastDistance, maxAllowedAirSpeed), event);
            }
        }
        data.setLastHorizontalDistance(distance);

        // 4. 速度阈值判定与平滑缓冲区 (Speed Violation Buffer)
        if (distance > allowedLimit) {
            double diff = distance - allowedLimit;

            // 若单次严重超速 (远超阈值 0.35 以上，通常为超变速齿轮或大步瞬移)，立刻标记违规
            if (diff > 0.35) {
                int vlToAdd = diff > 0.6 ? 3 : 2;
                String details = String.format("Burst dist=%.3f, max=%.3f (+%.3f)", distance, allowedLimit, diff);
                flag(player, vlToAdd, details, event);
                data.setSpeedBuffer(0.0);
            } else {
                // 微小超速先累加至缓冲区，防止丢包瞬时网络波动触发误报
                double currentBuffer = data.getSpeedBuffer() + diff;
                if (currentBuffer > speedBufferMax) {
                    String details = String.format("dist=%.3f, max=%.3f, buf=%.3f", distance, allowedLimit, currentBuffer);
                    flag(player, 1, details, event);
                    // 扣减部分 buffer 防止连续一帧一报，维持平滑防作弊拦截
                    data.setSpeedBuffer(currentBuffer - (speedBufferMax * 0.6));
                } else {
                    data.setSpeedBuffer(currentBuffer);
                }
            }
        } else {
            // 正常合法移动，平滑衰减速度缓冲区
            data.setSpeedBuffer(Math.max(0.0, data.getSpeedBuffer() - speedBufferDecay));
        }
    }
}

