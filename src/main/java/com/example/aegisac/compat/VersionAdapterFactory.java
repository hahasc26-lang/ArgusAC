package com.example.aegisac.compat;

import com.example.aegisac.compat.impl.LegacyVersionAdapter;
import com.example.aegisac.compat.impl.ModernVersionAdapter;
import org.bukkit.Bukkit;

/**
 * 适配器工厂：根据服务器版本与环境动态分发最匹配的实现
 */
public final class VersionAdapterFactory {

    private static final VersionAdapter ADAPTER;

    static {
        if (ServerVersion.isAtLeast(ServerVersion.V1_20_4) && ServerVersion.isPaper()) {
            ADAPTER = new ModernVersionAdapter();
            Bukkit.getLogger().info("[AegisAC-Compat] 运行环境判定: Paper 1.20+ 架构 -> 已激活 [现代原生适配器]");
        } else {
            ADAPTER = new LegacyVersionAdapter();
            Bukkit.getLogger().info(String.format("[AegisAC-Compat] 运行环境判定: %s (编码: %d) -> 已激活 [跨版本兼容与降级适配器]",
                    ServerVersion.get().getFriendlyName(), ServerVersion.getCode()));
        }
    }

    public static VersionAdapter getAdapter() {
        return ADAPTER;
    }
}
