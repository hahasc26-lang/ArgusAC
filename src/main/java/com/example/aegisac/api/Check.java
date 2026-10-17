package com.example.aegisac.api;

/**
 * 反作弊检查接口
 * 所有具体检测模块需实现此接口
 */
public interface Check {

    /**
     * 检测模块英文唯一名称 (如 Speed, Fly, Reach)
     */
    String getName();

    /**
     * 检测所属分类 (MOVEMENT, COMBAT, WORLD, PACKET)
     */
    CheckType getType();

    /**
     * 该检测当前是否启用
     */
    boolean isEnabled();

    /**
     * 设置该检测是否启用
     */
    void setEnabled(boolean enabled);

    /**
     * 最大惩罚 VL 阈值
     */
    int getMaxVl();

    /**
     * 该检测是否支持/启用回退机制
     */
    boolean isSetbackEnabled();

    /**
     * 该检测触发回退所需的最低 VL 阈值
     */
    int getSetbackMinVl();

    /**
     * 该检测指定的回退模式 (SOFT 或 HARD)
     */
    String getSetbackMode();

    /**
     * 重新从配置文件加载该检测的参数配置
     */
    void reloadConfig();
}
