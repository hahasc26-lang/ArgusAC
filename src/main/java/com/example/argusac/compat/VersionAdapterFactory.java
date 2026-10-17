package com.example.argusac.compat;

import com.example.argusac.compat.impl.LegacyVersionAdapter;
import com.example.argusac.compat.impl.ModernVersionAdapter;
import org.bukkit.Bukkit;

/**
 * 适配器工厂：根据服务器版本与环境动态分发最匹配的实现
 */
public final class VersionAdapterFactory {

    private static final VersionAdapter ADAPTER;

    static {
        if (ServerVersion.isAtLeast(ServerVersion.V1_20_4) && ServerVersion.isPaper()) {
            ADAPTER = new ModernVersionAdapter();
            Bukkit.getLogger().info("[ArgusAC-Compat] Environment detected: Paper 1.20+ -> Activated [Modern Native Adapter]");
        } else {
            ADAPTER = new LegacyVersionAdapter();
            Bukkit.getLogger().info(String.format("[ArgusAC-Compat] Environment detected: %s (Code: %d) -> Activated [Cross-Version Fallback Adapter]",
                    ServerVersion.get().getFriendlyName(), ServerVersion.getCode()));
        }
    }

    public static VersionAdapter getAdapter() {
        return ADAPTER;
    }
}
