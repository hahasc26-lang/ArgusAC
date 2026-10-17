package com.example.argusac.prediction;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性移动模拟预测器 (DeterministicMovePredictor)
 * 基于原版 Minecraft 运动学微分方程与状态机穷举合法按键输入空间，
 * 比对数据包位移与理论可能边界包络，计算运动残差 (Residual) 与缓冲累积 (Buffer)。
 */
public class DeterministicMovePredictor {

    private final ArgusACPlugin plugin;

    // 配置参数
    private boolean enabled = true;
    private double horizontalTolerance = 0.005;
    private double verticalTolerance = 0.005;
    private double maxBuffer = 1.0;
    private double bufferDecay = 0.05;

    // 8 个标准按键输入方向 + 静止
    private static final double[][] INPUT_VECTORS = {
            {0.0, 0.0},   // 静止
            {0.0, 1.0},   // 正前 (W)
            {0.0, -1.0},  // 正后 (S)
            {1.0, 0.0},   // 正左 (A)
            {-1.0, 0.0},  // 正右 (D)
            {1.0, 1.0},   // 前左 (W+A)
            {-1.0, 1.0},  // 前右 (W+D)
            {1.0, -1.0},  // 后左 (S+A)
            {-1.0, -1.0}  // 后右 (S+D)
    };

    public DeterministicMovePredictor(ArgusACPlugin plugin) {
        this.plugin = plugin;
        reloadConfig();
    }

    public void reloadConfig() {
        this.enabled = plugin.getConfig().getBoolean("prediction.enabled", true);
        this.horizontalTolerance = plugin.getConfig().getDouble("prediction.horizontal-tolerance", 0.005);
        this.verticalTolerance = plugin.getConfig().getDouble("prediction.vertical-tolerance", 0.005);
        this.maxBuffer = plugin.getConfig().getDouble("prediction.max-buffer", 1.0);
        this.bufferDecay = plugin.getConfig().getDouble("prediction.buffer-decay", 0.05);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 核心确定性物理预测入口
     *
     * @param player 目标玩家
     * @param data 玩家运行时数据
     * @param toX 目标 X
     * @param toY 目标 Y
     * @param toZ 目标 Z
     * @param yaw 目标视角偏航角
     * @param pitch 目标视角俯仰角
     * @param clientOnGround 客户端上报的地面状态
     * @return 预测与残差计算结果
     */
    public PredictionResult predict(Player player, PlayerData data,
                                   double toX, double toY, double toZ,
                                   float yaw, float pitch, boolean clientOnGround) {
        if (!enabled || player == null || data == null) {
            return PredictionResult.exempt();
        }

        // 创造/旁观、允许飞行、坐在载具中直接豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) {
            return PredictionResult.exempt();
        }

        // 鞘翅滑翔完全移交 ElytraFly 处理
        if (plugin.getCheckManager().isGlidingExempt(player, data)) {
            return PredictionResult.exempt();
        }

        // 回退自免疫期、合法高机动期、登录豁免期直接豁免
        if (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod()) {
            return PredictionResult.exempt();
        }

        PlayerPhysicsState state = data.getPhysicsState();
        if (state == null) {
            return PredictionResult.exempt();
        }

        // 单移动周期预测缓存命中 (同一 tick 内 SpeedCheck 与 FlyCheck 共享计算结果，防止状态机二次推进)
        if (state.hasCachedPrediction(toX, toY, toZ, yaw, pitch)) {
            return state.getCachedPrediction();
        }

        // 初次收到位置包时进行初始化，不进行残差计算
        if (!state.hasLastPosition()) {
            state.reset(toX, toY, toZ, yaw, pitch);
            return PredictionResult.exempt();
        }

        double deltaX = toX - state.getLastX();
        double deltaY = toY - state.getLastY();
        double deltaZ = toZ - state.getLastZ();

        // 清理超期击退
        state.cleanExpiredVelocities();

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        Location currentLoc = new Location(player.getWorld(), state.getLastX(), state.getLastY(), state.getLastZ(), yaw, pitch);

        // 1. 探查环境物理参数与摩擦力
        float blockFriction = getBlockFriction(currentLoc, adapter);
        boolean inLiquid = adapter.isInWater(player);
        boolean inWeb = isBlockInWeb(currentLoc);

        // 2. 计算玩家的基础速度与药水修饰符
        double baseSpeed = 0.10;
        int speedAmp = adapter.getPotionAmplifier(player, CompatPotionType.SPEED);
        if (speedAmp >= 0) {
            baseSpeed *= (1.0 + 0.20 * (speedAmp + 1));
        }
        int slownessAmp = adapter.getPotionAmplifier(player, CompatPotionType.SLOWNESS);
        if (slownessAmp >= 0) {
            baseSpeed *= Math.max(0.0, 1.0 - 0.15 * (slownessAmp + 1));
        }

        // 灵魂沙减速
        Block underBlock = currentLoc.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isSoulSand(underBlock)) {
            baseSpeed *= 0.40;
        }

