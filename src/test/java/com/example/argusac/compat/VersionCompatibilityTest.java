package com.example.argusac.compat;

import com.example.argusac.util.ColorUtil;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨版本兼容性 (1.12.2 - 最新版本 1.21.x / 26.x) 回归测试
 */
public class VersionCompatibilityTest {

    @Test
    @DisplayName("测试服务器版本号解析器对 1.12.2 至 26.x 的正确识别")
    void testServerVersionCodeParsing() throws Exception {
        Method parseMethod = ServerVersion.class.getDeclaredMethod("parseServerVersionCode");
        parseMethod.setAccessible(true);

        // 验证标准 1.12.2, 1.16.5, 1.20.4, 1.21.4 与 26.3 版本编码常量的数值大小关系
        assertTrue(ServerVersion.V1_12_2.getVersionCode() < ServerVersion.V1_13.getVersionCode());
        assertTrue(ServerVersion.V1_13.getVersionCode() < ServerVersion.V1_16_5.getVersionCode());
        assertTrue(ServerVersion.V1_16_5.getVersionCode() < ServerVersion.V1_20_4.getVersionCode());
        assertTrue(ServerVersion.V1_20_4.getVersionCode() < ServerVersion.V1_21.getVersionCode());
        assertTrue(ServerVersion.V1_21.getVersionCode() < ServerVersion.V1_21_4.getVersionCode());
        assertTrue(ServerVersion.V1_21_4.getVersionCode() < ServerVersion.V26_0.getVersionCode());
        assertTrue(ServerVersion.V26_0.getVersionCode() < ServerVersion.V26_3.getVersionCode());

        assertEquals(11202, ServerVersion.V1_12_2.getVersionCode());
        assertEquals(12004, ServerVersion.V1_20_4.getVersionCode());
        assertEquals(12104, ServerVersion.V1_21_4.getVersionCode());
        assertEquals(12603, ServerVersion.V26_3.getVersionCode());
    }

    @Test
    @DisplayName("测试 ColorUtil 颜色转换在跨版本环境下的稳定性")
    void testColorUtilCompatibility() {
        // 测试普通 & 颜色代码转换
        String colored = ColorUtil.colorize("&c[ArgusAC] &a安全检测通过");
        assertNotNull(colored);
        assertTrue(colored.contains("ArgusAC"));

        // 测试 null 安全性
        assertEquals("", ColorUtil.colorize(null));
        assertNotNull(ColorUtil.toComponent(null));

        // 测试 Adventure Component 转换
        Component comp = ColorUtil.toComponent("&e警告: &f检测到异常移动");
        assertNotNull(comp);
    }

    @Test
    @DisplayName("测试 CompatBoundingBox 几何运算的正确性 (跨版本通用)")
    void testCompatBoundingBoxCalculations() {
        CompatBoundingBox box1 = new CompatBoundingBox(0.0, 0.0, 0.0, 1.0, 2.0, 1.0);
        CompatBoundingBox box2 = new CompatBoundingBox(0.5, 1.0, 0.5, 1.5, 3.0, 1.5);
        CompatBoundingBox boxDisjoint = new CompatBoundingBox(2.0, 0.0, 2.0, 3.0, 2.0, 3.0);

        // 相交判定
        assertTrue(box1.intersects(box2));
        assertTrue(box2.intersects(box1));
        assertFalse(box1.intersects(boxDisjoint));

        // 外扩判定 (expand)
        CompatBoundingBox expanded = box1.expand(0.5);
        assertEquals(-0.5, expanded.minX(), 1e-6);
        assertEquals(-0.5, expanded.minY(), 1e-6);
        assertEquals(-0.5, expanded.minZ(), 1e-6);
        assertEquals(1.5, expanded.maxX(), 1e-6);
        assertEquals(2.5, expanded.maxY(), 1e-6);
        assertEquals(1.5, expanded.maxZ(), 1e-6);

        // 边距容差相交判定 (intersectsWithMargin)
        assertTrue(box1.intersectsWithMargin(box2, 0.2));
        assertFalse(box1.intersectsWithMargin(box2, 0.6));
        assertFalse(box1.intersectsWithMargin(boxDisjoint, 0.1));
    }

    @Test
    @DisplayName("测试 CompatAttribute 名称映射规范 (兼容 1.21.2+ GENERIC_ 前缀剥离)")
    void testCompatAttributeNameMapping() {
        for (CompatAttribute attr : CompatAttribute.values()) {
            String fullName = attr.name();
            assertTrue(fullName.startsWith("GENERIC_"));
            String strippedName = fullName.substring("GENERIC_".length());
            assertFalse(strippedName.isEmpty());

            // 验证剥离后的关键属性名符合现代 Minecraft / Paper 1.21.2+ 规范
            if (attr == CompatAttribute.GENERIC_MOVEMENT_SPEED) {
                assertEquals("MOVEMENT_SPEED", strippedName);
            } else if (attr == CompatAttribute.GENERIC_ATTACK_SPEED) {
                assertEquals("ATTACK_SPEED", strippedName);
            } else if (attr == CompatAttribute.GENERIC_MAX_HEALTH) {
                assertEquals("MAX_HEALTH", strippedName);
            }
        }
    }
}
