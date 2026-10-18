package com.example.argusac;

import com.example.argusac.command.ArgusCommand;
import com.example.argusac.command.ArgusTabCompleter;
import com.example.argusac.manager.CheckManager;
import com.example.argusac.manager.PlayerManager;
import com.example.argusac.listener.PlayerEventListener;
import com.example.argusac.manager.AlertManager;
import com.example.argusac.manager.PunishmentManager;
import com.example.argusac.manager.SetbackManager;
import com.example.argusac.manager.ViolationManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;



/**
 * ArgusAC 主插件类
 * 专为 Paper 1.20.4+ 服务端优化，采用模块化结构与原生状态回退机制
 */
public class ArgusACPlugin extends JavaPlugin {

    private static ArgusACPlugin instance;

    // 核心管理器
    private PlayerManager playerManager;
    private SetbackManager setbackManager;
    private AlertManager alertManager;
    private PunishmentManager punishmentManager;
    private ViolationManager violationManager;
    private CheckManager checkManager;

    // 统一审计日志核心管理器
    private com.example.argusac.log.core.AuditLogManager auditLogManager;
    private com.example.argusac.log.maintenance.LogArchiveService logArchiveService;
    private com.example.argusac.log.query.LogQueryService logQueryService;
    private com.example.argusac.log.debug.CheckDebugLogger checkDebugLogger;

    // 多语言国际化 (i18n) 管理器
    private com.example.argusac.i18n.LanguageManager languageManager;

    // 跨版本兼容性调度中心
    private com.example.argusac.compat.CompatibilityManager compatibilityManager;

    // 独立检测配置 (checks.yml)
    private org.bukkit.configuration.file.FileConfiguration checksConfig;
    private java.io.File checksConfigFile;

    // 独立战斗交互与伤害拦截处理器
    private com.example.argusac.combat.CombatMitigationHandler combatMitigationHandler;

    // 底层数据包协议管线管理器 (PacketEvents)
    private com.example.argusac.packet.PacketPipelineManager packetPipelineManager;

    // 确定性移动模拟预测器 (Prediction Simulation)
    private com.example.argusac.prediction.DeterministicMovePredictor movePredictor;

    // 实体历史碰撞盒回溯追踪器 (Lag Compensation History)
    private com.example.argusac.history.EntityHistoryTracker historyTracker;


