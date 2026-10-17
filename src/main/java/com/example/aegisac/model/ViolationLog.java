package com.example.aegisac.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * 违规与回退审计记录模型 (ViolationLog)
 * 记录违规者、触发检查项、违规等级、回退模式、前后坐标与是否启用兜底位置
 */
public class ViolationLog {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private final String playerName;
    private final UUID playerUuid;
    private final String checkName;
    private final int vl;
    private final String details;
    private final boolean setbackExecuted;
    private final String setbackMode;
    private final String fromLocation;
    private final String toLocation;
    private final boolean usedFallback;
    private final long timestamp;

    public ViolationLog(String playerName, UUID playerUuid, String checkName, int vl, String details,
                        boolean setbackExecuted, String setbackMode,
                        String fromLocation, String toLocation, boolean usedFallback) {
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.checkName = checkName;
        this.vl = vl;
        this.details = details;
        this.setbackExecuted = setbackExecuted;
        this.setbackMode = setbackMode != null ? setbackMode : "NONE";
        this.fromLocation = fromLocation != null ? fromLocation : "-";
        this.toLocation = toLocation != null ? toLocation : "-";
        this.usedFallback = usedFallback;
        this.timestamp = System.currentTimeMillis();
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getCheckName() {
        return checkName;
    }

    public int getVl() {
        return vl;
    }

    public String getDetails() {
        return details;
    }

    public boolean isSetbackExecuted() {
        return setbackExecuted;
    }

    public String getSetbackMode() {
        return setbackMode;
    }

    public String getFromLocation() {
        return fromLocation;
    }

    public String getToLocation() {
        return toLocation;
    }

    public boolean isUsedFallback() {
        return usedFallback;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTime() {
        return DATE_FORMAT.format(new Date(timestamp));
    }

    /**
     * 生成供落盘使用的标准 JSON 字符串
     */
    public String toJson() {
        return String.format(
                "{\"type\":\"VIOLATION\",\"time\":%d,\"player\":\"%s\",\"uuid\":\"%s\",\"check\":\"%s\",\"vl\":%d,\"details\":\"%s\",\"setback\":%b,\"mode\":\"%s\",\"from\":\"%s\",\"to\":\"%s\",\"usedFallback\":%b}",
                timestamp, playerName, playerUuid != null ? playerUuid.toString() : "",
                checkName, vl, details.replace("\"", "\\\""),
                setbackExecuted, setbackMode, fromLocation, toLocation, usedFallback
        );
    }

    @Override
    public String toString() {
        String setbackInfo = "";
        if (setbackExecuted) {
            String fallbackTag = usedFallback ? " (已启用前一位置兜底)" : "";
            setbackInfo = String.format(" [回退(%s): %s -> %s%s]", setbackMode, fromLocation, toLocation, fallbackTag);
        }
        return String.format("[%s] %s 触发 %s (VL: %d) - %s%s",
                getFormattedTime(), playerName, checkName, vl, details, setbackInfo);
    }
}
