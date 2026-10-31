package com.example.argusac.check;

import com.example.argusac.data.ElytraData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ArgusAC v1.2.1 误报修复与状态机回归单元测试
 */
public class FalsePositiveRegressionTest {

    @Test
    public void testElytraTimerLagSpikeCatchupDoesNotFalseFlag() {
        ElytraData data = new ElytraData();
        // 模拟正常滑翔 10 ticks (50ms/tick)
        for (int i = 0; i < 10; i++) {
            data.recordMoveInterval(50L);
        }
        // 模拟网络长卡顿 1200ms
        data.recordMoveInterval(1200L);
        // 紧接着同一 TCP 批次到达 8 个积压补发包 (0~2ms)
        for (int i = 0; i < 8; i++) {
            data.recordMoveInterval(1L);
        }
        // 随后恢复正常 50ms 发包直至凑满 20 个样本
        while (data.getMoveIntervalSampleSize() < 20) {
            data.recordMoveInterval(50L);
        }

        double avg = data.getAverageMoveInterval();
        assertTrue(avg >= 44.0, "长卡顿后的 TCP 补包潮不应拉低平均间隔触发 ElytraTimer 误报, 实际 avg=" + avg);
    }

    @Test
    public void testElytraTimerDetectsRealSpeedhack() {
        ElytraData data = new ElytraData();
        // 模拟真实 1.5x Timer 作弊 (持续 ~33ms/tick)
        for (int i = 0; i < 20; i++) {
            data.recordMoveInterval(33L);
        }
        assertEquals(20, data.getMoveIntervalSampleSize());
        assertTrue(data.getAverageMoveInterval() < 44.0, "真实 Timer 加速必须使平均间隔低于阈值 44.0ms");
    }

    @Test
    public void testKillAuraSingleTurnFlickUnderAttackDoesNotEscalateOnFirstHit() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        long now = System.currentTimeMillis();
        // 第一次受击快速甩鼠反击（INTERACT_ENTITY 早于 PLAYER_ROTATION 到达）：计数为 1，不触发高确信 VL
        assertEquals(1, data.recordAngleAnomaly(now));
        // 下一刀视角同步正常后立即清零
        data.resetAngleAnomaly();
        assertEquals(1, data.recordAngleAnomaly(now + 500L));
        // 连续两次背身/大角度异常发包则达标 (>= 2)
        assertEquals(2, data.recordAngleAnomaly(now + 650L));
    }

    @Test
    public void testDiggingTransitionWallIsolatesClickIntervals() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        long now = System.currentTimeMillis();
        // 模拟玩家开始挖掘并持续挥臂
        data.startDigging(now);
        assertTrue(data.isDigging());
        assertEquals(0, data.getMsIntervalSamples().size());

        // 停止挖掘
        data.stopDigging(now + 500L);
        // 停止挖掘后的第 1 次点击（紧随其后 15ms），应当被硬截断丢弃，防止 15ms 污染样本队列
        data.recordClickSample(15.0, 1);
        assertEquals(0, data.getMsIntervalSamples().size(), "挖掘硬截断必须拦截刚退出挖掘时的首个异常短间隔样本");

        // 后续正常点击样本才会被录入
        data.recordClickSample(65.0, 1);
        assertEquals(1, data.getMsIntervalSamples().size());
        assertEquals(65.0, data.getMsIntervalSamples().peekLast());
    }

    @Test
    public void testInteractEntityPairDeduplication() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        data.incrementClientFlyingTicks(System.nanoTime());

        // 原版 1.8+ 盔甲架/实体右键时先发 INTERACT_AT 后发 INTERACT
        boolean firstAt = data.recordInteractEntity(42, true);
        boolean secondInteract = data.recordInteractEntity(42, false);

        assertTrue(firstAt, "首个 INTERACT_AT 包应当正常记录");
        assertFalse(secondInteract, "同一 tick 对同一实体的普通 INTERACT 必须被聚合去重，避免交互计次翻倍");

        // 推进到下一个 client tick 后再次右键实体
        data.incrementClientFlyingTicks(System.nanoTime());
        boolean nextTickInteract = data.recordInteractEntity(42, false);
        assertTrue(nextTickInteract, "不同 client tick 的独立右键必须正常放行");
    }

    @Test
    public void testAttackSwingOrderingTolerance() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        data.incrementClientFlyingTicks(System.nanoTime());

        // 模组端/网络乱序：同 tick 内 Attack 先于 Swing 到达
        data.recordAttack();
        data.recordSwing();
        assertEquals(0, data.getConsecutiveNoSwingAttacks(), "同 tick 乱序到达的 Attack 与 Swing 必须正常完成配对");

        // 跨 1 个 client tick 乱序到达
        data.incrementClientFlyingTicks(System.nanoTime());
        data.recordSwing();
        data.incrementClientFlyingTicks(System.nanoTime());
        data.recordAttack();
        assertEquals(0, data.getConsecutiveNoSwingAttacks(), "相邻 client tick 缓冲内的 Attack 与 Swing 同样允许配对");
    }
}

