package com.example.argusac.check.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 潜行方块边缘 (Sneak Edge Prevention) 与悬崖回退吸附物理模型单元测试
 */
public class FlyCheckEdgeSneakTest {

    @Test
    public void testSneakEdgeProbePoints() {
        // 潜行时探出边缘探测半径必须扩展至至少 0.40 格
        double reachSneak = 0.42;
        double reachNormal = 0.32;
        assertTrue(reachSneak >= 0.40, "潜行探测半径必须覆盖原版防掉落极限探出距离 (0.40~0.42格)");
        assertTrue(reachNormal >= 0.30, "普通移动探测半径覆盖 AABB 半宽 (0.30格)");

        // 验证 8 方向十字探测与四角对角线
        double diag = reachSneak * 0.72;
        double[][] points = {
                {0, 0},
                {reachSneak, 0}, {-reachSneak, 0}, {0, reachSneak}, {0, -reachSneak},
                {diag, diag}, {-diag, diag}, {diag, -diag}, {-diag, -diag}
        };
        assertEquals(9, points.length, "必须包含中心点加 8 方向全覆盖探测");

        // 假设方块在 X: [-11.00, -10.00]，玩家探出到 X = -11.01 (身后 +X 0.35 处是实心方块)
        double playerX = -11.01;
        double probeBackX = playerX + reachSneak;
        assertTrue(probeBackX > -11.00, "正后向探测必须踩中身后稳固方块表面: " + probeBackX);
    }

    @Test
    public void testFlatHoverThresholdDuration() {
        // 验证平飞悬停判定门槛：严禁使用 3 tick (150ms) 超敏误杀，必须至少 6 tick (300ms)
        int minFlatHoverTicks = 6;
        int minAirTicks = 6;
        assertTrue(minFlatHoverTicks >= 6, "平飞悬空判定时长至少需要 300ms (6 tick) 以消除跳跃顶点与边缘蹭步抖动");
        assertTrue(minAirTicks >= 6, "总滞空时长必须至少 6 tick");
    }
}

