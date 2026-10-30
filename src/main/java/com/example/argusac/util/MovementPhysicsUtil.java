package com.example.argusac.util;

/**
 * 移动物理动力学工具类
 * <p>
 * 包含速度限制计算、跳跃物理、空气动力学等核心算法
 * 供业务检测类和单元测试共同引用
 * </p>
 */
public final class MovementPhysicsUtil {

    private MovementPhysicsUtil() {
        // 工具类禁止实例化
    }

    /**
     * 计算当前移动状态下的速度限制 (核心物理动力学算法)
     *
     * @param sprinting    是否疾跑
     * @param airborne     是否在空中
     * @param jumping      是否正在跳跃
     * @param airTicks     滞空帧数
     * @param groundTicks  着地帧数
     * @param slownessAmp  缓慢药水等级 (-1表示无效果, 0=缓慢I, 1=缓慢II, 3=缓慢IV)
     * @return 当前状态下的理论速度上限 (单位: 格/tick)
     */
    public static double calculateSpeedLimit(boolean sprinting, boolean airborne, boolean jumping,
                                             int airTicks, int groundTicks, int slownessAmp) {
        double slownessFactor = 1.0;
        if (slownessAmp >= 0) {
            slownessFactor = Math.max(0.35, 1.0 - ((slownessAmp + 1) * 0.15));
        }

        double baseLimit;
        if (airborne || jumping) {
            if (airTicks <= 1 || jumping) {
                // 原版疾跑起跳冲量 (Sprint Jump Launch)
                double jumpBase = (0.24 * slownessFactor + 0.20);
                double jumpLaunchLimit = Math.max(0.40, jumpBase * 1.15);
                baseLimit = jumpLaunchLimit;
            } else if (airTicks == 2) {
                // 滞空第 2 帧
                double air2Base = (0.20 * slownessFactor + 0.18);
                double air2Limit = Math.max(0.36, air2Base * 1.12);
                baseLimit = air2Limit;
            } else {
                // 滞空中后期
                double airLateBase = Math.max(0.33, 0.36 * (0.50 + 0.50 * slownessFactor));
                baseLimit = airLateBase;
            }
        } else {
            if (groundTicks <= 2) {
                baseLimit = Math.max(0.35, 0.40 * (0.45 + 0.55 * slownessFactor));
            } else {
                double groundSprintBase = 0.2806 * slownessFactor + 0.085;
                baseLimit = Math.max(0.20, groundSprintBase);
            }
        }
        return baseLimit;
    }

    /**
     * 判断是否应该触发瞬发硬拦截 (Instant Violation)
     * <p>
     * 仅对明显脱离原版物理极限的恶劣超速进行瞬发拦截
     * </p>
     *
     * @param distance     实际移动距离
     * @param allowedLimit 允许的速度上限
     * @return true=应该瞬发拦截, false=不应瞬发拦截
     */
    public static boolean isInstantViolation(double distance, double allowedLimit) {
        double diff = distance - allowedLimit;
        return (diff >= 0.18 && distance >= 0.38) || (distance > allowedLimit * 1.60 && distance >= 0.42);
    }

    /**
     * 计算缓慢药水的速度衰减系数
     *
     * @param slownessAmp 缓慢药水等级 (-1表示无效果)
     * @return 速度衰减因子 (范围: 0.35~1.0)
     */
    public static double calculateSlownessFactor(int slownessAmp) {
        if (slownessAmp < 0) {
            return 1.0;
        }
        return Math.max(0.35, 1.0 - ((slownessAmp + 1) * 0.15));
    }

    /**
     * 计算空气动力学速度衰减
     *
     * @param baseSpeed 基础速度
     * @param airTicks  滞空帧数
     * @return 衰减后的速度
     */
    public static double applyAirResistance(double baseSpeed, int airTicks) {
        // 原版空气阻力恒为 0.91
        double airDrag = 0.91;
        return baseSpeed * Math.pow(airDrag, airTicks);
    }
}
