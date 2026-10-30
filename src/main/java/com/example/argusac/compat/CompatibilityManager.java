package com.example.argusac.compat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.i18n.I18n;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时自检与运行时能力矩阵调度中心
 */
public class CompatibilityManager {

    private final ArgusACPlugin plugin;
    private final Map<String, CheckCompatStatus> checkStatusMap = new LinkedHashMap<>();
    private final Map<String, String> statusReasons = new LinkedHashMap<>();

    public CompatibilityManager(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    public void evaluateAllChecks() {
        checkStatusMap.clear();
        statusReasons.clear();

        // 1. ElytraFly 鞘翅飞行
        if (ServerVersion.isOlderThan(ServerVersion.V1_12_2)) {
            markCheck("ElytraFly", CheckCompatStatus.DISABLED, "compat.reason.elytrafly-disabled");
        } else {
            markCheck("ElytraFly", CheckCompatStatus.FULL, "compat.reason.elytrafly-full");
        }

        // 2. Speed / Fly 移动检测
        if (ServerVersion.isOlderThan(ServerVersion.V1_13)) {
            markCheck("Speed", CheckCompatStatus.DEGRADED, "compat.reason.speed-degraded");
            markCheck("Fly", CheckCompatStatus.FULL, "compat.reason.fly-legacy");
        } else {
            markCheck("Speed", CheckCompatStatus.FULL, "compat.reason.speed-full");
            markCheck("Fly", CheckCompatStatus.FULL, "compat.reason.fly-full");
        }

        // 3. KillAura / Reach / AutoClicker / Velocity / FastBow / FastEat 战斗检测
        if (ServerVersion.isOlderThan(ServerVersion.V1_13)) {
            markCheck("Reach", CheckCompatStatus.DEGRADED, "compat.reason.reach-degraded");
            markCheck("KillAura", CheckCompatStatus.DEGRADED, "compat.reason.killaura-degraded");
        } else {
            markCheck("Reach", CheckCompatStatus.FULL, "compat.reason.reach-full");
            markCheck("KillAura", CheckCompatStatus.FULL, "compat.reason.killaura-full");
        }
        markCheck("AutoClicker", CheckCompatStatus.FULL, "compat.reason.autoclicker-full");
        markCheck("Velocity", CheckCompatStatus.FULL, "compat.reason.velocity-full");
        markCheck("FastBow", CheckCompatStatus.FULL, "compat.reason.fastbow-full");
        markCheck("FastEat", CheckCompatStatus.FULL, "compat.reason.fasteat-full");

        // 4. BadPackets 协议检测 (真实检查底层数据包监听管线是否就绪)
        if (plugin.getPacketPipelineManager() != null && plugin.getPacketPipelineManager().isActive()) {
            markCheck("BadPackets", CheckCompatStatus.FULL, "compat.reason.badpackets-full");
        } else {
            markCheck("BadPackets", CheckCompatStatus.DEGRADED, "compat.reason.badpackets-degraded");
        }

        // 5. 其余基础检测 (NoFall, FastBreak, Nuker, Scaffold, InventoryMove, Jesus)
        markCheck("NoFall", CheckCompatStatus.FULL, "compat.reason.nofall-full");
        markCheck("FastBreak", CheckCompatStatus.FULL, "compat.reason.fastbreak-full");
        markCheck("Nuker", CheckCompatStatus.FULL, "compat.reason.nuker-full");
        markCheck("Scaffold", CheckCompatStatus.FULL, "compat.reason.scaffold-full");
        markCheck("InventoryMove", CheckCompatStatus.FULL, "compat.reason.inventorymove-full");
        markCheck("Jesus", CheckCompatStatus.FULL, "compat.reason.jesus-full");

        // 6. 重锤 (Mace) 战斗与下落伤害检测 (Minecraft 1.21+ 专属机制)
        if (ServerVersion.isOlderThan(ServerVersion.V1_21)) {
            markCheck("Mace", CheckCompatStatus.DISABLED, "compat.reason.mace-disabled");
        } else {
            markCheck("Mace", CheckCompatStatus.FULL, "compat.reason.mace-full");
        }
    }

    private void markCheck(String checkName, CheckCompatStatus status, String reasonKey) {
        checkStatusMap.put(checkName, status);
        statusReasons.put(checkName, reasonKey);

        if (plugin.getCheckManager() != null) {
            Check check = plugin.getCheckManager().getCheck(checkName);
            if (check != null && status == CheckCompatStatus.DISABLED) {
                check.setEnabled(false);
            }
        }
    }

    public CheckCompatStatus getStatus(String checkName) {
        return checkStatusMap.getOrDefault(checkName, CheckCompatStatus.FULL);
    }

    public String getReason(String checkName) {
        String key = statusReasons.get(checkName);
        if (key == null) return I18n.getServer("compat.reason.default");
        return I18n.getServer(key);
    }

    public String getReason(CommandSender sender, String checkName) {
        String key = statusReasons.get(checkName);
        if (key == null) return I18n.get(sender, "compat.reason.default");
        return I18n.get(sender, key);
    }

    public Map<String, CheckCompatStatus> getAllStatus() {
        return checkStatusMap;
    }

    public boolean hasDegradedModules() {
        return checkStatusMap.values().stream().anyMatch(s -> s == CheckCompatStatus.DEGRADED || s == CheckCompatStatus.DISABLED);
    }

    public void printStartupDiagnosis() {
        Bukkit.getLogger().info("==================================================");
        Bukkit.getLogger().info(" [ArgusAC] " + I18n.getServer("compat.diag.ready"));
        Bukkit.getLogger().info(" " + I18n.getServer("compat.diag.core", "core", ServerVersion.get().getFriendlyName(), "type", (ServerVersion.isPaper() ? "Paper" : "Spigot/CraftBukkit")));
        Bukkit.getLogger().info(" " + I18n.getServer("compat.diag.code", "code", ServerVersion.getCode()));
        Bukkit.getLogger().info(" " + I18n.getServer("compat.diag.adapter", "adapter", VersionAdapterFactory.getAdapter().getClass().getSimpleName()));
        Bukkit.getLogger().info(" " + I18n.getServer("compat.diag.matrix", "count", checkStatusMap.size()));
        if (hasDegradedModules()) {
            Bukkit.getLogger().info(" " + I18n.getServer("compat.diag.degraded-notice"));
        }
        Bukkit.getLogger().info("==================================================");
    }
}


