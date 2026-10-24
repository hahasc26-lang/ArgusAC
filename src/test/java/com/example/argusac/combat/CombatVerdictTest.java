package com.example.argusac.combat;

import com.example.argusac.check.combat.ReachCheck;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 战斗数据包层拦截与分级处置策略 (CombatVerdict & CombatMitigationHandler) 单元测试
 */
public class CombatVerdictTest {

    @Test
    public void testPassVerdict() {
        CombatVerdict pass = CombatVerdict.pass();
        assertFalse(pass.isBlocked());
        assertFalse(pass.isHighConfidence());
        assertFalse(pass.isLowConfidenceDrop());
        assertEquals(CombatVerdict.Level.PASS, pass.getLevel());
        assertEquals(0, pass.getVlStep());
    }

    @Test
    public void testLowConfidenceDropVerdict() {
        ReachCheck check = new ReachCheck(null);
        CombatVerdict low = CombatVerdict.lowConfidenceDrop(check, "dist=3.22, limit=3.15, excess=0.07");

        // 低确信：必须阻断数据包 (Drop)，但不触发高确信 VL 累加
        assertTrue(low.isBlocked());
        assertTrue(low.isLowConfidenceDrop());
        assertFalse(low.isHighConfidence());
        assertEquals(0, low.getVlStep());
        assertEquals(check, low.getCheck());
    }

    @Test
    public void testHighConfidenceVerdict() {
        ReachCheck check = new ReachCheck(null);
        CombatVerdict high = CombatVerdict.highConfidence(check, 2, "dist=4.10, limit=3.15, excess=0.95");

        // 高确信：既阻断数据包，又触发 VL += step 升级处置
        assertTrue(high.isBlocked());
        assertTrue(high.isHighConfidence());
        assertFalse(high.isLowConfidenceDrop());
        assertEquals(2, high.getVlStep());
        assertEquals(check, high.getCheck());
    }

    @Test
    public void testSuppressionStateTracking() {
        CombatMitigationHandler handler = new CombatMitigationHandler(null);
        assertFalse(handler.isAttackerEntitySuppressed(101));
        assertFalse(handler.isVictimEntitySuppressed(202));
        handler.clearAll();
    }
}

