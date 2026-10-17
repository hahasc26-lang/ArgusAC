package com.example.aegisac;

import com.example.aegisac.command.AegisCommand;
import com.example.aegisac.command.AegisTabCompleter;
import com.example.aegisac.check.CheckManager;
import com.example.aegisac.data.PlayerManager;
import com.example.aegisac.listener.PlayerEventListener;
import com.example.aegisac.manager.AlertManager;
import com.example.aegisac.manager.PunishmentManager;
import com.example.aegisac.manager.SetbackManager;
import com.example.aegisac.manager.ViolationManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;



/**
 * AegisAC (神盾反作弊) 主插件类
 * 专为 Paper 1.20.4+ 服务端优化，采用模块化结构与原生状态回退机制
 */
public class AegisACPlugin extends JavaPlugin {

    private static AegisACPlugin instance;

    // 核心管理器
    private PlayerManager playerManager;
    private SetbackManager setbackManager;
    private AlertManager alertManager;
    private PunishmentManager punishmentManager;
    private ViolationManager violationManager;
    private CheckManager checkManager;

    // 统一审计日志核心管理器
    private com.example.aegisac.log.core.AuditLogManager auditLogManager;
    private com.example.aegisac.log.maintenance.LogArchiveService logArchiveService;
    private com.example.aegisac.log.query.LogQueryService logQueryService;
    private com.example.aegisac.log.debug.CheckDebugLogger checkDebugLogger;

    // 多语言国际化 (i18n) 管理器
    private com.example.aegisac.i18n.LanguageManager languageManager;

    // 跨版本兼容性调度中心
    private com.example.aegisac.compat.CompatibilityManager compatibilityManager;

    @Override
    public void onEnable() {
        instance = this;

        // 1. 初始化配置文件
        saveDefaultConfig();

        // 1.5 初始化多语言国际化系统 (i18n) 并注册全局门面
        this.languageManager = new com.example.aegisac.i18n.LanguageManager(this);
        com.example.aegisac.i18n.I18n.init(this.languageManager);

        // 2. 检查最低支持版本 (1.12.2)，过低版本安全挂起退出，不抛异常
        if (com.example.aegisac.compat.ServerVersion.isOlderThan(com.example.aegisac.compat.ServerVersion.V1_12_2)) {
            getLogger().severe("==================================================");
            getLogger().severe(" [AegisAC 兼容性拦截] 当前服务器版本过低: " + com.example.aegisac.compat.ServerVersion.get().getFriendlyName());
            getLogger().severe(" AegisAC 架构要求最低 Minecraft 1.12.2，为保护服务器稳定性，插件已安全停用。");
            getLogger().severe("==================================================");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 启动主线程轻量级 TickMonitor 采样器 (为纯 Spigot 环境提供零开销 TPS 兜底)
        com.example.aegisac.compat.impl.TickMonitor.start(this);

        // 4. 初始化跨版本兼容适配体系与环境自检
        this.compatibilityManager = new com.example.aegisac.compat.CompatibilityManager(this);
        this.compatibilityManager.printStartupDiagnosis();

        // 5. 初始化统一日志系统
        this.auditLogManager = new com.example.aegisac.log.core.AuditLogManager(this);
        this.logArchiveService = new com.example.aegisac.log.maintenance.LogArchiveService(this, this.auditLogManager);
        this.logQueryService = new com.example.aegisac.log.query.LogQueryService(this, this.auditLogManager);
        this.checkDebugLogger = new com.example.aegisac.log.debug.CheckDebugLogger(this);

        // 6. 依次初始化各子系统管理器
        this.playerManager = new PlayerManager();
        this.setbackManager = new SetbackManager(this);
        this.alertManager = new AlertManager(this);
        this.punishmentManager = new PunishmentManager(this);
        this.violationManager = new ViolationManager(this);
        this.checkManager = new CheckManager(this);
        this.compatibilityManager.evaluateAllChecks();

        // 7. 注册事件监听器 (包含主事件监听器与独立回退引擎监听器)
        getServer().getPluginManager().registerEvents(new PlayerEventListener(this), this);
        getServer().getPluginManager().registerEvents(this.setbackManager, this);

        // 8. 针对 1.13+ 现代专有事件进行条件式动态注册 (杜绝 1.12.2 类装载 NoClassDefFoundError)
        if (com.example.aegisac.compat.ServerVersion.isAtLeast(com.example.aegisac.compat.ServerVersion.V1_13)) {
            getServer().getPluginManager().registerEvents(new com.example.aegisac.listener.ModernEventListener(this), this);
        }

        // 9. 注册全版本客户端品牌 PluginMessage 通道监听
        registerBrandChannelListeners();

        // 10. 注册命令与补全器
        PluginCommand cmd = getCommand("aegis");
        if (cmd != null) {
            cmd.setExecutor(new AegisCommand(this));
            cmd.setTabCompleter(new AegisTabCompleter(this));
        }

        // 11. 初始化已有在线玩家数据 (兼容 /reload 或热重载插件情况)
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            playerManager.getPlayerData(online);
            setbackManager.getContext(online.getUniqueId()).reset(
                    online.getLocation(),
                    online.isInsideVehicle(),
                    online.getVehicle() != null ? online.getVehicle().getUniqueId() : null
            );
        }

        // 12. 打印就绪横幅
        printBanner();
    }

