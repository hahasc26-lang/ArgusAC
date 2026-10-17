package com.example.aegisac.compat;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.Check;
import org.bukkit.Bukkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时自检与运行时能力矩阵调度中心
 */
public class CompatibilityManager {

    private final AegisACPlugin plugin;
    private final Map<String, CheckCompatStatus> checkStatusMap = new LinkedHashMap<>();
    private final Map<String, String> statusReasons = new LinkedHashMap<>();

    public CompatibilityManager(AegisACPlugin plugin) {
        this.plugin = plugin;
        // 注意：此处不调用 evaluateAllChecks()，因为 checkManager 在主类 onEnable 第 78 行才初始化。
        // 真正生效的 evaluateAllChecks() 调用在 AegisACPlugin.onEnable() 第 79 行执行。
    }

    public void evaluateAllChecks() {
        checkStatusMap.clear();
        statusReasons.clear();

        // 1. ElytraFly 鞘翅飞行
        if (ServerVersion.isOlderThan(ServerVersion.V1_12_2)) {
            markCheck("ElytraFly", CheckCompatStatus.DISABLED, "Minecraft 1.9 以前不存在滑翔机制");
        } else {
            markCheck("ElytraFly", CheckCompatStatus.FULL, "完整支持滑翔姿态采样");
        }

        // 2. Speed / Fly 移动检测
        if (ServerVersion.isOlderThan(ServerVersion.V1_13)) {
            markCheck("Speed", CheckCompatStatus.DEGRADED, "1.13 以前无 swimming 姿态，在水中采用老版浮力模型推算");
            markCheck("Fly", CheckCompatStatus.FULL, "基础位移向量与重力加速度全版本支持");
        } else {
            markCheck("Speed", CheckCompatStatus.FULL, "支持游泳与原生 BoundingBox 边缘碰撞");
            markCheck("Fly", CheckCompatStatus.FULL, "完整支持现代高度边界计算");
        }

        // 3. KillAura / Reach 战斗检测
        if (ServerVersion.isOlderThan(ServerVersion.V1_13)) {
            markCheck("Reach", CheckCompatStatus.DEGRADED, "无 Entity#getBoundingBox，采用 0.6x1.8 盒状近似计算");
        } else {
            markCheck("Reach", CheckCompatStatus.FULL, "支持原生精确碰撞盒交互距离运算");
        }

        // 4. BadPackets 协议检测
        boolean hasProtocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
        if (!hasProtocolLib) {
            markCheck("BadPackets", CheckCompatStatus.DEGRADED, "未装载 ProtocolLib，仅依赖 Bukkit 移动/传送事件做基础校验");
        } else {
            markCheck("BadPackets", CheckCompatStatus.FULL, "ProtocolLib 联动已建立，支持底层数据包时序追踪");
        }

        // 5. 其余基础检测 (NoFall, FastBreak, Scaffold, InventoryMove)
        markCheck("NoFall", CheckCompatStatus.FULL, "坠落动量与地面状态全版本支持");
        markCheck("FastBreak", CheckCompatStatus.FULL, "方块挖掘时序全版本支持");
        markCheck("Scaffold", CheckCompatStatus.FULL, "搭建朝向与视线交点全版本支持");
        markCheck("InventoryMove", CheckCompatStatus.FULL, "背包开启状态监听全版本支持");
    }

    private void markCheck(String checkName, CheckCompatStatus status, String reason) {
        checkStatusMap.put(checkName, status);
        statusReasons.put(checkName, reason);

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
        return statusReasons.getOrDefault(checkName, "正常运转");
    }

    public Map<String, CheckCompatStatus> getAllStatus() {
        return checkStatusMap;
    }

    public boolean hasDegradedModules() {
        return checkStatusMap.values().stream().anyMatch(s -> s == CheckCompatStatus.DEGRADED || s == CheckCompatStatus.DISABLED);
    }

    public void printStartupDiagnosis() {
        Bukkit.getLogger().info("==================================================");
        Bukkit.getLogger().info(" [AegisAC] 多版本自适应环境自检就绪:");
        Bukkit.getLogger().info(" 识别内核: " + ServerVersion.get().getFriendlyName() + " (" + (ServerVersion.isPaper() ? "Paper" : "Spigot/CraftBukkit") + ")");
        Bukkit.getLogger().info(" 版本编码: " + ServerVersion.getCode());
        Bukkit.getLogger().info(" 适配器通道: " + VersionAdapterFactory.getAdapter().getClass().getSimpleName());
        Bukkit.getLogger().info(" 模块能力矩阵: " + checkStatusMap.size() + " 个检测项已分配运行等级");
        if (hasDegradedModules()) {
            Bukkit.getLogger().info(" [提示] 检测到部分模块运行在降级兼容模式，使用 /aegis compat 可查看详情。");
        }
        Bukkit.getLogger().info("==================================================");
    }
}
