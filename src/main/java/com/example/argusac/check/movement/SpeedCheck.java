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
 * 检测水平移动距离 (BPS / per-tick offset)，综合考虑药水加速、冰面滑行、疾跑跳跃容差
 */
public class SpeedCheck extends AbstractCheck {

    private double maxHorizontalSpeed;
    private double iceModifier;
    private double speedPotionMultiplier;

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
            return;
        }

        // 鞘翅滑翔显式互斥：滑翔状态完全移交 ElytraFly 模块集中判定，Speed 模块不进行水平速度检测
        if (plugin.getCheckManager().isGlidingExempt(player, data)) {
            return;
        }

        // 处于传送或登录豁免缓冲期，或处于回退自免疫期、合法高机动期、近期 800ms 内受到击退/伤害
        if (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod() || data.hasRecentVelocity(800L)) {
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
        com.example.argusac.prediction.DeterministicMovePredictor predictor = plugin.getMovePredictor();
        if (predictor != null && predictor.isEnabled()) {
            com.example.argusac.prediction.PredictionResult result = predictor.predict(
                    player, data, to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch(), data.isOnGround());
            if (!result.isValid() && result.getHorizontalResidual() > 0.005) {
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

        // 冰面与粘液块加速修正 (检测脚下方块及再下一格方块)
        Block under = from.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isIce(under) || adapter.isSlime(under)) {
            allowedLimit *= iceModifier;
        } else if (adapter.isSoulSand(under) || adapter.isHoney(under)) {
            // 灵魂沙与蜂蜜块减速
            allowedLimit *= 0.65;
        }

        // 水中海豚的恩惠或三叉戟加速保守容差
        if (adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE) || adapter.isRiptiding(player)) {
            allowedLimit *= 2.0;
        }

        // 空中加速度反常检测 (Mid-Air Acceleration / BHop)：在空中无地面摩擦力时速度非法激增
        double lastDistance = data.getLastHorizontalDistance();
        if (data.getAirTicks() > 2 && lastDistance > 0.15
                && !adapter.isRiptiding(player)
                && !adapter.isInWater(player)
                && !adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE)) {
            double maxAllowedAirSpeed = lastDistance * 0.98 + 0.05;
            if (distance > maxAllowedAirSpeed && distance > 0.40) {
                flag(player, 1, String.format("AirAccelerate dist=%.3f, prev=%.3f, limit=%.3f", distance, lastDistance, maxAllowedAirSpeed), event);
            }
        }
        data.setLastHorizontalDistance(distance);

        if (distance > allowedLimit) {
            double diff = distance - allowedLimit;
            // 阶梯式递增 VL，防止轻微丢包瞬间误封
            int vlToAdd = diff > 0.3 ? 2 : 1;
            String details = String.format("dist=%.3f, max=%.3f", distance, allowedLimit);
            flag(player, vlToAdd, details, event);
        }
    }
}
