package com.example.argusac.log.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * 回退失败审计日志模型 (SetbackFailureLog)
 */
public class SetbackFailureLog {

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private final String playerName;
    private final UUID playerUuid;
    private final String checkName;
    private final String failureReason;
    private final String originalLocation;
    private final long timestamp;

    public SetbackFailureLog(String playerName, UUID playerUuid, String checkName,
                             String failureReason, String originalLocation) {
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.checkName = checkName;
        this.failureReason = failureReason;
        this.originalLocation = originalLocation;
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

    public String getFailureReason() {
        return failureReason;
    }

    public String getOriginalLocation() {
        return originalLocation;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTime() {
        return TIME_FORMAT.format(new Date(timestamp));
    }

    public String toJson() {
        return String.format(
                "{\"type\":\"SETBACK_FAILURE\",\"time\":%d,\"player\":\"%s\",\"uuid\":\"%s\",\"check\":\"%s\",\"reason\":\"%s\",\"origLoc\":\"%s\"}",
                timestamp, playerName, playerUuid.toString(), checkName, failureReason, originalLocation
        );
    }

    @Override
    public String toString() {
        return String.format("[%s] [FAIL] %s triggered %s setback failure - Reason: %s (Original: %s)",
                getFormattedTime(), playerName, checkName, failureReason, originalLocation);
    }
}