    @Override
    public void onLoad() {
        try {
            com.github.retrooper.packetevents.PacketEvents.setAPI(
                    io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder.build(this)
            );
            com.github.retrooper.packetevents.PacketEvents.getAPI().load();
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.WARNING, "Failed to load PacketEvents API in onLoad", t);
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        // 0. 初始化配置文件与多语言国际化系统 (i18n) 并注册全局门面
        saveDefaultConfig();
        saveDefaultChecksConfig();
        this.languageManager = new com.example.argusac.i18n.LanguageManager(this);
        com.example.argusac.i18n.I18n.init(this.languageManager);

        // 初始化独立战斗拦截处理器
        this.combatMitigationHandler = new com.example.argusac.combat.CombatMitigationHandler(this);


        // 1. 初始化底层 PacketEvents 数据包管线
        try {
            com.github.retrooper.packetevents.PacketEvents.getAPI().init();
            this.packetPipelineManager = new com.example.argusac.packet.PacketPipelineManager(this);
            this.packetPipelineManager.init();
        } catch (Throwable t) {
            getLogger().log(java.util.logging.Level.SEVERE, "Failed to initialize PacketEvents pipeline", t);
        }

        // 2. 检查最低支持版本 (1.12.2)，过低版本安全挂起退出，不抛异常
        if (com.example.argusac.compat.ServerVersion.isOlderThan(com.example.argusac.compat.ServerVersion.V1_12_2)) {
            getLogger().severe("==================================================");
            getLogger().severe(" [ArgusAC Compatibility] Unsupported server version: " + com.example.argusac.compat.ServerVersion.get().getFriendlyName());
            getLogger().severe(" ArgusAC requires Minecraft 1.12.2+. Plugin disabled safely for server stability.");
            getLogger().severe("==================================================");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 启动主线程轻量级 TickMonitor 采样器 (为纯 Spigot 环境提供零开销 TPS 兜底)
        com.example.argusac.compat.impl.TickMonitor.start(this);

        // 4. 初始化跨版本兼容适配体系与环境自检
        this.compatibilityManager = new com.example.argusac.compat.CompatibilityManager(this);
        this.compatibilityManager.printStartupDiagnosis();

        // 5. 初始化统一日志系统
        this.auditLogManager = new com.example.argusac.log.core.AuditLogManager(this);
        this.logArchiveService = new com.example.argusac.log.maintenance.LogArchiveService(this, this.auditLogManager);
        this.logQueryService = new com.example.argusac.log.query.LogQueryService(this, this.auditLogManager);
        this.checkDebugLogger = new com.example.argusac.log.debug.CheckDebugLogger(this);

        // 6. 依次初始化各子系统管理器
        this.playerManager = new PlayerManager();
        this.setbackManager = new SetbackManager(this);
        this.alertManager = new AlertManager(this);
        this.punishmentManager = new PunishmentManager(this);
        this.violationManager = new ViolationManager(this);
        this.checkManager = new CheckManager(this);
        this.movePredictor = new com.example.argusac.prediction.DeterministicMovePredictor(this);
        this.historyTracker = new com.example.argusac.history.EntityHistoryTracker(this);
        this.historyTracker.start();
        this.compatibilityManager.evaluateAllChecks();

        // 7. 注册事件监听器 (包含主事件监听器与独立回退引擎监听器)
        getServer().getPluginManager().registerEvents(new PlayerEventListener(this), this);
        getServer().getPluginManager().registerEvents(this.setbackManager, this);

        // 8. 针对 1.13+ 现代专有事件进行条件式动态注册 (杜绝 1.12.2 类装载 NoClassDefFoundError)
        if (com.example.argusac.compat.ServerVersion.isAtLeast(com.example.argusac.compat.ServerVersion.V1_13)) {
            getServer().getPluginManager().registerEvents(new com.example.argusac.listener.ModernEventListener(this), this);
        }

        // 9. 注册全版本客户端品牌 PluginMessage 通道监听
        registerBrandChannelListeners();

        // 10. 注册命令与补全器
        PluginCommand cmd = getCommand("argus");
        if (cmd != null) {
            cmd.setExecutor(new ArgusCommand(this));
            cmd.setTabCompleter(new ArgusTabCompleter(this));
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
        if (combatMitigationHandler != null) {
            combatMitigationHandler.clearAll();
        }
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
        if (packetPipelineManager != null) {
            packetPipelineManager.shutdown();
        }
        if (historyTracker != null) {
            historyTracker.stop();
        }
        try {
            com.github.retrooper.packetevents.PacketEvents.getAPI().terminate();
        } catch (Throwable ignored) {}

        if (auditLogManager != null) {
            auditLogManager.shutdown();
        }
        getLogger().info("[ArgusAC] Plugin safely unloaded. All audit logs flushed and caches released.");
        instance = null;
    }

    /**
     * 热重载所有配置与模块
     */
    public void reloadAll() {
        reloadConfig();
        reloadChecksConfig();
        if (languageManager != null) languageManager.reload();
        if (setbackManager != null) setbackManager.reloadConfig();
        if (alertManager != null) alertManager.reloadConfig();
        if (punishmentManager != null) punishmentManager.reloadConfig();
        if (checkManager != null) checkManager.reloadAll();
        if (movePredictor != null) movePredictor.reloadConfig();
        if (violationManager != null) violationManager.startDecayTask();
    }

    @SuppressWarnings("deprecation")
    private void printBanner() {
        getLogger().info("==================================================");
        getLogger().info("    _                               _     ____  ");
        getLogger().info("   / \\   _ __ __ _ _   _ ___       / \\   / ___| ");
        getLogger().info("  / _ \\ | '__/ _` | | | / __|     / _ \\ | |     ");
        getLogger().info(" / ___ \\| | | (_| | |_| \\__ \\    / ___ \\| |___  ");
        getLogger().info("/_/   \\_\\_|  \\__, |\\__,_|___/   /_/   \\_\\\\____| ");
        getLogger().info("             |___/    ArgusAC AntiCheat v" + this.getDescription().getVersion());
        getLogger().info(" Target: Spigot / Paper 1.12.2 ~ 1.21.x | Runtime: Java 17");
        getLogger().info(" Packet Pipeline: PacketEvents v2.7.0 (Native Packet State Machine)");
        getLogger().info(" Checks: " + checkManager.getAllChecks().size() + " registered modules");
        getLogger().info(" Setback: " + (setbackManager.isGlobalEnabled() ? "Globally Enabled" : "Standby"));
        getLogger().info(" Audit Log Engine: Active (Persistent Writer / SHA-256 Chain / Non-blocking)");
        getLogger().info("==================================================");
    }

    // ==================== Getters ====================

    public static ArgusACPlugin getInstance() {
        return instance;
    }

    public com.example.argusac.packet.PacketPipelineManager getPacketPipelineManager() {
        return packetPipelineManager;
    }

    public com.example.argusac.prediction.DeterministicMovePredictor getMovePredictor() {
        return movePredictor;
    }

    public com.example.argusac.history.EntityHistoryTracker getHistoryTracker() {
        return historyTracker;
    }

    /**
     * 判断玩家当前是否处于豁免状态 (拥有 bypass 权限，或配置了直接豁免创造模式玩家)
     */
    public boolean isPlayerExempt(org.bukkit.entity.Player player) {
        if (player == null) {
            return true;
        }
        if (player.hasPermission("argusac.bypass") || player.hasPermission("ArgusAC.bypass")) {
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

    public com.example.argusac.log.core.AuditLogManager getAuditLogManager() {
        return auditLogManager;
    }

    public com.example.argusac.log.maintenance.LogArchiveService getLogArchiveService() {
        return logArchiveService;
    }

    public com.example.argusac.log.query.LogQueryService getLogQueryService() {
        return logQueryService;
    }

    public com.example.argusac.log.debug.CheckDebugLogger getCheckDebugLogger() {
        return checkDebugLogger;
    }

    public com.example.argusac.compat.CompatibilityManager getCompatibilityManager() {
        return compatibilityManager;
    }

    public com.example.argusac.i18n.LanguageManager getLanguageManager() {
        return languageManager;
    }

    public com.example.argusac.combat.CombatMitigationHandler getCombatMitigationHandler() {
        return combatMitigationHandler;
    }

    // ==================== checks.yml 独立配置文件管理 ====================

    public void saveDefaultChecksConfig() {
        if (checksConfigFile == null) {
            checksConfigFile = new java.io.File(getDataFolder(), "checks.yml");
        }
        if (!checksConfigFile.exists()) {
            saveResource("checks.yml", false);
        }
    }

    public org.bukkit.configuration.file.FileConfiguration getChecksConfig() {
        if (checksConfig == null) {
            reloadChecksConfig();
        }
        return checksConfig;
    }

    public void reloadChecksConfig() {
        if (checksConfigFile == null) {
            checksConfigFile = new java.io.File(getDataFolder(), "checks.yml");
        }
        checksConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(checksConfigFile);
    }

    public void saveChecksConfig() {
        if (checksConfig == null || checksConfigFile == null) {
            return;
        }
        try {
            checksConfig.save(checksConfigFile);
        } catch (java.io.IOException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Could not save checks.yml to " + checksConfigFile, e);
        }
    }
}

