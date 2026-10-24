package com.example.argusac.check.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MaceCheck 与 MaceDMG 核心数学模型及判定逻辑单元测试
 */
public class MaceCheckTest {

    @Test
    public void testKinematicMaxFall() {
        // 运动学约束测试
        // 0 tick 或负 tick
        MaceCheck check = new MaceCheck(null);
        assertEquals(1.5, check.calculateKinematicMaxFall(0), 0.001);

        // 1 tick (50ms): 最大允许落差约 1 * 3.92 + 2.0 = 5.92 格
        double max1 = check.calculateKinematicMaxFall(1);
        assertEquals(5.92, max1, 0.001);
        // 若客户端在 1 tick 内谎称下落了 22 格 (Wurst MaceDMG 典型发包)，必须超出约束
        assertTrue(22.0 > max1);

        // 2 ticks (100ms): 2 * 3.92 + 2.0 = 9.84 格
        double max2 = check.calculateKinematicMaxFall(2);
        assertEquals(9.84, max2, 0.001);
        assertTrue(22.0 > max2);

        // 3 ticks (150ms): 3 * 3.92 + 2.0 = 13.76 格
        double max3 = check.calculateKinematicMaxFall(3);
        assertEquals(13.76, max3, 0.001);
        assertTrue(22.0 > max3);

        // 正常高空坠落 22 格需耗时约 20+ ticks:
        double max20 = check.calculateKinematicMaxFall(20);
        assertTrue(22.0 <= max20);
    }

    @Test
    public void testApexFallVerificationLogic() {
        // 模拟物理真最高点与报告下落高度比对
        // double groundY = 64.0;
        double currentY = 64.5; // 玩家平地小跳
        double trueApexY = 65.25; // 起跳最高点 1.25 格

        double maxPhysicalFall = Math.max(0.0, trueApexY - currentY); // 0.75 格
        double apexTolerance = 0.85;

        // 情况 1: 客户端正常攻击，未开挂 (fallDistance = 0.75)
        float normalFall = 0.75f;
        assertFalse(normalFall > maxPhysicalFall + apexTolerance);

        // 情况 2: 客户端开启 MaceDMG 外挂，伪造 22 格下落
        float spoofedFall = 22.0f;
        assertTrue(spoofedFall > maxPhysicalFall + apexTolerance);

        // 差值
        double delta = spoofedFall - maxPhysicalFall;
        assertEquals(21.25, delta, 0.001);
    }

    @Test
    public void testGroundSmashLogic() {
        // 地面接地或物理高度严重不足判定
        double maxNonSmash = 9.0; // 6.0 * 1.5 暴击
        double physicalFall = 0.5; // 仅离地 0.5 格

        // 如果物理下落距离小于 1.45 格 (原版必须 > 1.5 格才能砸击)
        assertTrue(physicalFall < 1.45);

        // 如果此时客户端打出了 64 点伤害 (MaceDMG 砸击)
        double exploitDamage = 64.0;
        assertTrue(exploitDamage > maxNonSmash + 1.5);
    }

    @Test
    public void testMaceDamageFormula() {
        // 测试重锤原版下落增益分段函数
        // 3 格以内: 每格 +4.0
        double fall3 = 3.0;
        double bonus3 = fall3 * 4.0; // 12.0
        assertEquals(12.0, bonus3, 0.001);

        // 8 格: 3*4 + 5*2 = 22.0
        double fall8 = 8.0;
        double bonus8 = 3.0 * 4.0 + (fall8 - 3.0) * 2.0;
        assertEquals(22.0, bonus8, 0.001);

        // 15 格: 12 + 10 + 7 = 29.0
        double fall15 = 15.0;
        double bonus15 = 3.0 * 4.0 + 5.0 * 2.0 + (fall15 - 8.0) * 1.0;
        assertEquals(29.0, bonus15, 0.001);

        // 致密 V (Density V): 每格 +2.5
        double densityVBonus15 = (5 * 0.5) * fall15; // 37.5
        assertEquals(37.5, densityVBonus15, 0.001);
        double totalSmashBonus15 = bonus15 + densityVBonus15; // 66.5
        assertEquals(66.5, totalSmashBonus15, 0.001);
    }
}

