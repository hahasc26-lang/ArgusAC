package com.example.aegisac.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * 颜色与文本工具类
 * 兼容经典 Minecraft 颜色代码以及 Paper Adventure Component API
 */
public final class ColorUtil {

    private ColorUtil() {}

    /**
     * 将带有 & 颜色代码的字符串转换为标准颜色字符串
     */
    public static String colorize(String message) {
        if (message == null) {
            return "";
        }
        // 使用 Adventure LegacyComponentSerializer 转换，避免依赖已废弃的 ChatColor
        return LegacyComponentSerializer.legacyAmpersand()
                .serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    /**
     * 将经典颜色代码字符串转换为 Paper Adventure Component
     */
    public static Component toComponent(String message) {
        if (message == null) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }
}
