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
    private double horizontalTolerance = 0.012;
    private double verticalTolerance = 0.015;
    private double maxBuffer = 2.0;
    private double bufferDecay = 0.10;

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
        this.horizontalTolerance = plugin.getConfig().getDouble("prediction.horizontal-tolerance", 0.012);
        this.verticalTolerance = plugin.getConfig().getDouble("prediction.vertical-tolerance", 0.015);
        this.maxBuffer = plugin.getConfig().getDouble("prediction.max-buffer", 2.0);
        this.bufferDecay = plugin.getConfig().getDouble("prediction.buffer-decay", 0.10);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public double getHorizontalTolerance() {
        return horizontalTolerance;
    }

    public double getVerticalTolerance() {
        return verticalTolerance;
    }

    public double getMaxBuffer() {
        return maxBuffer;
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

        com.example.argusac.setback.PlayerSetbackContext setbackContext =
                plugin.getSetbackManager() != null ? plugin.getSetbackManager().getContext(player.getUniqueId()) : null;
        boolean underViolationLock = setbackContext != null && (setbackContext.isTeleportInFlight() || setbackContext.isUnderViolationLock());

        // 仅在非违规锁定期才允许合法高机动期与登录豁免期豁免
        if (!underViolationLock && (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod())) {
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
        Location targetLoc = new Location(player.getWorld(), toX, toY, toZ, yaw, pitch);

        // 气泡柱 (Bubble Column) 产生极端垂直强推力，予以预测豁免
        if (isBubbleColumn(currentLoc) || isBubbleColumn(targetLoc)) {
            return PredictionResult.exempt();
        }

        // 1. 探查环境物理参数与摩擦力
        float blockFriction = getBlockFriction(currentLoc, adapter);
        boolean inLiquid = adapter.isInWater(player) || adapter.isSwimming(player);
        if (!inLiquid) {
            Block feet = currentLoc.getBlock();
            Block head = currentLoc.clone().add(0, 1.0, 0).getBlock();
            if (adapter.isLiquid(feet) || adapter.isLiquid(head)) {
                inLiquid = true;
            }
        }
        int depthStrider = getDepthStriderLevel(player);
        boolean isSwimming = adapter.isSwimming(player);
        boolean inWeb = isBlockInWeb(currentLoc);

        // 2. 计算玩家的基础速度与药水修饰符 (动态支持属性加成与 walkSpeed 技能/指令修改)
        double defaultWalkSpeedFactor = player.getWalkSpeed() / 0.20;
        double baseSpeed = adapter.getAttributeValue(player, com.example.argusac.compat.CompatAttribute.GENERIC_MOVEMENT_SPEED, 0.10) * defaultWalkSpeedFactor;
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
        double radPitch = Math.toRadians(pitch);

        double prevVx = state.getPrevVx();
        double prevVz = state.getPrevVz();

        // 地面摩擦因子与阻力因子构建 (全面适配：水中阻尼、空中阻尼、地面摩擦力以及落地触地帧)
        double groundFriction = blockFriction * 0.91;
        double[] candidateFrictions;
        if (inLiquid) {
            // 水中原版标准阻尼为 0.80；深海眷顾/海豚恩惠/水底触地时阻尼可提升至 0.85~0.96
            if (depthStrider > 0 || adapter.hasPotionEffect(player, CompatPotionType.DOLPHINS_GRACE)) {
                candidateFrictions = new double[]{0.80, 0.85, 0.91, 0.96, groundFriction};
            } else if (state.wasOnGround() || clientOnGround || data.isOnGround()) {
                candidateFrictions = new double[]{0.80, 0.85, 0.91, groundFriction};
            } else {
                candidateFrictions = new double[]{0.80, 0.85, 0.91};
            }
        } else if (state.wasOnGround() || state.isJumpedLastTick() || clientOnGround || data.isOnGround()) {
            // 关键物理：涵盖地面推进、起跳首空中帧、以及下落落地瞬间的触地阻尼，彻底消除落地帧 0.18+ 的虚假残差
            candidateFrictions = new double[]{groundFriction, groundFriction * 0.91, 0.91};
        } else {
            // 纯空中下落
            candidateFrictions = new double[]{0.91};
        }

        // 起跳候选综合判定：包括常规地面起跳、落地即弹起的连跳 (Bunny-Hop)、以及起跳垂直初速度特征 (0.35~0.48)
        boolean isJumpCandidate = (state.wasOnGround() && !clientOnGround && deltaY > 0.05)
                || (!state.wasOnGround() && (clientOnGround || data.isOnGround()) && deltaY > 0.05)
                || (deltaY >= 0.35 && deltaY <= 0.48);

        // 上一帧动量候选：包含持续惯性动量与原地起步/重新起步 (零动量) 候选，彻底消除静止起步第一步误判
        double[][] prevMomentums = {
                {prevVx, prevVz},
                {0.0, 0.0}
        };

        // 枚举水平按键与机动状态空间
        for (double[] input : INPUT_VECTORS) {
            double strafe = input[0];
            double forward = input[1];

            for (boolean sprint : sprintStates) {
                for (boolean sneak : sneakStates) {
                    for (double frictionH : candidateFrictions) {
                        double currentMoveSpeed = baseSpeed;
                        if (sprint) {
                            currentMoveSpeed *= 1.30;
                        }
                        if (sneak) {
                            currentMoveSpeed *= 0.30;
                        }

                        // 计算加速度：原版 Minecraft travel() 加速度公式分子为 0.21600002F (即 0.6^3)
                        // 修正分子避免地面加速度被错误削减 33%，彻底消除正常走路误报
                        double accH;
                        if (state.wasOnGround() && !inLiquid) {
                            accH = currentMoveSpeed * (0.21600002 / (frictionH * frictionH * frictionH));
                        } else if (inLiquid) {
                            // 水中基础加速度 0.02，疾跑/游泳为 0.04~0.06，深海眷顾额外提升
                            double baseWaterAcc = (sprint || isSwimming) ? 0.045 : 0.02;
                            if (depthStrider > 0) {
                                baseWaterAcc += depthStrider * 0.025;
                            }
                            accH = baseWaterAcc;
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

                        // 起跳冲量候选：常规起跳或连跳 Bunny-Hop 时可能具备疾跑视角冲量 (0.2)
                        boolean[] jumpBonusStates = isJumpCandidate ? new boolean[]{false, true} : new boolean[]{false};
                        for (boolean hasJumpBonus : jumpBonusStates) {
                            double jumpBonusX = (hasJumpBonus && sprint) ? -sinYaw * 0.2 : 0.0;
                            double jumpBonusZ = (hasJumpBonus && sprint) ? cosYaw * 0.2 : 0.0;

                            for (double[] pMotion : prevMomentums) {
                                double pVx = pMotion[0];
                                double pVz = pMotion[1];

                                double candVx = pVx * frictionH + ax + jumpBonusX;
                                double candVz = pVz * frictionH + az + jumpBonusZ;

                                // 蜘蛛网极大阻尼
                                if (inWeb) {
                                    candVx *= 0.25;
                                    candVz *= 0.25;
                                }

                                // 候选 1: 自由无阻挡全速位移
                                candidateVelocities.add(new Vector(candVx, 0.0, candVz));

                                // 候选 2: 贴墙行走 / 撞墙受阻 (Wall Collision / Sliding) 分解候选，彻底杜绝蹭墙、顶墙、进门受阻误判
                                candidateVelocities.add(new Vector(0.0, 0.0, candVz)); // X 轴受阻
                                candidateVelocities.add(new Vector(candVx, 0.0, 0.0)); // Z 轴受阻
                                candidateVelocities.add(new Vector(0.0, 0.0, 0.0));    // 双轴受阻完全停滞

                                // 若处于 3D 游泳状态，叠加视角方向推力候选
                                if (isSwimming) {
                                    double swimLookX = -sinYaw * Math.cos(radPitch) * 0.04;
                                    double swimLookZ = cosYaw * Math.cos(radPitch) * 0.04;
                                    candidateVelocities.add(new Vector(candVx + swimLookX, 0.0, candVz + swimLookZ));
                                    candidateVelocities.add(new Vector(0.0, 0.0, candVz + swimLookZ));
                                    candidateVelocities.add(new Vector(candVx + swimLookX, 0.0, 0.0));
                                }

                                // 叠加待处理击退
                                for (PendingVelocity pv : state.getPendingVelocities()) {
                                    candidateVelocities.add(new Vector(candVx + pv.getX(), 0.0, candVz + pv.getZ()));
                                    candidateVelocities.add(new Vector(pv.getX(), 0.0, candVz + pv.getZ()));
                                    candidateVelocities.add(new Vector(candVx + pv.getX(), 0.0, pv.getZ()));
                                }
                            }
                        }
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
        int jumpBoostAmp = adapter.getPotionAmplifier(player, CompatPotionType.JUMP_BOOST);
        double jumpInitialVy = 0.42;
        if (jumpBoostAmp >= 0) {
            jumpInitialVy += 0.1 * (jumpBoostAmp + 1);
        }

        // 空中运动重力加速度
        double gravityVy = (prevVy - 0.08) * 0.98;
        if (adapter.hasPotionEffect(player, CompatPotionType.SLOW_FALLING)) {
            gravityVy = Math.max(gravityVy, -0.05);
        }
        candidateVy.add(gravityVy);
        candidateVy.add(0.0);       // 水平滞留、跳跃顶点速度过零归零截断或头顶撞天花板
        candidateVy.add(-0.0784);   // 离地首帧自然重力下落速度

        // 天花板/头顶方块碰撞截断 (Ceiling Collision Truncation)
        // 上升跳跃中若头顶碰撞方块，向上速度自然被天花板截断
        if (deltaY >= 0.0 && deltaY <= jumpInitialVy) {
            Block headBlock = currentLoc.clone().add(0, 2.0, 0).getBlock();
            if (adapter.isSolidBlock(headBlock) || adapter.isStandable(headBlock)) {
                candidateVy.add(deltaY);
            }
        }

        // 地面行走、台阶、缓坡与地形起伏候选
        if (state.wasOnGround() || clientOnGround || data.isOnGround()) {
            // 斜坡、下坡与地形过渡连续梯度候选
            double[] slopes = {-0.05, -0.0784, -0.10, -0.15, -0.20, -0.25, -0.30, -0.35, -0.40, -0.45};
            for (double s : slopes) {
                candidateVy.add(s);
            }

            // 台阶提升 (Step-Up) 与各种非完整方块高度
            double[] stepUps = {
                    0.0625,  // 耕地 / 草径 (1/16)
                    0.125,   // 雪层 1 层 (1/8)
                    0.1875,  // 3/16
                    0.25,    // 雪层 2 层 (1/4)
                    0.3125,  // 5/16
                    0.375,   // 雪层 3 层 (3/8)
                    0.4375,  // 蛋糕 (7/16)
                    0.50,    // 半砖 / 楼梯 Step-Up (1/2)
                    0.5625,  // 床 (9/16)
                    0.60,    // 原版台阶提升上限容差
                    0.625,   // 漏斗 / 雪层 4 层 (5/8)
                    0.75,    // 附魔台 / 雪层 6 层 (3/4)
                    0.875    // 雪层 7 层 (7/8)
            };
            for (double up : stepUps) {
                candidateVy.add(up);
                candidateVy.add(-up); // 走下台阶 Step-Down
            }

            // 起跳候选 (普通起跳、半砖/台阶起跳)
            if (!adapter.isHoney(underBlock)) {
                candidateVy.add(jumpInitialVy);
                candidateVy.add(jumpInitialVy + 0.0625);
                candidateVy.add(jumpInitialVy + 0.125);
                candidateVy.add(jumpInitialVy + 0.25);
                candidateVy.add(jumpInitialVy + 0.50);
            }

            // 落地瞬间几何碰撞截断 (Ground Collision Truncation)
            // 当从空中下落且当前接触地面时，垂直位移受地面方块几何表面阻挡，位移自然截断在 [gravityVy - 0.05, 0.0] 范围内
            if (deltaY <= 0.0 && deltaY >= gravityVy - 0.05) {
                candidateVy.add(deltaY);
            }
        } else {
            // 空中连续跳跃起跳 (Bunny Hop)
            candidateVy.add(jumpInitialVy);
            // 空中下坡微下切与空中落地缓冲
            candidateVy.add(-0.15);
            candidateVy.add(-0.25);
            candidateVy.add(-0.50);
        }

        // 液体中的完整运动学模型 (按空格向上游泳、加速游动、跃出水面、自然沉降、向下潜水及 3D 游泳)
        if (inLiquid) {
            candidateVy.add((prevVy * 0.80) + 0.04);           // 原版按空格向上游动
            candidateVy.add((prevVy * 0.80) + 0.08);           // 快速向上游动 / 水面弹起
            candidateVy.add(0.30);                             // 浮力出水冲量
            candidateVy.add(jumpInitialVy);                    // 水底或水面弹跳起跳
            candidateVy.add((prevVy - 0.02) * 0.80);           // 水中重力自然沉降
            candidateVy.add((prevVy * 0.80) - 0.04);           // 潜水向下游动
            // 仅当玩家静止/微动漂浮或脚下有实体支撑时允许 0.0，杜绝在深水表面高速平奔借此卡 0 残差
            double hMoveDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            if (hMoveDist <= 0.08 || state.wasOnGround() || clientOnGround || data.isOnGround()) {
                candidateVy.add(0.0);
                candidateVy.add(-0.005);
            }
            if (isSwimming) {
                candidateVy.add(-Math.sin(radPitch) * 0.05);
                candidateVy.add(-Math.sin(radPitch) * 0.12);
            }
        }

        // 爬梯或藤蔓 (脚底或上半身接触均具备攀爬能力)
        Block currentBlock = currentLoc.getBlock();
        Block upperBlock = currentLoc.clone().add(0, 1.0, 0).getBlock();
        if (adapter.isClimbable(currentBlock) || adapter.isClimbable(upperBlock)) {
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

        // 6. 动态容差与缓冲 (Buffer) 状态维护
        // 普通平地走路时基础水平容差设为 0.015；跳跃机动、滞空或液体游动时放宽动态容差至 0.030
        double dynamicHTol = Math.max(horizontalTolerance, 0.015);
        if (!clientOnGround || isJumpCandidate || inLiquid) {
            dynamicHTol = Math.max(horizontalTolerance, 0.030);
        }
        // 垂直容差：基础容差保证 0.025，跳跃、空中、上下坡或液体中动态放宽，吸收地形浮点毛刺
        double dynamicVTol = Math.max(verticalTolerance, 0.025);
        if (!clientOnGround || isJumpCandidate || Math.abs(deltaY) > 0.05) {
            dynamicVTol = Math.max(verticalTolerance, 0.040);
        }
        if (inLiquid) {
            dynamicVTol = Math.max(verticalTolerance, 0.055);
        }

        double hBuffer = state.getHorizontalBuffer();
        double vBuffer = state.getVerticalBuffer();

        if (minHResidual > dynamicHTol) {
            hBuffer += (minHResidual - dynamicHTol) * 10.0;
        } else {
            hBuffer = Math.max(0.0, hBuffer - bufferDecay);
        }

        if (minVResidual > dynamicVTol) {
            vBuffer += (minVResidual - dynamicVTol) * 10.0;
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
        state.setJumpedLastTick(isJumpCandidate);

        boolean hInvalid = (hBuffer > maxBuffer);
        boolean vInvalid = (vBuffer > maxBuffer);
        boolean invalid = hInvalid || vInvalid;
        String reason = "";
        if (invalid) {
            if (hInvalid && vInvalid) {
                reason = String.format("Residual breach H=%.4f (buf=%.2f), V=%.4f (buf=%.2f)",
                        minHResidual, hBuffer, minVResidual, vBuffer);
            } else if (hInvalid) {
                reason = String.format("Horizontal residual breach: res=%.4f, buf=%.2f, tol=%.4f",
                        minHResidual, hBuffer, dynamicHTol);
            } else {
                reason = String.format("Vertical residual breach: res=%.4f, buf=%.2f, tol=%.4f",
                        minVResidual, vBuffer, dynamicVTol);
            }
        }

        Vector fitted = new Vector(bestFittedH.getX(), bestFittedVy, bestFittedH.getZ());
        PredictionResult result = new PredictionResult(!invalid, !hInvalid, !vInvalid, minHResidual, minVResidual, hBuffer, vBuffer, fitted, reason);
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
        if (loc == null || loc.getWorld() == null) return false;
        Block feet = loc.getBlock();
        if (feet != null && feet.getType().name().contains("WEB")) return true;
        Block body = loc.clone().add(0, 1.0, 0).getBlock();
        return body != null && body.getType().name().contains("WEB");
    }

    private boolean isBubbleColumn(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        Block b = loc.getBlock();
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
