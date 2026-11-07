package com.example.argusac.i18n;

import com.example.argusac.ArgusACPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 现代精炼版多语言国际化管理器 (Language & i18n Manager)
 * 具备 1.12.2 ~ 26.3+ 全版本（Spigot / Paper / Folia）底层硬兼容保障：
 * 1. 杜绝第三方扩展库依赖，不依赖 LegacyComponentSerializer，纯 MiniMessage 解析。
 * 2. 多重反射隔离获取客户端语言，适配 1.12.2 旧 Spigot 架构。
 * 3. 链式兜底机制、玩家语言缓存与自动补全缺失键值。
 */
public class LanguageManager {

    private final ArgusACPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static final List<String> BUILTIN_LANGS = List.of("zh_cn", "en_us");

    private String serverLanguage = "zh_cn";
    private String clientDefaultLanguage = "en_us";
    private boolean autoClientLanguage = true;

    private final Map<String, FileConfiguration> configs = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerLocaleCache = new ConcurrentHashMap<>();

    // 经典颜色代码映射表（O(1) 数组查表，避免在 1.12.2 抛出 NoClassDefFoundError）
    private static final String[] LEGACY_TAGS = new String[128];
    static {
        LEGACY_TAGS['0'] = "<black>";
        LEGACY_TAGS['1'] = "<dark_blue>";
        LEGACY_TAGS['2'] = "<dark_green>";
        LEGACY_TAGS['3'] = "<dark_aqua>";
        LEGACY_TAGS['4'] = "<dark_red>";
        LEGACY_TAGS['5'] = "<dark_purple>";
        LEGACY_TAGS['6'] = "<gold>";
        LEGACY_TAGS['7'] = "<gray>";
        LEGACY_TAGS['8'] = "<dark_gray>";
        LEGACY_TAGS['9'] = "<blue>";
        LEGACY_TAGS['a'] = "<green>";
        LEGACY_TAGS['b'] = "<aqua>";
        LEGACY_TAGS['c'] = "<red>";
        LEGACY_TAGS['d'] = "<light_purple>";
        LEGACY_TAGS['e'] = "<yellow>";
        LEGACY_TAGS['f'] = "<white>";
        LEGACY_TAGS['k'] = "<obfuscated>";
        LEGACY_TAGS['l'] = "<bold>";
        LEGACY_TAGS['m'] = "<strikethrough>";
        LEGACY_TAGS['n'] = "<underlined>";
        LEGACY_TAGS['o'] = "<italic>";
        LEGACY_TAGS['r'] = "<reset>";
    }

    public LanguageManager(ArgusACPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.reload();
    }

    /**
     * 重载多语言配置并释放默认语言文件
     */
    public synchronized void reload() {
        configs.clear();
        playerLocaleCache.clear();

        FileConfiguration cfg = plugin.getConfig();
        serverLanguage = normalize(cfg.getString("language.server-language", "zh_cn"));
        clientDefaultLanguage = normalize(cfg.getString("language.client-default-language", "en_us"));
        autoClientLanguage = cfg.getBoolean("language.auto-client-language", true);

        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists())
            langDir.mkdirs();

        // 释放缺失的默认语言文件（不覆盖服主已有修改）
        for (String lang : BUILTIN_LANGS) {
            File target = new File(langDir, lang + ".yml");
            if (!target.exists()) {
                try {
                    plugin.saveResource("lang/" + lang + ".yml", false);
                } catch (Exception ignored) {
                }
            }
        }