        // 3. 构建理论速度候选集 (Candidate Envelope)
        List<Vector> candidateVelocities = new ArrayList<>();

        // 是否可能处于疾跑状态 (客户端可能正在疾跑或未疾跑)
        boolean[] sprintStates = {false, true};
        // 是否可能处于潜行状态
        boolean[] sneakStates = {false, true};

        double radYaw = Math.toRadians(yaw);
        double sinYaw = Math.sin(radYaw);
        double cosYaw = Math.cos(radYaw);

        double prevVx = state.getPrevVx();
        double prevVz = state.getPrevVz();

        // 地面摩擦因子与空中阻力因子
        double frictionH = state.wasOnGround() ? (blockFriction * 0.91) : 0.91;

        // 枚举水平按键与机动状态空间
        for (double[] input : INPUT_VECTORS) {
            double strafe = input[0];
            double forward = input[1];

            for (boolean sprint : sprintStates) {
                for (boolean sneak : sneakStates) {
                    double currentMoveSpeed = baseSpeed;
                    if (sprint) {
                        currentMoveSpeed *= 1.30;
                    }
                    if (sneak) {
                        currentMoveSpeed *= 0.30;
                    }

                    // 计算加速度
                    double accH;
                    if (state.wasOnGround()) {
                        accH = currentMoveSpeed * (0.16277136 / (frictionH * frictionH * frictionH));
                    } else {
                        accH = sprint ? 0.026 : 0.020;
                    }

                    double strafeAcc = strafe;
                    double forwardAcc = forward;
                    double distInput = Math.sqrt(strafe * strafe + forward * forward);
                    if (distInput >= 0.01) {
                        strafeAcc = (strafe / distInput) * accH;
                        forwardAcc = (forward / distInput) * accH;
                    }

                    // 投影至世界坐标
                    double ax = strafeAcc * cosYaw - forwardAcc * sinYaw;
                    double az = forwardAcc * cosYaw + strafeAcc * sinYaw;

                    // 若从地面起跳，疾跑起跳额外获得沿视角的冲量
                    double jumpBonusX = 0.0;
                    double jumpBonusZ = 0.0;
                    if (state.wasOnGround() && !clientOnGround && deltaY > 0.1) {
                        if (sprint) {
                            jumpBonusX = -sinYaw * 0.2;
                            jumpBonusZ = cosYaw * 0.2;
                        }
                    }

                    double candVx = prevVx * frictionH + ax + jumpBonusX;
                    double candVz = prevVz * frictionH + az + jumpBonusZ;

                    // 蜘蛛网极大阻尼
                    if (inWeb) {
                        candVx *= 0.25;
                        candVz *= 0.25;
                    }

                    // 候选基础向量
                    candidateVelocities.add(new Vector(candVx, 0.0, candVz));

                    // 叠加待处理击退
                    for (PendingVelocity pv : state.getPendingVelocities()) {
                        candidateVelocities.add(new Vector(candVx + pv.getX(), 0.0, candVz + pv.getZ()));
                    }
                }
            }
        }

        // 4. 计算水平残差 (Horizontal Residual)
        double minHResidual = Double.MAX_VALUE;
        Vector bestFittedH = new Vector(0, 0, 0);

        for (Vector cand : candidateVelocities) {
            double diffX = deltaX - cand.getX();
            double diffZ = deltaZ - cand.getZ();
            double res = Math.sqrt(diffX * diffX + diffZ * diffZ);
            if (res < minHResidual) {
                minHResidual = res;
                bestFittedH = cand;
            }
        }

        // 5. 垂直方向理论运动推算与残差计算 (Vertical Residual)
        List<Double> candidateVy = new ArrayList<>();
        double prevVy = state.getPrevVy();

        if (state.wasOnGround()) {
            // 情况 A: 保持在地面上 (微小坡度或水平移动)
            candidateVy.add(0.0);
            candidateVy.add(-0.0784); // 沿斜坡/台阶轻微下切
            // 情况 B: 起跳
            int jumpBoostAmp = adapter.getPotionAmplifier(player, CompatPotionType.JUMP_BOOST);
            double jumpInitialVy = 0.42;
            if (jumpBoostAmp >= 0) {
                jumpInitialVy += 0.1 * (jumpBoostAmp + 1);
            }
            candidateVy.add(jumpInitialVy);
            // 蜂蜜块无法起跳，若在蜂蜜块上移除起跳候选
            if (adapter.isHoney(underBlock)) {
                candidateVy.remove(jumpInitialVy);
            }
        } else {
            // 在空中：标准重力加速度
            double gravityVy = (prevVy - 0.08) * 0.98;
            if (adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)) {
                gravityVy = Math.max(gravityVy, -0.05);
            }
            candidateVy.add(gravityVy);

