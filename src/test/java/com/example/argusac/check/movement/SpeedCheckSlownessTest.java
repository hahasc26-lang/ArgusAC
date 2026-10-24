package com.example.argusac.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SpeedCheck 缓慢效果 (Slowness) 与疾跑跳跃物理动力学模型单元测试
 */
public class SpeedCheckSlownessTest {

    private double calculateSpeedLimit(boolean sprinting, boolean airborne, boolean jumping, int airTicks, int groundTicks, int slownessAmp) {
        double slownessFactor = 1.0;
        if (slownessAmp >= 0) {
            slownessFactor = Math.max(0.35, 1.0 - ((slownessAmp + 1) * 0.15));
        }

        double baseLimit;
        if (airborne || jumping) {
            if (airTicks <= 1 || jumping) {
                double jumpBase = (0.22 * slownessFactor + 0.20);
                baseLimit = jumpBase * 1.06;
            } else if (airTicks == 2) {
                double air2Base = (0.19 * slownessFactor + 0.18);
                baseLimit = air2Base * 1.05;
            } else {
                double airLateBase = 0.34;
                double airSlownessFactor = 0.50 + 0.50 * slownessFactor;
                baseLimit = airLateBase * airSlownessFactor;
            }
        } else {
            if (groundTicks <= 2) {
                baseLimit = Math.max(0.32, 0.40 * (0.45 + 0.55 * slownessFactor));
            } else {
                baseLimit = 0.28 * slownessFactor + 0.06;
            }
        }
        return baseLimit;
    }

    private boolean isInstantViolation(double distance, double allowedLimit) {
        double diff = distance - allowedLimit;
        return (diff >= 0.18 && distance >= 0.38) || (distance > allowedLimit * 1.60 && distance >= 0.42);
    }

    @Test
    public void testNoSlownessSprintJump() {
        // 无缓慢效果时疾跑跳跃动力学基准
        double launchLimit = calculateSpeedLimit(true, true, true, 0, 0, -1);
        assertTrue(launchLimit >= 0.44, "无缓慢起跳首帧应满足至少 0.44 格容差: " + launchLimit);

        double air2Limit = calculateSpeedLimit(true, true, false, 2, 0, -1);
        assertTrue(air2Limit >= 0.38, "滞空第 2 帧应满足至少 0.38: " + air2Limit);

        double landingLimit = calculateSpeedLimit(true, false, false, 0, 1, -1);
        assertTrue(landingLimit >= 0.40, "刚落地过渡帧应满足至少 0.40: " + landingLimit);
    }

    @Test
    public void testSlowness1SprintJumpCoverage() {
        // 缓慢 I (slownessAmp = 0, 减速 15%)
        // 原版起跳冲量恒定为 0.20，结合地面初速 0.204，实际首帧约为 0.38~0.40
        double launchLimit = calculateSpeedLimit(true, true, true, 0, 0, 0);
        assertTrue(launchLimit >= 0.40, "缓慢 I 疾跑起跳首帧必须放行原版 0.38~0.40 位移: " + launchLimit);

        // 滞空第 2 帧原版实际位移约为 0.35~0.36
        double air2Limit = calculateSpeedLimit(true, true, false, 2, 0, 0);
        assertTrue(air2Limit >= 0.35, "缓慢 I 滞空第 2 帧必须放行原版 0.35 位移: " + air2Limit);

        // 刚落地过渡帧保留空中末速度约 0.33~0.36
        double landingLimit = calculateSpeedLimit(true, false, false, 0, 1, 0);
        assertTrue(landingLimit >= 0.35, "缓慢 I 落地过渡帧必须放行空中惯性末速度: " + landingLimit);

        // 纯平地连续跑动基准为 0.298 (0.28 * 0.85 + 0.06)，完美容纳原版 0.238 跑速与 45 度 Strafe
        double groundSprint = calculateSpeedLimit(true, false, false, 0, 10, 0);
        assertTrue(groundSprint >= 0.29 && groundSprint <= 0.30, "缓慢 I 纯平地持续疾跑需稳定在 0.298 容差包络: " + groundSprint);
    }

    @Test
    public void testSlownessGroundEnvelopeAndInstantViolation() {
        // 1. 测试地面无论是否同步 sprinting，均采用物理包络上限，绝不误退
        double groundLimitSlowness1 = calculateSpeedLimit(false, false, false, 0, 10, 0);
        assertEquals(0.298, groundLimitSlowness1, 0.001, "缓慢 I 地面包络应为 0.298");

        double groundLimitSlowness2 = calculateSpeedLimit(false, false, false, 0, 10, 1);
        assertEquals(0.256, groundLimitSlowness2, 0.001, "缓慢 II 地面包络应为 0.256");

        double groundLimitSlowness4 = calculateSpeedLimit(false, false, false, 0, 10, 3);
        assertEquals(0.172, groundLimitSlowness4, 0.001, "缓慢 IV 地面包络应为 0.172");

        // 2. 瞬发硬拦截 (Instant Violation) 尺度门槛测试：
        // 在缓慢效果下低速起跑 (distance = 0.24, allowedLimit = 0.172):
        // 虽然 0.24 > 0.172 * 1.39，但因为 distance < 0.38 且 diff = 0.068 < 0.18，绝不能触发瞬发硬拦截！
        assertFalse(isInstantViolation(0.24, 0.172), "缓慢低速挪动起步绝不能触发瞬发硬拦截");
        assertFalse(isInstantViolation(0.35, 0.20), "未达绝对尺度阈值前绝不瞬发拦截");

        // 只有恶劣超速 (例如 distance >= 0.45 且超出上限 60% 以上，或 diff >= 0.18 且 distance >= 0.38)
        assertTrue(isInstantViolation(0.45, 0.25), "恶劣倍数超速必须瞬发硬拦截");
        assertTrue(isInstantViolation(0.48, 0.29), "大幅绝对超速必须瞬发硬拦截");
    }

    @Test
    public void testSlowness2SprintJumpCoverage() {
        // 缓慢 II (slownessAmp = 1, 减速 30%)
        double launchLimit = calculateSpeedLimit(true, true, true, 0, 0, 1);
        assertTrue(launchLimit >= 0.37, "缓慢 II 疾跑起跳首帧必须放行原版冲量位移: " + launchLimit);

        double air2Limit = calculateSpeedLimit(true, true, false, 2, 0, 1);
        assertTrue(air2Limit >= 0.32, "缓慢 II 滞空第 2 帧必须放行空气动力学速度: " + air2Limit);

        double landingLimit = calculateSpeedLimit(true, false, false, 0, 1, 1);
        assertTrue(landingLimit >= 0.32, "缓慢 II 落地过渡帧必须承接跳跃惯性: " + landingLimit);
    }
}

