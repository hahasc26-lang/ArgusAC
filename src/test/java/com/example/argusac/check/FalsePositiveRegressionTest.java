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
}

