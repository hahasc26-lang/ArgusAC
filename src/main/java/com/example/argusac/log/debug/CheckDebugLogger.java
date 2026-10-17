package com.example.argusac.log.debug;

import com.example.argusac.ArgusACPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 临时检测模块调试日志记录器 (不进入哈希链，独立写入 logs/debug/ 目录)
 */
public class CheckDebugLogger {

    private final File debugDir;
    private final Set<String> activeDebugChecks = ConcurrentHashMap.newKeySet();
    private final ExecutorService debugExecutor;

    public CheckDebugLogger(ArgusACPlugin plugin) {
        this.debugDir = new File(plugin.getDataFolder(), "logs/debug");
        if (!debugDir.exists()) debugDir.mkdirs();
        this.debugExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ArgusAC-DebugLogWriter");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean toggle(String checkName) {
        String normalized = checkName.toLowerCase();
        if (activeDebugChecks.contains(normalized)) {
            activeDebugChecks.remove(normalized);
            return false;
        } else {
            activeDebugChecks.add(normalized);
            return true;
        }
    }

    public boolean isDebug(String checkName) {
        return checkName != null && activeDebugChecks.contains(checkName.toLowerCase());
    }

    public void log(String checkName, String playerName, String message) {
        if (!isDebug(checkName)) return;

        debugExecutor.submit(() -> {
            File checkFile = new File(debugDir, checkName.toLowerCase() + ".log");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(checkFile, true))) {
                String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
                writer.write(String.format("[%s] [%s] %s%n", now, playerName, message));
            } catch (IOException ignored) {}
        });
    }

    public void shutdown() {
        debugExecutor.shutdown();
    }
}
