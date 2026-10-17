package com.example.aegisac.model;

/**
 * 玩家个人回退覆写策略枚举
 */
public enum SetbackOverride {
    /** 继承全局与检测模块默认配置 */
    DEFAULT,
    /** 强制对该玩家禁用任何回退 */
    DISABLED,
    /** 强制对该玩家仅允许 SOFT 模式回退 */
    SOFT_ONLY,
    /** 强制对该玩家仅允许 HARD 模式回退 */
    HARD_ONLY
}