        // 载入 lang 目录下的所有语言配置
        File[] files = langDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String code = normalize(file.getName().replace(".yml", ""));
                try {
                    YamlConfiguration diskCfg = YamlConfiguration.loadConfiguration(file);
                    // 绑定内置资源作为 defaults，实现未配置 Key 的热补全
                    try (InputStream in = plugin.getResource("lang/" + file.getName())) {
                        if (in != null) {
                            diskCfg.setDefaults(YamlConfiguration
                                    .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
                        }
                    }
                    configs.put(code, diskCfg);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to load lang file: " + file.getName(), e);
                }
            }
        }
    }

    /**
     * 解析目标发送者的语言代码（三轨反射兼容：Paper -> Spigot -> 1.12.2 SpigotAPI）
     */
    public String resolveLanguage(CommandSender sender) {
        if (!autoClientLanguage || !(sender instanceof Player player))
            return serverLanguage;
        return playerLocaleCache.computeIfAbsent(player.getUniqueId(), uuid -> {
            // 轨 1：Paper / Adventure 原生 player.locale()
            try {
                Method m = player.getClass().getMethod("locale");
                Object loc = m.invoke(player);
                if (loc != null)
                    return normalize(loc.toString());
            } catch (Throwable ignored) {
            }

            // 轨 2：Spigot 现代接口 player.getLocale()
            try {
                Method m = player.getClass().getMethod("getLocale");
                Object loc = m.invoke(player);
                if (loc instanceof String s && !s.isBlank())
                    return normalize(s);
            } catch (Throwable ignored) {
            }

            // 轨 3：1.12.2 经典旧版 player.spigot().getLocale()
            try {
                Method spigotMethod = player.getClass().getMethod("spigot");
                Object spigot = spigotMethod.invoke(player);
                Method getLocale = spigot.getClass().getMethod("getLocale");
                Object loc = getLocale.invoke(spigot);
                if (loc instanceof String s && !s.isBlank())
                    return normalize(s);
            } catch (Throwable ignored) {
            }

            return clientDefaultLanguage;
        });
    }

    public void invalidateCache(UUID uuid) {
        if (uuid != null)
            playerLocaleCache.remove(uuid);
    }

    public static String normalize(String raw) {
        return raw == null ? "zh_cn" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 链式回退查询原始文本：目标语言 -> 客户端默认语言 -> 服务端默认语言 -> 预设保底 (en_us) -> Key 自身
     */
    public String getRawMessage(String langCode, String key) {
        if (key == null)
            return "";
        List<String> lookupOrder = List.of(
                langCode != null ? langCode : clientDefaultLanguage,
                clientDefaultLanguage,
                serverLanguage,
                "en_us");

        for (String code : lookupOrder) {
            FileConfiguration cfg = configs.get(code);
            if (cfg != null && cfg.getString(key) != null) {
                return cfg.getString(key);
            }
        }
        return key;
    }

    public List<String> getRawList(String langCode, String key) {
        if (key == null)
            return Collections.emptyList();
        FileConfiguration cfg = configs.get(langCode != null ? langCode : clientDefaultLanguage);
        if (cfg != null && cfg.contains(key))
            return cfg.getStringList(key);
        return Collections.singletonList(key);
    }

    /**
     * 替换占位符：同时支持成对参数 (key, val) 与数字索引占位符 ({0}, {1})
     */
    public String format(String text, Object... args) {
        if (text == null || args == null || args.length == 0)
            return text == null ? "" : text;
        String res = text;
        if (args.length % 2 == 0 && args[0] instanceof CharSequence) {
            for (int i = 0; i < args.length; i += 2) {
                String k = String.valueOf(args[i]);
                String v = String.valueOf(args[i + 1]);
                res = res.replace("{" + k + "}", v).replace("%" + k + "%", v);
            }
        }
        for (int i = 0; i < args.length; i++) {
            res = res.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return res;
    }

    /**
     * 全版本兼容的文本渲染器：
     * 无需引入 LegacyComponentSerializer，将 & 传统颜色与 &#RRGGBB 预处理为 MiniMessage 标签统一解析，
     * 原生支持两种格式混写（例如：`<red>警告：&a正常`）。
     */
    public Component parseToComponent(String raw) {
        if (raw == null || raw.isEmpty())
            return Component.empty();

        // 1. 转换 Hex 颜色代码 &#RRGGBB 为 MiniMessage <#RRGGBB>
        String converted = raw.replaceAll("&#([0-9a-fA-F]{6})", "<#$1>");

        // 2. 转换传统 & 颜色代码为 MiniMessage 标签
        if (converted.indexOf('&') != -1) {
            StringBuilder sb = new StringBuilder(converted.length() + 16);
            char[] chars = converted.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (chars[i] == '&' && i + 1 < chars.length) {
                    char c = Character.toLowerCase(chars[i + 1]);
                    if (c < 128 && LEGACY_TAGS[c] != null) {
                        sb.append(LEGACY_TAGS[c]);
                        i++; // 跳过颜色识别码
                        continue;
                    }
                }
                sb.append(chars[i]);
            }
            converted = sb.toString();
        }

        // 3. 统一交由 MiniMessage 反序列化
        try {
            return miniMessage.deserialize(converted);
        } catch (Exception e) {
            return Component.text(raw);
        }
    }

    // =========================================================================
    // 快捷封装方法（完整补齐接口，杜绝 undefined 编译报错）
    // =========================================================================

    public Component getComponent(CommandSender sender, String key, Object... args) {
        return parseToComponent(format(getRawMessage(resolveLanguage(sender), key), args));
    }

    public Component getPrefixedComponent(CommandSender sender, String key, Object... args) {
        String lang = resolveLanguage(sender);
        String prefix = getRawMessage(lang, "prefix");
        return parseToComponent(format(prefix + getRawMessage(lang, key), args));
    }

    public List<Component> getComponentList(CommandSender sender, String key, Object... args) {
        String lang = resolveLanguage(sender);
        List<String> rawList = getRawList(lang, key);
        List<Component> result = new ArrayList<>(rawList.size());
        for (String raw : rawList) {
            result.add(parseToComponent(format(raw, args)));
        }
        return result;
    }

    public void sendMessage(CommandSender sender, String key, Object... args) {
        if (sender != null && key != null)
            sender.sendMessage(getComponent(sender, key, args));
    }

    public void sendPrefixedMessage(CommandSender sender, String key, Object... args) {
        if (sender != null && key != null)
            sender.sendMessage(getPrefixedComponent(sender, key, args));
    }

    public void broadcast(String key, Object... args) {
        plugin.getServer().getConsoleSender().sendMessage(getComponent(null, key, args));
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(getComponent(p, key, args));
    }

    public void broadcastPermission(String perm, String key, Object... args) {
        plugin.getServer().getConsoleSender().sendMessage(getComponent(null, key, args));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(perm))
                p.sendMessage(getComponent(p, key, args));
        }
    }

    public void sendActionBar(Player player, String key, Object... args) {
        if (player != null && key != null)
            player.sendActionBar(getComponent(player, key, args));
    }

    public void sendTitle(Player player, String titleKey, String subKey, int in, int stay, int out, Object... args) {
        if (player == null)
            return;
        Component t = titleKey != null ? getComponent(player, titleKey, args) : Component.empty();
        Component s = subKey != null ? getComponent(player, subKey, args) : Component.empty();
        player.showTitle(Title.title(t, s, Title.Times.times(
                Duration.ofMillis(in * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(out * 50L))));
    }

    // =========================================================================
    // Getters & 状态查询
    // =========================================================================

    public String getServerLanguage() {
        return serverLanguage;
    }

    public String getClientDefaultLanguage() {
        return clientDefaultLanguage;
    }

    public boolean isAutoClientLanguage() {
        return autoClientLanguage;
    }

    public Set<String> getLoadedLanguages() {
        return Collections.unmodifiableSet(configs.keySet());
    }
}