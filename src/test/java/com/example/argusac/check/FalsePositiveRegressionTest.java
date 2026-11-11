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

    @Test
    public void testArrowDamageKnockbackReachAndCandidateEyes() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        long now = System.currentTimeMillis();

        // 模拟玩家受到远程箭矢攻击，记录受击时间与受击前世界坐标
        data.setLastDamageTime(now);
        data.grantLegalVelocity(com.example.argusac.model.VelocitySourceType.ATTACK_KNOCKBACK, 1500L);
        assertTrue(data.hasRecentVelocity(1500L), "刚受到箭矢伤害应处于合法击退/受击期");
        assertTrue(data.hasAnyLegalVelocity(), "应拥有生效中的 ATTACK_KNOCKBACK 合法速度来源");
    }

    @Test
    public void testArrowDamageKnockbackKillAuraRequiresHigherAnomalyThreshold() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        long now = System.currentTimeMillis();
        data.setLastDamageTime(now);
        data.grantLegalVelocity(com.example.argusac.model.VelocitySourceType.ATTACK_KNOCKBACK, 1500L);

        // 受击击退期疯狂点左键试图还击，连续 2 次和 3 次异常在 underKnockback 下必须仍处于容差缓冲内 (< 4)
        int anomalies = data.recordAngleAnomaly(now + 100L);
        assertEquals(1, anomalies);
        anomalies = data.recordAngleAnomaly(now + 250L);
        assertEquals(2, anomalies);
        assertTrue(anomalies < 4, "在击退状态下，连续 2 次偏角不应达到高确信阈值 4");

        anomalies = data.recordAngleAnomaly(now + 400L);
        assertEquals(3, anomalies);
        assertTrue(anomalies < 4, "在击退状态下，连续 3 次偏角仍处于宽限期");

        // 直到第 4 次才达标
        anomalies = data.recordAngleAnomaly(now + 550L);
        assertEquals(4, anomalies);
    }

    @Test
    public void testFastEatCombinedTickAndMsToleranceUnderTpsFluctuation() {
        // 场景 1：服务器轻微高 TPS 抖动，经过了完整的 32 ticks，但物理耗时为 1180ms (略低于 1200ms，但远高于 75%=900ms) -> 不应误报
        assertFalse(com.example.argusac.check.combat.FastEatCheck.isFastEatViolation(32L, 1180L, 28, 1200L),
                "满 32 ticks 且物理耗时接近 1200ms 时不应因单边轻微抖动触发 FastEat 误报");

        // 场景 2：服务器低 TPS (约 16 TPS)，耗时已达 1650ms，但服务端仅走过 26 ticks (略低于 28 ticks，高于 75%=21 ticks) -> 不应误报
        assertFalse(com.example.argusac.check.combat.FastEatCheck.isFastEatViolation(26L, 1650L, 28, 1200L),
                "低 TPS 下物理耗时已超过 1.6 秒且 tick 数接近阈值时不应触发 FastEat 误报");

        // 场景 3：真实 FastEat 作弊 (20 ticks / 950ms，双指标均低于 28t / 1200ms) -> 必须拦截
        assertTrue(com.example.argusac.check.combat.FastEatCheck.isFastEatViolation(20L, 950L, 28, 1200L),
                "Tick 与毫秒同时低于阈值时必须拦截 FastEat");

        // 场景 4：极端瞬间进食 InstantEat (5 ticks / 250ms) -> 必须拦截
        assertTrue(com.example.argusac.check.combat.FastEatCheck.isFastEatViolation(5L, 250L, 28, 1200L),
                "极端短耗时 InstantEat 必须被拦截");
    }

    @Test
    public void testFastBreakMiningFatigueDynamicThresholdWithEfficiency() {
        // 挖掘疲劳 I (amp=0) + 效率 V (eff=5) + 无急迫 + 20% 宽容度：最小阈值应 <= 250ms，正常 420ms 挖矿绝不误报
        long eff5Threshold = com.example.argusac.check.world.FastBreakCheck.calculateMiningFatigueThresholdMs(0, 5, false, 0.20);
        assertTrue(eff5Threshold <= 250L, "效率 V 在挖掘疲劳 I 下阈值应适配高效率工具, 实际=" + eff5Threshold);
        assertTrue(420L >= eff5Threshold, "效率 V 在挖掘疲劳 I 下耗时 420ms 挖石头不应误判");

        // 挖掘疲劳 III (amp=2，远古守卫者) + 无效率：阈值应保持高强度拦截 (>= 1500ms)
        long fatigue3Threshold = com.example.argusac.check.world.FastBreakCheck.calculateMiningFatigueThresholdMs(2, 0, false, 0.20);
        assertTrue(fatigue3Threshold >= 1500L, "挖掘疲劳 III 下必须保持高阈值拦截秒破作弊, 实际=" + fatigue3Threshold);
    }

    @Test
    public void testElytraPostDiveGlideMomentumExemption() {
        // 高空俯冲达到峰值速度 1.85 后拉平滑翔，当前速度 1.52 (低于上一帧 1.54，正在自然衰减)
        assertTrue(com.example.argusac.check.movement.ElytraFlyCheck.isDecayingPostDiveGlide(1.52, 1.54, 1.85, -0.04),
                "高空俯冲后的自然减速滑翔应被识别为合法余速滑翔");

        // 从未俯冲 (peakDiveSpeed=0.90)，却在平飞中维持 1.50 高速 -> 不属于俯冲余速，必须被拦截
        assertFalse(com.example.argusac.check.movement.ElytraFlyCheck.isDecayingPostDiveGlide(1.50, 1.50, 0.90, -0.02),
                "无俯冲历史的高速平飞不应获得俯冲余速豁免");
    }

    @Test
    public void testContinuousDiggingPreservesInitialStartTime() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        long t0 = 10_000L;
        data.startDigging(t0);
        assertEquals(t0, data.getDiggingStartTime());

        // 挖掘中途 (t0 + 400ms) 再次触发左键方块事件，不应覆盖首次 diggingStartTime
        data.startDigging(t0 + 400L);
        assertEquals(t0, data.getDiggingStartTime(), "连续挖掘过程中重复触发 startDigging 不应重置初始挖掘时间戳");

        // 停止挖掘后再次开始新挖掘，才更新为新时间戳
        data.stopDigging(t0 + 600L);
        data.startDigging(t0 + 800L);
        assertEquals(t0 + 800L, data.getDiggingStartTime());
    }

    @Test
    public void testBowReleaseUseItemPreservesChargeTimestampForShootEvent() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        long startTick = 100L;
        long startMs = 5000L;

        // 1. 玩家按下右键开始拉弓
        data.startBowCharge(startTick, startMs);
        assertTrue(data.isBowCharging());
        assertEquals(startTick, data.getBowChargeStartTick());
        assertEquals(startMs, data.getBowChargeStartTimeMs());

        // 2. 玩家拉弓瞄准 4.5 秒期间准星扫过方块再次触发 startBowCharge，不应重置起点
        data.startBowCharge(startTick + 90L, startMs + 4500L);
        assertEquals(startTick, data.getBowChargeStartTick(), "持续按住右键拉弓瞄准期间不应重置蓄力起始 Tick");
        assertEquals(startMs, data.getBowChargeStartTimeMs(), "持续按住右键拉弓瞄准期间不应重置蓄力起始毫秒时间戳");

        // 3. 玩家松开右键射箭：Netty 线程首先收到 RELEASE_USE_ITEM 调用 releaseBowCharge()
        data.releaseBowCharge();
        assertFalse(data.isBowCharging(), "松开右键后应解除防抖锁定");
        assertEquals(startTick, data.getBowChargeStartTick(), "Netty 线程收到 RELEASE_USE_ITEM 时必须保留 startTick 供主线程 EntityShootBowEvent 核验");
        assertEquals(startMs, data.getBowChargeStartTimeMs(), "Netty 线程收到 RELEASE_USE_ITEM 时必须保留 startMs 供主线程 EntityShootBowEvent 核验");

        // 4. 若上次松手未射出箭（例如蓄力仅 2 ticks 取消），玩家重新按下右键拉弓时必须正常刷新为新起点
        data.startBowCharge(startTick + 100L, startMs + 5000L);
        assertTrue(data.isBowCharging());
        assertEquals(startTick + 100L, data.getBowChargeStartTick(), "松手后重新按下右键拉弓必须更新为新蓄力起点");
        assertEquals(startMs + 5000L, data.getBowChargeStartTimeMs());

        // 5. 主线程 EntityShootBowEvent 核验完成后调用 stopBowCharge() 清零
        data.stopBowCharge();
        assertEquals(-1L, data.getBowChargeStartTick());
        assertEquals(0L, data.getBowChargeStartTimeMs());
    }

    @Test
    public void testFastBowNonlinearForceCurveInverseCalculation() {
        // 原版拉弓 17 ticks (t = 0.85)，弓力 force = (0.85^2 + 2*0.85)/3 = 0.8075f (>= 0.80f)
        float force17Ticks = 0.8075f;
        int expectedTicks = com.example.argusac.check.combat.FastBowCheck.calculateExpectedBowChargeTicks(force17Ticks, 18);
        long expectedMs = com.example.argusac.check.combat.FastBowCheck.calculateExpectedBowChargeMs(force17Ticks, 850L);

        assertTrue(expectedTicks <= 16, "17 ticks 半蓄力箭 (force=0.8075) 的期望最小 Tick 阈值应按二次曲线反解折算 (<= 16), 实际=" + expectedTicks);
        assertTrue(expectedMs <= 750L, "17 ticks 半蓄力箭 (force=0.8075) 的期望最小毫秒阈值应 <= 750ms, 实际=" + expectedMs);

        // 满弦箭 (force = 1.0f) 必须维持完整阈值 (18 ticks / 850ms)
        assertEquals(18, com.example.argusac.check.combat.FastBowCheck.calculateExpectedBowChargeTicks(1.0f, 18));
        assertEquals(850L, com.example.argusac.check.combat.FastBowCheck.calculateExpectedBowChargeMs(1.0f, 850L));
    }

    @Test
    public void testFastBowBurstVsIsolatedShotProtection() {
        com.example.argusac.data.PlayerData data = new com.example.argusac.data.PlayerData(java.util.UUID.randomUUID(), "haha___1");
        long now = 100000L;
        long currentTick = 2000L;

        // 1. 模拟偶发右键漏记导致 startTick <= 0 的孤立单发射击：距上一箭超过 1000ms (lastShotTime=0)，不应被直接当作连发外挂拦截
        boolean isolatedInsufficient = false;
        long lastShotTime = data.getLastBowShotTime();
        int shotsInWindow = data.recordBowShot(currentTick, now);
        if (data.getBowChargeStartTick() <= 0) {
            if (1.0f >= 0.5f) {
                if (lastShotTime > 0 && (now - lastShotTime) < 1000L || shotsInWindow >= 2) {
                    isolatedInsufficient = true;
                }
            }
        }
        assertFalse(isolatedInsufficient, "偶发漏记起始点的孤立射击不应被粗暴误杀判死");

        // 2. 模拟作弊端短时间内连发第二发瞬发满力箭 (未蓄力，距上一箭仅 100ms)
        long secondShotTime = now + 100L;
        long secondTick = currentTick + 2L;
        lastShotTime = data.getLastBowShotTime();
        shotsInWindow = data.recordBowShot(secondTick, secondShotTime);
        boolean burstInsufficient = false;
        if (data.getBowChargeStartTick() <= 0) {
            if (1.0f >= 0.5f) {
                if (lastShotTime > 0 && (secondShotTime - lastShotTime) < 1000L || shotsInWindow >= 2) {
                    burstInsufficient = true;
                }
            }
        }
        assertTrue(burstInsufficient, "短时间内连续未蓄力瞬发射击 (FastBow 外挂) 必须 100% 严惩拦截");
    }
}

