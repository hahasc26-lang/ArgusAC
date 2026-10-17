package com.example.aegisac.compat;

import org.bukkit.Bukkit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务器核心版本识别与比较工具 (集中化管理，严禁在检测模块内散落解析)
 * 采用 5 位整数编码规则：major * 10000 + minor * 100 + patch
 * 例：1.12.2 -> 11202, 1.16.5 -> 11605, 1.20.4 -> 12004, 1.21.1 -> 12101
 */
public enum ServerVersion {
    UNKNOWN(0, "Unknown"),
    V1_12_2(11202, "1.12.2"),
    V1_13(11300, "1.13"),
    V1_13_2(11302, "1.13.2"),
    V1_14_4(11404, "1.14.4"),
    V1_15_2(11502, "1.15.2"),
    V1_16_5(11605, "1.16.5"),
    V1_17_1(11701, "1.17.1"),
    V1_18_2(11802, "1.18.2"),
    V1_19_4(11904, "1.19.4"),
    V1_20_4(12004, "1.20.4"),
    V1_21(12100, "1.21");

    private final int versionCode;
    private final String friendlyName;

    private static final ServerVersion CURRENT_VERSION;
    private static final int CURRENT_CODE;
    private static final boolean IS_PAPER;

    static {
        boolean paperCheck = false;
        try {
            Class.forName("com.destroystokyo.paper.PaperConfig");
            paperCheck = true;
        } catch (ClassNotFoundException ignored) {
            try {
                Class.forName("io.papermc.paper.configuration.Configuration");
                paperCheck = true;
            } catch (ClassNotFoundException ignored2) {
                paperCheck = false;
            }
        }
        IS_PAPER = paperCheck;

        int code = parseServerVersionCode();
        CURRENT_CODE = code;
        CURRENT_VERSION = matchVersionFromCode(code);
    }

    ServerVersion(int versionCode, String friendlyName) {
        this.versionCode = versionCode;
        this.friendlyName = friendlyName;
    }

    public int getVersionCode() {
        return versionCode;
    }

    public String getFriendlyName() {
        return friendlyName;
    }

    public static ServerVersion get() {
        return CURRENT_VERSION;
    }

    public static int getCode() {
        return CURRENT_CODE;
    }

    public static boolean isPaper() {
        return IS_PAPER;
    }

    public static boolean isAtLeast(ServerVersion target) {
        return CURRENT_CODE >= target.versionCode;
    }

    public static boolean isOlderThan(ServerVersion target) {
        return CURRENT_CODE < target.versionCode;
    }

    public static boolean isBetween(ServerVersion min, ServerVersion max) {
        return CURRENT_CODE >= min.versionCode && CURRENT_CODE <= max.versionCode;
    }

    private static int parseServerVersionCode() {
        try {
            String versionStr = Bukkit.getVersion();
            Pattern pattern = Pattern.compile("(?i)\\(MC:\\s*([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?\\)");
            Matcher matcher = pattern.matcher(versionStr);

            if (matcher.find()) {
                int major = Integer.parseInt(matcher.group(1));
                int minor = Integer.parseInt(matcher.group(2));
                int patch = (matcher.group(3) != null) ? Integer.parseInt(matcher.group(3)) : 0;
                return major * 10000 + minor * 100 + patch;
            }

            String bukkitVer = Bukkit.getBukkitVersion();
            Pattern bPattern = Pattern.compile("^([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?");
            Matcher bMatcher = bPattern.matcher(bukkitVer);
            if (bMatcher.find()) {
                int major = Integer.parseInt(bMatcher.group(1));
                int minor = Integer.parseInt(bMatcher.group(2));
                int patch = (bMatcher.group(3) != null) ? Integer.parseInt(bMatcher.group(3)) : 0;
                return major * 10000 + minor * 100 + patch;
            }
        } catch (Exception ignored) {}

        return 12004;
    }

    private static ServerVersion matchVersionFromCode(int code) {
        ServerVersion best = UNKNOWN;
        for (ServerVersion v : values()) {
            if (v == UNKNOWN) continue;
            if (code >= v.versionCode) {
                best = v;
            }
        }
        return best;
    }
}