    private void registerBrandChannelListeners() {
        try {
            org.bukkit.plugin.messaging.PluginMessageListener listener = (channel, player, message) -> {
                try {
                    String brand = new String(message, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!brand.isEmpty() && playerManager != null) {
                        var data = playerManager.getPlayerData(player);
                        if (data != null) {
                            data.setClientBrand(brand);
                        }
                    }
                } catch (Throwable ignored) {}
            };
            getServer().getMessenger().registerIncomingPluginChannel(this, "MC|Brand", listener);
            getServer().getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", listener);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDisable() {
        if (violationManager != null) {
            violationManager.stopDecayTask();
        }
        if (playerManager != null) {
            playerManager.clear();
        }
        if (setbackManager != null) {
            setbackManager.cleanup();
        }
        if (logArchiveService != null) {
            logArchiveService.cancel();
        }
        if (checkDebugLogger != null) {
            checkDebugLogger.shutdown();
        }
        if (auditLogManager != null) {
            auditLogManager.shutdown();
        }
        getLogger().info("[AegisAC] 插件已安全卸载，所有审计日志已 Flush，内存缓存已释放。");
        instance = null;
    }

    /**
     * 热重载所有配置与模块
     */
    public void reloadAll() {
        reloadConfig();
        if (languageManager != null) languageManager.reload();
        if (setbackManager != null) setbackManager.reloadConfig();
        if (alertManager != null) alertManager.reloadConfig();
        if (punishmentManager != null) punishmentManager.reloadConfig();
        if (checkManager != null) checkManager.reloadAll();
        if (violationManager != null) violationManager.startDecayTask();
    }

    @SuppressWarnings("deprecation")
    private void printBanner() {
        getLogger().info("==================================================");
        getLogger().info("   ___             _        _   ___  ");
        getLogger().info("  / _ \\ ___  __ _ (_)___   / \\ / _ \\ ");
        getLogger().info(" / /_\\// _ \\/ _` || / __| / _ \\ | | |");
        getLogger().info("/ /_\\\\|  __/ (_| || \\__ \\/ ___ \\| |_| |");
        getLogger().info("\\____/ \\___|\\__, ||_|___/_/   \\_\\\\___/ ");
        getLogger().info("            |___/   神盾反作弊 v" + this.getDescription().getVersion());
        getLogger().info(" 目标平台: Spigot / Paper 1.12.2 ~ 1.21.x | 运行环境: Java 17");
        getLogger().info(" 已装载检测项: " + checkManager.getAllChecks().size() + " 个独立检测模块");
        getLogger().info(" 核心回退 (Setback): " + (setbackManager.isGlobalEnabled() ? "全局已启用" : "已就绪 (保守待命)"));
        getLogger().info(" 统一审计日志系统: 已挂载 (长驻Writer / 链式SHA-256 / 零IO)");
        getLogger().info("==================================================");
    }

    // ==================== Getters ====================

    public static AegisACPlugin getInstance() {
        return instance;
    }

    /**
     * 判断玩家当前是否处于豁免状态 (拥有 bypass 权限，或配置了直接豁免创造模式玩家)
     */
    public boolean isPlayerExempt(org.bukkit.entity.Player player) {
        if (player == null) {
            return true;
        }
        if (player.hasPermission("aegisac.bypass")) {
            return true;
        }
        boolean bypassCreative = getConfig().getBoolean("settings.bypass-creative", true);
        return bypassCreative && player.getGameMode() == org.bukkit.GameMode.CREATIVE;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public SetbackManager getSetbackManager() {
        return setbackManager;
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public PunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    public ViolationManager getViolationManager() {
        return violationManager;
    }

    public CheckManager getCheckManager() {
        return checkManager;
    }

    public com.example.aegisac.log.core.AuditLogManager getAuditLogManager() {
        return auditLogManager;
    }

    public com.example.aegisac.log.maintenance.LogArchiveService getLogArchiveService() {
        return logArchiveService;
    }

    public com.example.aegisac.log.query.LogQueryService getLogQueryService() {
        return logQueryService;
    }

    public com.example.aegisac.log.debug.CheckDebugLogger getCheckDebugLogger() {
        return checkDebugLogger;
    }

    public com.example.aegisac.compat.CompatibilityManager getCompatibilityManager() {
        return compatibilityManager;
    }

    public com.example.aegisac.i18n.LanguageManager getLanguageManager() {
        return languageManager;
    }
}
