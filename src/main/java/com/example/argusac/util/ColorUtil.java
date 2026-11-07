package com.example.argusac.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

/**
 * 颜色与文本工具类
 * 兼容经典 Minecraft 颜色代码以及 Paper Adventure Component API
 */
@SuppressWarnings("deprecation")
public final class ColorUtil {

    private static final boolean HAS_ADVENTURE;

    static {
        boolean adv = false;
        try {
            Class.forName("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
            adv = true;
        } catch (Throwable ignored) {
        }
        HAS_ADVENTURE = adv;
    }

    private ColorUtil() {
    }

    /**
     * 将带有 & 颜色代码的字符串转换为标准颜色字符串
     */
    public static String colorize(String message) {
        if (message == null) {
            return "";
        }
        if (HAS_ADVENTURE) {
            try {
                return LegacyComponentSerializer.legacyAmpersand()
                        .serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
            } catch (Throwable ignored) {
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    /**
     * 将经典颜色代码字符串转换为 Paper Adventure Component
     */
    public static Component toComponent(String message) {
        if (message == null) {
            return Component.empty();
        }
        if (HAS_ADVENTURE) {
            try {
                return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
            } catch (Throwable ignored) {
            }
        }
        return Component.text(ChatColor.translateAlternateColorCodes('&', message));
    }
}
