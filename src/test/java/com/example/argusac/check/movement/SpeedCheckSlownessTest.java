package com.example.argusac.check.movement;

import com.example.argusac.util.MovementPhysicsUtil;
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
                double jumpBase = (0.24 * slownessFactor + 0.20);
                double jumpLaunchLimit = Math.max(0.40, jumpBase * 1.15);
                baseLimit = jumpLaunchLimit;
            } else if (airTicks == 2) {
                double air2Base = (0.20 * slownessFactor + 0.18);
                double air2Limit = Math.max(0.36, air2Base * 1.12);
                baseLimit = air2Limit;
            } else {
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

    private boolean isInstantViolation(double distance, double allowedLimit) {
        double diff = distance - allowedLimit;
        return (diff >= 0.18 && distance >= 0.38) || (distance > allowedLimit * 1.60 && distance >= 0.42);
    }

    @Test
    public void testNoSlownessSprintJump() {
        // 无缓慢效果时疾跑跳跃动力学基准
        double launchLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, true, 0, 0, -1);
        assertTrue(launchLimit >= 0.44, "无缓慢起跳首帧应满足至少 0.44 格容差: " + launchLimit);

        double air2Limit = MovementPhysicsUtil.calculateSpeedLimit(true, true, false, 2, 0, -1);
        assertTrue(air2Limit >= 0.38, "滞空第 2 帧应满足至少 0.38: " + air2Limit);

        double landingLimit = MovementPhysicsUtil.calculateSpeedLimit(true, false, false, 0, 1, -1);
        assertTrue(landingLimit >= 0.40, "刚落地过渡帧应满足至少 0.40: " + landingLimit);
    }

    @Test
    public void testSlowness1SprintJumpCoverage() {
        // 缓慢 I (slownessAmp = 0, 减速 15%)
        // 原版起跳冲量恒定为 0.20，结合地面初速 0.204，实际首帧约为 0.38~0.40
        double launchLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, true, 0, 0, 0);
        assertTrue(launchLimit >= 0.40, "缓慢 I 疾跑起跳首帧必须放行原版 0.38~0.40 位移: " + launchLimit);

        // 滞空第 2 帧原版实际位移约为 0.35~0.36
        double air2Limit = MovementPhysicsUtil.calculateSpeedLimit(true, true, false, 2, 0, 0);
        assertTrue(air2Limit >= 0.35, "缓慢 I 滞空第 2 帧必须放行原版 0.35 位移: " + air2Limit);

        // 刚落地过渡帧保留空中末速度约 0.33~0.36
        double landingLimit = MovementPhysicsUtil.calculateSpeedLimit(true, false, false, 0, 1, 0);
        assertTrue(landingLimit >= 0.35, "缓慢 I 落地过渡帧必须放行空中惯性末速度: " + landingLimit);

        // 纯平地连续跑动基准为 0.3235 (0.2806 * 0.85 + 0.085)，完美容纳原版 0.238 跑速与 45 度 Strafe
        double groundSprint = MovementPhysicsUtil.calculateSpeedLimit(true, false, false, 0, 10, 0);
        assertTrue(groundSprint >= 0.32 && groundSprint <= 0.33, "缓慢 I 纯平地持续疾跑需稳定在 0.323 容差包络: " + groundSprint);
    }

    @Test
    public void testSlownessGroundEnvelopeAndInstantViolation() {
        // 1. 测试地面无论是否同步 sprinting，均采用物理包络上限，绝不误退
        double groundLimitSlowness1 = MovementPhysicsUtil.calculateSpeedLimit(false, false, false, 0, 10, 0);
        assertTrue(groundLimitSlowness1 >= 0.32, "缓慢 I 地面包络应至少为 0.32: " + groundLimitSlowness1);

        double groundLimitSlowness2 = MovementPhysicsUtil.calculateSpeedLimit(false, false, false, 0, 10, 1);
        assertTrue(groundLimitSlowness2 >= 0.28, "缓慢 II 地面包络应至少为 0.28: " + groundLimitSlowness2);

        double groundLimitSlowness4 = MovementPhysicsUtil.calculateSpeedLimit(false, false, false, 0, 10, 3);
        assertTrue(groundLimitSlowness4 >= 0.197, "缓慢 IV 地面包络应至少达到 0.197: " + groundLimitSlowness4);

        // 2. 瞬发硬拦截 (Instant Violation) 尺度门槛测试：
        // 在缓慢效果下低速起跑 (distance = 0.24, allowedLimit = 0.197):
        assertFalse(MovementPhysicsUtil.isInstantViolation(0.24, 0.197), "缓慢低速挪动起步绝不能触发瞬发硬拦截");
        assertFalse(MovementPhysicsUtil.isInstantViolation(0.35, 0.20), "未达绝对尺度阈值前绝不瞬发拦截");

        // 只有恶劣超速 (例如 distance >= 0.45 且超出上限 60% 以上，或 diff >= 0.18 且 distance >= 0.38)
        assertTrue(MovementPhysicsUtil.isInstantViolation(0.45, 0.25), "恶劣倍数超速必须瞬发硬拦截");
        assertTrue(MovementPhysicsUtil.isInstantViolation(0.48, 0.29), "大幅绝对超速必须瞬发硬拦截");
    }

    @Test
    public void testSlowness4SprintJumpCoverage() {
        // 缓慢 IV (slownessAmp = 3, 减速 60%)
        // 原版起跳冲量恒定为 0.20F，起跳首帧必须放行至少 0.40 格
        double launchLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, true, 0, 0, 3);
        assertTrue(launchLimit >= 0.40, "缓慢 IV 起跳首帧必须至少达到 0.40: " + launchLimit);

        // 滞空第 2 帧必须放行至少 0.36 格
        double air2Limit = MovementPhysicsUtil.calculateSpeedLimit(true, true, false, 2, 0, 3);
        assertTrue(air2Limit >= 0.36, "缓慢 IV 滞空第 2 帧必须至少达到 0.36: " + air2Limit);

        // 滞空中后期滑行必须放行至少 0.33 格
        double airLateLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, false, 4, 0, 3);
        assertTrue(airLateLimit >= 0.33, "缓慢 IV 滞空中后期滑行必须至少达到 0.33: " + airLateLimit);

        // 落地过渡帧必须放行至少 0.35 格
        double landingLimit = MovementPhysicsUtil.calculateSpeedLimit(true, false, false, 0, 1, 3);
        assertTrue(landingLimit >= 0.35, "缓慢 IV 落地过渡帧必须放行至少 0.35: " + landingLimit);

        // 平地疾跑实测为 0.1755，必须在 0.197+ 容限内完全放行
        double groundSprint = MovementPhysicsUtil.calculateSpeedLimit(true, false, false, 0, 10, 3);
        assertTrue(groundSprint >= 0.195, "缓慢 IV 地面必须放行原版 0.1755 疾跑: " + groundSprint);
    }

    @Test
    public void testSlowness2SprintJumpCoverage() {
        // 缓慢 II (slownessAmp = 1, 减速 30%)
        double launchLimit = MovementPhysicsUtil.calculateSpeedLimit(true, true, true, 0, 0, 1);
        assertTrue(launchLimit >= 0.37, "缓慢 II 疾跑起跳首帧必须放行原版冲量位移: " + launchLimit);

        double air2Limit = MovementPhysicsUtil.calculateSpeedLimit(true, true, false, 2, 0, 1);
        assertTrue(air2Limit >= 0.32, "缓慢 II 滞空第 2 帧必须放行空气动力学速度: " + air2Limit);

        double landingLimit = MovementPhysicsUtil.calculateSpeedLimit(true, false, false, 0, 1, 1);
        assertTrue(landingLimit >= 0.32, "缓慢 II 落地过渡帧必须承接跳跃惯性: " + landingLimit);
    }
}

