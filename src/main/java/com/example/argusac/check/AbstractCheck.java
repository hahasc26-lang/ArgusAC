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
        plugin.getConfig().set("checks." + name + ".enabled", enabled);
        plugin.saveConfig();
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
        FileConfiguration config = plugin.getConfig();
        String path = "checks." + name + ".";
        this.enabled = config.getBoolean(path + "enabled", true);
        this.maxVl = config.getInt(path + "max-vl", 15);

        // 回退模块配置覆写 (回退作为默认处罚手段，默认 min-vl 为全局 default-min-vl: 1)
        int defaultMinVl = config.getInt("setback.default-min-vl", 1);
        String setbackPath = "setback.per-check." + name + ".";
        this.setbackEnabled = config.getBoolean(setbackPath + "enabled", true);
        this.setbackMinVl = config.getInt(setbackPath + "min-vl", defaultMinVl);
        this.setbackMode = config.getString(setbackPath + "mode", plugin.getSetbackManager().getDefaultMode()).toUpperCase();
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
        plugin.getViolationManager().handleViolation(player, this, vl, details, moveEvent);
    }
}
