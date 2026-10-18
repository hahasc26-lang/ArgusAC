package com.example.argusac.check;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.api.CheckType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * 反作弊检查抽象基类 (AbstractCheck)
 * 封装通用配置读取、VL 告警触发与回退支持
 */
public abstract class AbstractCheck implements Check {

    protected final ArgusACPlugin plugin;
    protected final String name;
    protected final CheckType type;

    protected boolean enabled;
    protected int maxVl;
    protected boolean setbackEnabled;
    protected int setbackMinVl;
    protected String setbackMode;

    public AbstractCheck(ArgusACPlugin plugin, String name, CheckType type) {
        this.plugin = plugin;
        this.name = name;
        this.type = type;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public CheckType getType() {
        return type;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getChecksConfig().set("checks." + name + ".enabled", enabled);
        plugin.saveChecksConfig();
    }

    @Override
    public int getMaxVl() {
        return maxVl;
    }

    @Override
    public boolean isSetbackEnabled() {
        return setbackEnabled;
    }

    @Override
    public int getSetbackMinVl() {
        return setbackMinVl;
    }

    @Override
    public String getSetbackMode() {
        return setbackMode != null ? setbackMode : plugin.getSetbackManager().getDefaultMode();
    }

    @Override
    public void reloadConfig() {
        FileConfiguration checksConfig = plugin.getChecksConfig();
        FileConfiguration config = plugin.getConfig();
        String path = "checks." + name + ".";
        this.enabled = checksConfig.getBoolean(path + "enabled", true);
        this.maxVl = checksConfig.getInt(path + "max-vl", 15);

        // 回退模块配置覆写 (回退作为默认处罚手段，默认 min-vl 为全局 default-min-vl: 1)
        int defaultMinVl = config.getInt("setback.default-min-vl", 1);
        String setbackPath = "setback.per-check." + name + ".";
        this.setbackEnabled = config.getBoolean(setbackPath + "enabled", true);
        this.setbackMinVl = config.getInt(setbackPath + "min-vl", defaultMinVl);
        this.setbackMode = config.getString(setbackPath + "mode", plugin.getSetbackManager().getDefaultMode()).toUpperCase();
    }

    /**
     * 获取 checks.yml 独立配置对象
     */
    protected FileConfiguration getChecksConfig() {
        return plugin.getChecksConfig();
    }

    /**
     * 标记玩家作弊行为并递交违规管理器处理
     *
     * @param player 违规玩家
     * @param vl 增加的 VL 点数
     * @param details 违规指标详情
     */
    protected void flag(Player player, int vl, String details) {
        plugin.getViolationManager().handleViolation(player, this, vl, details, null);
    }

    /**
     * 标记移动作弊行为并携带移动事件 (支持 SOFT 模式原地修正终点)
     *
     * @param player 违规玩家
     * @param vl 增加的 VL 点数
     * @param details 违规指标详情
     * @param moveEvent 移动事件
     */
    protected void flag(Player player, int vl, String details, org.bukkit.event.player.PlayerMoveEvent moveEvent) {
        // 根治空中悬停漏洞：禁止在 flag 阶段未审先判地强制设置 moveEvent.setTo(from)！
        // 这一行为会导致空中触发违规时直接清零当 tick 的垂直位移 ΔY，破坏自然重力加速度并把玩家死死钉在半空。
        // 一切位移拦截与回退处置，全权交由 ViolationManager 与 SetbackManager 依据 VL 阈值与地面物理守恒原则严格裁决。
        plugin.getViolationManager().handleViolation(player, this, vl, details, moveEvent);
    }
}
