package com.example.argusac.i18n;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

/**
 * 多语言系统静态全局门面 (I18n Facade)
 * 为插件各功能模块（命令、事件、检测、惩罚、日志）提供极简、直观、空安全的调用入口。
 */
public final class I18n {

    private static LanguageManager manager;

    private I18n() {}

    /**
     * 在插件主类中初始化绑定管理器实例
     */
    public static void init(LanguageManager languageManager) {
        manager = Objects.requireNonNull(languageManager, "LanguageManager cannot be null");
    }

    /**
     * 获取绑定的底层语言管理器实例
     */
    public static LanguageManager getManager() {
        return manager;
    }

    /**
     * 向目标发送者（玩家或控制台）发送本地化消息（不带全局前缀）
     *
     * @param sender 消息接收方 (为 null 时静默返回)
     * @param key    多语言键名 (为 null 时静默返回)
     * @param args   成对占位符 ("key", value) 或数字索引参数
     */
    public static void send(CommandSender sender, String key, Object... args) {
        if (manager != null) {
            manager.sendMessage(sender, key, args);
        }
    }

    /**
     * 向目标发送者发送带有插件统一前缀的本地化消息
     *
     * @param sender 消息接收方
     * @param key    多语言键名
     * @param args   占位符参数
     */
    public static void sendPrefixed(CommandSender sender, String key, Object... args) {
        if (manager != null) {
            manager.sendPrefixedMessage(sender, key, args);
        }
    }

    /**
     * 获取已完成占位符替换和颜色解析的 Component
     *
     * @param sender 消息接收方（用于决定语言代码）
     * @param key    多语言键名
     * @param args   占位符参数
     * @return Adventure Component
     */
    public static Component component(CommandSender sender, String key, Object... args) {
        if (manager == null) {
            return Component.text(key != null ? key : "");
        }
        return manager.getComponent(sender, key, args);
    }

    /**
     * 获取带有插件前缀的 Component
     */
    public static Component prefixedComponent(CommandSender sender, String key, Object... args) {
        if (manager == null) {
            return Component.text(key != null ? key : "");
        }
        return manager.getPrefixedComponent(sender, key, args);
    }

    /**
     * 获取多行本地化 Component 列表 (适用于 Help 菜单、GUI Lore 等)
     */
    public static List<Component> componentList(CommandSender sender, String key, Object... args) {
        if (manager == null) {
            return List.of(Component.text(key != null ? key : ""));
        }
        return manager.getComponentList(sender, key, args);
    }

    /**
     * 获取原始未解析的占位符替换后字符串
     */
    public static String raw(CommandSender sender, String key, Object... args) {
        if (manager == null) {
            return key != null ? key : "";
        }
        String lang = manager.resolveLanguage(sender);
        String raw = manager.getRawMessage(lang, key);
        return manager.format(raw, args);
    }

    /**
     * 全局广播消息：每位在线玩家根据其客户端语言独立本地化接收
     */
    public static void broadcast(String key, Object... args) {
        if (manager != null) {
            manager.broadcast(key, args);
        }
    }

    /**
     * 向所有拥有管理员权限 (aegis.admin) 的在线玩家广播消息
     */
    public static void broadcastStaff(String key, Object... args) {
        if (manager != null) {
            manager.broadcastPermission("argusac.admin", key, args);
        }
    }

    /**
     * 向玩家发送本地化 Action Bar
     */
    public static void sendActionBar(Player player, String key, Object... args) {
        if (manager != null) {
            manager.sendActionBar(player, key, args);
        }
    }

    /**
     * 向玩家发送本地化 Title 与 Subtitle
     *
     * @param player      目标玩家
     * @param titleKey    主标题多语言键 (传 null 则不显示主标题)
     * @param subtitleKey 副标题多语言键 (传 null 则不显示副标题)
     * @param fadeIn      淡入时间 (刻, tick)
     * @param stay        停留时间 (刻, tick)
     * @param fadeOut     淡出时间 (刻, tick)
     * @param args        占位符参数
     */
    public static void sendTitle(Player player, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut, Object... args) {
        if (manager != null) {
            manager.sendTitle(player, titleKey, subtitleKey, fadeIn, stay, fadeOut, args);
        }
    }
}
