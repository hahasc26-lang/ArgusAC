package com.example.argusac.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModrinthAutoUpdater 核心语义化版本比对与解析单元测试
 */
class ModrinthAutoUpdaterTest {

    @Test
    @DisplayName("测试基础版本号大小比对")
    void testBasicVersionComparison() {
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2", "1.2.3"));
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2", "1.3.0"));
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2", "2.0.0"));
        assertFalse(ModrinthAutoUpdater.isNewerVersion("1.2.2", "1.2.2"));
        assertFalse(ModrinthAutoUpdater.isNewerVersion("1.2.2", "1.2.1"));
        assertFalse(ModrinthAutoUpdater.isNewerVersion("1.2.2", "1.1.9"));
    }

    @Test
    @DisplayName("测试带 v/V 前缀与构建元数据 (+build)")
    void testPrefixAndBuildMetadata() {
        assertEquals(0, ModrinthAutoUpdater.compareVersions("v1.2.2", "1.2.2"));
        assertEquals(0, ModrinthAutoUpdater.compareVersions("V1.2.2", "v1.2.2"));
        assertEquals(0, ModrinthAutoUpdater.compareVersions("1.2.2+build100", "1.2.2+build200"));
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2+build1", "1.2.3+build0"));
    }

    @Test
    @DisplayName("测试预发布版本 (alpha, beta, rc, snapshot)")
    void testPreReleaseVersions() {
        // 正式版高于预发布版
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2-beta.1", "1.2.2"));
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2-rc.1", "1.2.2"));

        // beta 高于 alpha
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2-alpha", "1.2.2-beta"));

        // rc 高于 beta
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2-beta.2", "1.2.2-rc.1"));

        // 同前缀按后缀数字排序
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2-beta.1", "1.2.2-beta.2"));
        assertTrue(ModrinthAutoUpdater.isNewerVersion("1.2.2-beta.9", "1.2.2-beta.10"));
    }

    @Test
    @DisplayName("测试占位符转换工具方法")
    void testPlaceholderArgsConversion() {
        Map<String, String> ph = new HashMap<>();
        ph.put("current", "1.2.2");
        ph.put("latest", "1.2.3");

        Object[] args = ModrinthAutoUpdater.toArgs(ph);
        assertNotNull(args);
        assertEquals(4, args.length);

        Object[] emptyArgs = ModrinthAutoUpdater.toArgs(null);
        assertNotNull(emptyArgs);
        assertEquals(0, emptyArgs.length);
    }
}

