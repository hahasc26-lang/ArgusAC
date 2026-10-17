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
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 现代多语言国际化管理器 (Language & i18n Manager)
 * 专为 Paper / Spigot 1.20+ 架构打造：
 * 1. 支持自动释放内置语言文件，增量补全且不覆盖服主修改
 * 2. 具备四级兜底（客户端语言 -> 客户端回退语言 -> 硬编码底线语言 -> 原始 Key）
 * 3. 完美兼容 MiniMessage 现代标签与传统颜色代码 (包含 Legacy Hex &#RRGGBB)
 * 4. 线程安全，严密的空指针保护与异常捕获
 */
public class LanguageManager {

    private final ArgusACPlugin plugin;
    private final MiniMessage miniMessage;

    // 配置项缓存
    private String serverLanguage = "zh_cn";
    private String clientDefaultLanguage = "en_us";
    private boolean autoClientLanguage = true;

    // 硬编码最终兜底语言代码
    private static final String HARDCODED_FALLBACK_LANG = "en_us";

    // 预置保底内置语言文件列表
    private static final Set<String> DEFAULT_BUILTIN_LANGS = Set.of("zh_cn.yml", "en_us.yml");

    // 运行时语言配置缓存 (语言代码小写 -> 配置对象)
    private final Map<String, FileConfiguration> languageConfigs = new ConcurrentHashMap<>();
    // Jar 内置语言默认配置缓存 (用于 key 级热补全回退)
    private final Map<String, FileConfiguration> defaultConfigs = new ConcurrentHashMap<>();
    // 玩家 UUID -> 解析后语言代码缓存
    private final Map<UUID, String> playerLocaleCache = new ConcurrentHashMap<>();

    // 传统 Hex 颜色匹配正则: &#RRGGBB 或 &#rrggbb
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("&#([0-9a-fA-F]{6})");
    // 经典 Section 格式 Hex: &x&r&r&g&g&b&b
    private static final Pattern SECTION_HEX_PATTERN = Pattern.compile("&x(&[0-9a-fA-F]){6}");

    // 经典颜色代码映射表
    private static final Map<Character, String> LEGACY_TO_MINIMESSAGE = Map.ofEntries(
            Map.entry('0', "<black>"),
            Map.entry('1', "<dark_blue>"),
            Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"),
            Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"),
            Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"),
            Map.entry('a', "<green>"),
            Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"),
            Map.entry('d', "<light_purple>"),
            Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"),
            Map.entry('k', "<obfuscated>"),
            Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"),
            Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>")
    );

    public LanguageManager(ArgusACPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "ArgusACPlugin cannot be null");
        this.miniMessage = MiniMessage.miniMessage();
        this.reload();
    }

    /**
     * 重载多语言系统：重新读取 config.yml 配置、提取新增内置语言文件、重载所有语言配置
     */
    public synchronized void reload() {
        this.languageConfigs.clear();
        this.defaultConfigs.clear();
        this.playerLocaleCache.clear();

        // 1. 读取主配置文件 config.yml 中的 i18n 设定
        FileConfiguration config = plugin.getConfig();
        this.serverLanguage = normalizeLanguageCode(config.getString("language.server-language", "zh_cn"));
        this.clientDefaultLanguage = normalizeLanguageCode(config.getString("language.client-default-language", "en_us"));
        this.autoClientLanguage = config.getBoolean("language.auto-client-language", true);

        // 2. 检查并提取内置语言文件 (增量保存，不覆盖已有文件)
        extractDefaultLanguages();

        // 3. 加载 lang 目录下的所有语言文件
        loadAllLanguageFiles();

        plugin.getLogger().info(String.format(
                "[LanguageManager] Initialized. Loaded %d languages (server default: %s, client fallback: %s, auto-detect: %b)",
                languageConfigs.size(), serverLanguage, clientDefaultLanguage, autoClientLanguage
        ));
    }

    /**
     * 检查并释放内置语言文件到 plugins/ArgusAC/lang/ 目录
     * 支持增量释放：若文件已存在则跳过，不破坏服主自定义修改
     */
    private void extractDefaultLanguages() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists() && !langFolder.mkdirs()) {
            plugin.getLogger().warning("[LanguageManager] Failed to create language folder: " + langFolder.getAbsolutePath());
        }

        // 动态发现或使用预设列表
        Set<String> builtinFiles = discoverBuiltinLanguageFiles();
        for (String fileName : builtinFiles) {
            File targetFile = new File(langFolder, fileName);
            if (!targetFile.exists()) {
                try {
                    plugin.saveResource("lang/" + fileName, false);
                    plugin.getLogger().info("[LanguageManager] Automatically extracted default language file: " + fileName);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("[LanguageManager] Failed to extract builtin resource: lang/" + fileName + " (" + e.getMessage() + ")");
                }
            }
        }
    }

    /**
     * 扫描 Jar 包内部的 lang/ 目录，自动发现所有内置语言文件
     */
    private Set<String> discoverBuiltinLanguageFiles() {
        Set<String> files = new HashSet<>(DEFAULT_BUILTIN_LANGS);
        try {
            URL resourceUrl = plugin.getClass().getClassLoader().getResource("lang");
            if (resourceUrl != null) {
                if ("jar".equals(resourceUrl.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) resourceUrl.openConnection();
                    try (JarFile jar = connection.getJarFile()) {
                        Enumeration<JarEntry> entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (name.startsWith("lang/") && name.endsWith(".yml") && name.length() > 9) {
                                files.add(name.substring("lang/".length()));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 环境或安全策略限制扫描时，安全使用 DEFAULT_BUILTIN_LANGS 兜底
        }
        return files;
    }

    /**
     * 加载数据目录下 lang/ 中的所有语言文件，并以内置资源设置 defaults
     */
    private void loadAllLanguageFiles() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        File[] files = langFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));

        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                String langCode = normalizeLanguageCode(fileName.substring(0, fileName.length() - 4));
                try {
                    YamlConfiguration diskConfig = YamlConfiguration.loadConfiguration(file);

                    // 尝试加载 Jar 包内同名的内置资源，作为 key 缺失时的兜底 defaults
                    YamlConfiguration defaults = loadResourceDefaults("lang/" + fileName);
                    if (defaults != null) {
                        diskConfig.setDefaults(defaults);
                        defaultConfigs.put(langCode, defaults);
                    }

                    languageConfigs.put(langCode, diskConfig);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "[LanguageManager] Failed to load language file: " + fileName, e);
                }
            }
        }

        // 极端兜底：如果磁盘上没有任何语言文件，直接将内置资源载入内存，确保插件能正常渲染消息
        if (languageConfigs.isEmpty()) {
            plugin.getLogger().warning("[LanguageManager] No language files found on disk, booting fallback memory language bundle!");
            for (String fileName : DEFAULT_BUILTIN_LANGS) {
                String langCode = normalizeLanguageCode(fileName.substring(0, fileName.length() - 4));
                YamlConfiguration defaults = loadResourceDefaults("lang/" + fileName);
                if (defaults != null) {
                    languageConfigs.put(langCode, defaults);
                    defaultConfigs.put(langCode, defaults);
                }
            }
        }
    }

    /**
     * 读取 Jar 内置资源文件为 YamlConfiguration
     */
    private YamlConfiguration loadResourceDefaults(String resourcePath) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) return null;
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(reader);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[LanguageManager] Error reading internal resource: " + resourcePath, e);
            return null;
        }
    }

    /**
     * 获取发送者（控制台或玩家）对应的目标语言代码
     */
    public String resolveLanguage(CommandSender sender) {
        if (sender == null || !(sender instanceof Player player)) {
            return serverLanguage;
        }
        if (!autoClientLanguage) {
            return serverLanguage;
        }
        return getPlayerLocale(player);
    }

    /**
     * 查询玩家客户端设置的语言代码并标准化 (带本地缓存，提高高频调用吞吐量)
     * 支持 Paper 原生 Adventure API，跨版本向下兼容 Spigot
     */
    public String getPlayerLocale(Player player) {
        if (player == null) {
            return clientDefaultLanguage;
        }

        UUID uuid = player.getUniqueId();
        String cached = playerLocaleCache.get(uuid);
        if (cached != null) {
            return cached;
        }

        String rawLocale = null;

        // 1. 尝试优先调用 Paper / Adventure 的 player.locale() -> java.util.Locale
        try {
            Method localeMethod = player.getClass().getMethod("locale");
            Object locObj = localeMethod.invoke(player);
            if (locObj instanceof Locale loc) {
                rawLocale = loc.toString();
            }
        } catch (Throwable ignored) {
            // Paper 1.16 以前或部分 Spigot 实现无此方法
        }

        // 2. 降级尝试调用 Spigot 传统接口 player.getLocale() -> String
        if (rawLocale == null) {
            try {
                Method getLocaleMethod = player.getClass().getMethod("getLocale");
                Object locObj = getLocaleMethod.invoke(player);
                if (locObj instanceof String s) {
                    rawLocale = s;
                }
            } catch (Throwable ignored) {
                // 忽略反射异常
            }
        }

        if (rawLocale == null || rawLocale.isBlank()) {
            return clientDefaultLanguage;
        }

        String normalized = normalizeLanguageCode(rawLocale);
        String resolved = null;

        // 精确匹配 (例如 zh_cn)
        if (languageConfigs.containsKey(normalized)) {
            resolved = normalized;
        } else if (normalized.contains("_")) {
            // 模糊前缀降级：如玩家客户端是 zh_tw 或 zh_hk，匹配首个 zh 开头的语言包
            String prefix = normalized.substring(0, normalized.indexOf('_'));
            for (String code : languageConfigs.keySet()) {
                if (code.startsWith(prefix)) {
                    resolved = code;
                    break;
                }
            }
        }

        if (resolved == null) {
            resolved = languageConfigs.containsKey(clientDefaultLanguage) ? clientDefaultLanguage : serverLanguage;
        }

        playerLocaleCache.put(uuid, resolved);
        return resolved;
    }

    /**
     * 清理指定玩家的语言缓存 (在玩家切换客户端语言或退出时调用)
     */
    public void invalidateCache(UUID uuid) {
        if (uuid != null) {
            playerLocaleCache.remove(uuid);
        }
    }

    /**
     * 规范化语言代码：去除空格、转为小写、横杠统一转为下划线 (例: "zh-CN" -> "zh_cn")
     */
    public static String normalizeLanguageCode(String raw) {
        if (raw == null) return "zh_cn";
        return raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 四级 Fallback 获取未格式化原始文本：
     * 1. 目标语言磁盘配置 -> 2. 目标语言内置配置 -> 3. 客户端回退语言 -> 4. 硬编码底线语言 -> 5. 原始 key
     */
    public String getRawMessage(String langCode, String key) {
        if (key == null) return "";

        String targetLang = (langCode != null && languageConfigs.containsKey(langCode)) ? langCode : clientDefaultLanguage;

        // 1. 查询目标语言
        String val = lookupString(targetLang, key);
        if (val != null) return val;

        // 2. 查询客户端回退语言
        if (!targetLang.equalsIgnoreCase(clientDefaultLanguage)) {
            val = lookupString(clientDefaultLanguage, key);
            if (val != null) return val;
        }

        // 3. 查询服务端默认语言
        if (!targetLang.equalsIgnoreCase(serverLanguage) && !clientDefaultLanguage.equalsIgnoreCase(serverLanguage)) {
            val = lookupString(serverLanguage, key);
            if (val != null) return val;
        }

        // 4. 硬编码底线语言 (en_us)
        if (!HARDCODED_FALLBACK_LANG.equalsIgnoreCase(targetLang) && languageConfigs.containsKey(HARDCODED_FALLBACK_LANG)) {
            val = lookupString(HARDCODED_FALLBACK_LANG, key);
            if (val != null) return val;
        }

        // 5. 遍历已加载的所有语言包碰运气
        for (String lang : languageConfigs.keySet()) {
            val = lookupString(lang, key);
            if (val != null) return val;
        }

        // 6. 极端保底：直接返回 key，避免 NPE
        return key;
    }

    private String lookupString(String langCode, String key) {
        FileConfiguration config = languageConfigs.get(langCode);
        if (config != null) {
            String str = config.getString(key);
            if (str != null) return str;
        }
        FileConfiguration defaults = defaultConfigs.get(langCode);
        if (defaults != null) {
            return defaults.getString(key);
        }
        return null;
    }

    /**
     * 获取多行文本列表 (适用于帮助菜单、GUI Lore 等)
     */
    public List<String> getRawList(String langCode, String key) {
        if (key == null) return Collections.emptyList();

        String targetLang = (langCode != null && languageConfigs.containsKey(langCode)) ? langCode : clientDefaultLanguage;

        List<String> list = lookupList(targetLang, key);
        if (list != null && !list.isEmpty()) return list;

        if (!targetLang.equalsIgnoreCase(clientDefaultLanguage)) {
            list = lookupList(clientDefaultLanguage, key);
            if (list != null && !list.isEmpty()) return list;
        }

        return Collections.singletonList(key);
    }

    private List<String> lookupList(String langCode, String key) {
        FileConfiguration config = languageConfigs.get(langCode);
        if (config != null) {
            List<String> list = config.getStringList(key);
            if (!list.isEmpty()) return list;
        }
        FileConfiguration defaults = defaultConfigs.get(langCode);
        if (defaults != null) {
            List<String> list = defaults.getStringList(key);
            if (!list.isEmpty()) return list;
        }
        return null;
    }

    /**
     * 替换占位符：同时支持成对参数 (key, value) 以及索引参数 ({0}, {1})
     */
    public String format(String message, Object... args) {
        if (message == null || message.isEmpty() || args == null || args.length == 0) {
            return message != null ? message : "";
        }

        String result = message;

        // 如果参数个数为偶数，且首个参数是字符串，优先作为 key-value 替换
        if (args.length % 2 == 0 && (args[0] instanceof CharSequence)) {
            for (int i = 0; i < args.length; i += 2) {
                String k = String.valueOf(args[i]);
                String v = args[i + 1] != null ? String.valueOf(args[i + 1]) : "null";
                result = result.replace("{" + k + "}", v).replace("%" + k + "%", v);
            }
        }

        // 同时支持 {0}, {1}, {2} 格式的数字索引占位符
        for (int i = 0; i < args.length; i++) {
            String v = args[i] != null ? String.valueOf(args[i]) : "null";
            result = result.replace("{" + i + "}", v);
        }

        return result;
    }

    /**
     * 替换 Map 形式的占位符
     */
    public String format(String message, Map<String, ?> placeholders) {
        if (message == null || message.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return message != null ? message : "";
        }
        String result = message;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            String k = entry.getKey();
            String v = entry.getValue() != null ? String.valueOf(entry.getValue()) : "null";
            result = result.replace("{" + k + "}", v).replace("%" + k + "%", v);
        }
        return result;
    }

    /**
     * 智能文本渲染引擎：
     * 1. 将传统颜色代码 (&a, &c) 与 Legacy Hex (&#RRGGBB) 转换为标准 MiniMessage 标签
     * 2. 使用 MiniMessage 反序列化为现代 Paper Component
     */
    public Component parseToComponent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }

        // 步骤 1: 处理 Hex 颜色代码 &#RRGGBB -> <#RRGGBB>
        String converted = raw;
        Matcher hexMatcher = HEX_COLOR_PATTERN.matcher(converted);
        if (hexMatcher.find()) {
            converted = hexMatcher.replaceAll("<#$1>");
        }

        // 步骤 2: 处理 Spigot Section Hex 格式 (&x&r&r&g&g&b&b)
        if (converted.contains("&x")) {
            Matcher sectionMatcher = SECTION_HEX_PATTERN.matcher(converted);
            StringBuilder sb = new StringBuilder();
            while (sectionMatcher.find()) {
                String hex = sectionMatcher.group().replace("&x", "").replace("&", "");
                sectionMatcher.appendReplacement(sb, "<#" + hex + ">");
            }
            sectionMatcher.appendTail(sb);
            converted = sb.toString();
        }

        // 步骤 3: 处理传统 & 颜色与样式代码 -> MiniMessage 标签
        if (converted.indexOf('&') != -1) {
            StringBuilder sb = new StringBuilder(converted.length() + 32);
            char[] chars = converted.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (chars[i] == '&' && i + 1 < chars.length) {
                    char code = Character.toLowerCase(chars[i + 1]);
                    String tag = LEGACY_TO_MINIMESSAGE.get(code);
                    if (tag != null) {
                        sb.append(tag);
                        i++; // 跳过颜色字符
                        continue;
                    }
                }
                sb.append(chars[i]);
            }
            converted = sb.toString();
        }

        // 步骤 4: 统一使用 MiniMessage 一次性解析 (原生支持渐变、点击、悬浮等现代特性)
        try {
            return miniMessage.deserialize(converted);
        } catch (Exception e) {
            // 解析 MiniMessage 标签语法异常时的容错兜底
            return Component.text(raw);
        }
    }

    // =========================================================================
    // 面向外部调用的高层 API
    // =========================================================================

    /**
     * 获取已格式化并本地化的 Component 消息
     */
    public Component getComponent(CommandSender sender, String key, Object... args) {
        String lang = resolveLanguage(sender);
        String raw = getRawMessage(lang, key);
        String formatted = format(raw, args);
        return parseToComponent(formatted);
    }

    /**
     * 获取包含全局前缀的 Component 消息
     */
    public Component getPrefixedComponent(CommandSender sender, String key, Object... args) {
        String lang = resolveLanguage(sender);
        String prefix = getRawMessage(lang, "prefix");
        String raw = getRawMessage(lang, key);
        String formatted = format(prefix + raw, args);
        return parseToComponent(formatted);
    }

    /**
     * 获取已本地化的多行 Component 列表
     */
    public List<Component> getComponentList(CommandSender sender, String key, Object... args) {
        String lang = resolveLanguage(sender);
        List<String> rawList = getRawList(lang, key);
        List<Component> result = new ArrayList<>(rawList.size());
        for (String raw : rawList) {
            result.add(parseToComponent(format(raw, args)));
        }
        return result;
    }

    /**
     * 发送带本地化及占位符解析的消息
     */
    public void sendMessage(CommandSender sender, String key, Object... args) {
        if (sender == null || key == null) return;
        sender.sendMessage(getComponent(sender, key, args));
    }

    /**
     * 发送附带插件前缀的本地化消息
     */
    public void sendPrefixedMessage(CommandSender sender, String key, Object... args) {
        if (sender == null || key == null) return;
        sender.sendMessage(getPrefixedComponent(sender, key, args));
    }

    /**
     * 全局广播消息：每位在线玩家根据其各自客户端语言独立本地化接收
     */
    public void broadcast(String key, Object... args) {
        // 给控制台发送服务端语言
        plugin.getServer().getConsoleSender().sendMessage(getComponent(null, key, args));
        // 给各玩家按其各自语言发送
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(getComponent(player, key, args));
        }
    }

    /**
     * 权限广播：给拥有特定权限的在线玩家独立本地化发送
     */
    public void broadcastPermission(String permission, String key, Object... args) {
        // 控制台天然拥有权限
        plugin.getServer().getConsoleSender().sendMessage(getComponent(null, key, args));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(getComponent(player, key, args));
            }
        }
    }

    /**
     * 发送本地化 Action Bar
     */
    public void sendActionBar(Player player, String key, Object... args) {
        if (player == null || key == null) return;
        player.sendActionBar(getComponent(player, key, args));
    }

    /**
     * 发送本地化 Title 与 Subtitle
     */
    public void sendTitle(Player player, String titleKey, String subtitleKey, int fadeIn, int stay, int fadeOut, Object... args) {
        if (player == null) return;
        Component titleComp = titleKey != null ? getComponent(player, titleKey, args) : Component.empty();
        Component subComp = subtitleKey != null ? getComponent(player, subtitleKey, args) : Component.empty();

        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L)
        );
        player.showTitle(Title.title(titleComp, subComp, times));
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
        return Collections.unmodifiableSet(languageConfigs.keySet());
    }
}
