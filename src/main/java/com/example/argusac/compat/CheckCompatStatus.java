package com.example.argusac.compat;

public enum CheckCompatStatus {
    FULL("&a完整支持", "当前服务端核心 API 满足本模块全部高精度数学检测模型"),
    DEGRADED("&e降级运行", "部分高版本 API 不可用，已自动切换为几何近似或旧版本兜底模型"),
    DISABLED("&c版本禁用", "当前版本缺少本机制所需的基础物理特性，已优雅静默关闭以杜绝误报");

    private final String display;
    private final String description;

    CheckCompatStatus(String display, String description) {
        this.display = display;
        this.description = description;
    }

    public String getDisplay() { return display; }
    public String getDescription() { return description; }
}