            // 落地瞬间 (碰撞地面垂直速度归零)
            if (clientOnGround) {
                candidateVy.add(0.0);
            }
        }

        // 液体阻力
        if (inLiquid) {
            candidateVy.add((prevVy - 0.02) * 0.80);
            candidateVy.add(0.04); // 液体中向上游动
        }

        // 爬梯或藤蔓
        Block currentBlock = currentLoc.getBlock();
        if (adapter.isClimbable(currentBlock)) {
            candidateVy.add(0.20);  // 向上攀爬
            candidateVy.add(-0.15); // 向下滑动
            candidateVy.add(0.0);   // 滞留
        }

        // 叠加待处理击退在 Y 轴的加成
        List<Double> knockbackVy = new ArrayList<>();
        for (PendingVelocity pv : state.getPendingVelocities()) {
            for (double vy : candidateVy) {
                knockbackVy.add(vy + pv.getY());
            }
        }
        candidateVy.addAll(knockbackVy);

        double minVResidual = Double.MAX_VALUE;
        double bestFittedVy = 0.0;
        for (double vy : candidateVy) {
            double res = Math.abs(deltaY - vy);
            if (res < minVResidual) {
                minVResidual = res;
                bestFittedVy = vy;
            }
        }

        // 6. 缓冲 (Buffer) 状态维护
        double hBuffer = state.getHorizontalBuffer();
        double vBuffer = state.getVerticalBuffer();

        if (minHResidual > horizontalTolerance) {
            hBuffer += (minHResidual - horizontalTolerance) * 10.0;
        } else {
            hBuffer = Math.max(0.0, hBuffer - bufferDecay);
        }

        if (minVResidual > verticalTolerance) {
            vBuffer += (minVResidual - verticalTolerance) * 10.0;
        } else {
            vBuffer = Math.max(0.0, vBuffer - bufferDecay);
        }

        state.setHorizontalBuffer(hBuffer);
        state.setVerticalBuffer(vBuffer);

        // 更新状态机的历史数据
        state.setLastX(toX);
        state.setLastY(toY);
        state.setLastZ(toZ);
        state.setLastYaw(yaw);
        state.setLastPitch(pitch);
        state.setPrevVx(deltaX); // 采用真实位移驱动下一次迭代，维持确定性收敛
        state.setPrevVy(deltaY);
        state.setPrevVz(deltaZ);
        state.setWasOnGround(clientOnGround);

        boolean invalid = (hBuffer > maxBuffer) || (vBuffer > maxBuffer);
        String reason = "";
        if (invalid) {
            if (hBuffer > maxBuffer && vBuffer > maxBuffer) {
                reason = String.format("Residual breach H=%.4f (buf=%.2f), V=%.4f (buf=%.2f)",
                        minHResidual, hBuffer, minVResidual, vBuffer);
            } else if (hBuffer > maxBuffer) {
                reason = String.format("Horizontal residual breach: res=%.4f, buf=%.2f, tol=%.4f",
                        minHResidual, hBuffer, horizontalTolerance);
            } else {
                reason = String.format("Vertical residual breach: res=%.4f, buf=%.2f, tol=%.4f",
                        minVResidual, vBuffer, verticalTolerance);
            }
        }

        Vector fitted = new Vector(bestFittedH.getX(), bestFittedVy, bestFittedH.getZ());
        PredictionResult result = new PredictionResult(!invalid, minHResidual, minVResidual, hBuffer, vBuffer, fitted, reason);
        state.cachePrediction(toX, toY, toZ, yaw, pitch, result);
        return result;
    }

    private float getBlockFriction(Location loc, VersionAdapter adapter) {
        Block under = loc.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isIce(under)) {
            // 区分普通冰、浮冰或蓝冰
            String typeName = under.getType().name();
            if (typeName.contains("BLUE_ICE")) {
                return 0.989f;
            }
            return 0.98f;
        }
        if (adapter.isSlime(under)) {
            return 0.80f;
        }
        return 0.60f;
    }

    private boolean isBlockInWeb(Location loc) {
        Block b = loc.getBlock();
        return b != null && b.getType().name().contains("WEB");
    }
}
