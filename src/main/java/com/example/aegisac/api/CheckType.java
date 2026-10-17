package com.example.aegisac.api;

/**
 * 检测分类枚举
 */
public enum CheckType {
    MOVEMENT("移动"),
    COMBAT("战斗"),
    WORLD("世界交互"),
    PACKET("数据包");

    private final String displayName;

    CheckType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
