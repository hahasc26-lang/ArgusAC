package com.example.argusac.log.maintenance;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.log.core.AuditLogManager;
import com.example.argusac.log.model.LogEntry;
import com.example.argusac.log.model.LogType;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.zip.GZIPOutputStream;

/**
 * 日志文件生命周期管控：
 * 1. 自动轮转
 * 2. 超过 7 天的文件分片压缩为 .jsonl.gz (附带 TPS 动态降级保护)
 * 3. 超过 retention-days (默认30天) 的文件自动物理清理并记录 ADMIN 日志
 */
public class LogArchiveService {

    private final ArgusACPlugin plugin;
    private final AuditLogManager logManager;
    private final int retentionDays;
    private final boolean keepAdminPermanent;
    private final double minTpsThreshold;
    private BukkitTask archiveTask;

    public LogArchiveService(ArgusACPlugin plugin, AuditLogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
        this.retentionDays = plugin.getConfig().getInt("audit.archive.retention-days", 30);
        this.keepAdminPermanent = plugin.getConfig().getBoolean("audit.archive.keep-admin-permanent", true);
        this.minTpsThreshold = plugin.getConfig().getDouble("audit.archive.min-tps-threshold", 18.5);

        scheduleDailyMaintenance();
    }

    private void scheduleDailyMaintenance() {
        // 每 1 小时检查一次 (72000 ticks)
        this.archiveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::performMaintenance, 200L, 72000L);
    }

    public synchronized void performMaintenance() {
        File dir = logManager.getBaseLogDir();
        if (!dir.exists() || !dir.isDirectory()) return;

        LocalDate now = LocalDate.now();
        File[] files = dir.listFiles((d, name) -> name.startsWith("audit-") && (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")));
        if (files == null) return;

        // 1. 检查压缩 (大于 7 天的 .jsonl 压缩为 .jsonl.gz)
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".jsonl")) {
                LocalDate fileDate = extractDateFromFilename(name);
                if (fileDate != null && ChronoUnit.DAYS.between(fileDate, now) >= 7) {
                    compressFileWithTpsThrottling(file);
                }
            }
        }

        // 2. 检查过期清理
        for (File file : files) {
            LocalDate fileDate = extractDateFromFilename(file.getName());
            if (fileDate != null && ChronoUnit.DAYS.between(fileDate, now) > retentionDays) {
                deleteExpiredFile(file);
            }
        }
    }

    private void compressFileWithTpsThrottling(File source) {
        File targetGz = new File(source.getParentFile(), source.getName() + ".gz");
        if (targetGz.exists()) return;

        byte[] buffer = new byte[8192];
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(targetGz);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {

            int len;
            int counter = 0;
            while ((len = fis.read(buffer)) > 0) {
                gzos.write(buffer, 0, len);
                counter++;
                // 每读写 64KB (8 次) 检查一次 TPS
                if (counter % 8 == 0) {
                    throttleIfTpsDrops();
                }
            }
            gzos.finish();

            source.delete();
            plugin.getLogger().info("[LogArchive] 成功归档压缩历史文件: " + targetGz.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("[LogArchive] 压缩文件失败 " + source.getName() + ": " + e.getMessage());
            if (targetGz.exists()) targetGz.delete();
        }
    }

    private void throttleIfTpsDrops() {
        try {
            double currentTps = Bukkit.getTPS()[0];
            while (currentTps < minTpsThreshold) {
                Thread.sleep(1000L);
                currentTps = Bukkit.getTPS()[0];
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void deleteExpiredFile(File file) {
        String filename = file.getName();
        if (keepAdminPermanent && !filename.contains("admin-permanent")) {
            extractAndPreserveAdminLogs(file);
        }
        boolean deleted = file.delete();
        if (deleted) {
            plugin.getLogger().info("[LogArchive] 已清理过期日志文件: " + filename);
            LogEntry adminLog = new LogEntry.Builder(LogType.ADMIN, logManager.getServerId())
                    .actor("SYSTEM")
                    .action("LOG_PURGE_EXPIRED")
                    .args("file=" + filename + ";retentionDays=" + retentionDays)
                    .result("SUCCESS")
                    .build();
            logManager.submitLog(adminLog);
        }
    }

    private void extractAndPreserveAdminLogs(File file) {
        File permanentFile = new File(logManager.getBaseLogDir(), "audit-admin-permanent.jsonl");
        try (java.io.BufferedReader reader = openReader(file);
             java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(permanentFile, true))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("\"type\":\"ADMIN\"")) {
                    writer.write(line);
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[LogArchive] 提取永久管理员日志失败: " + e.getMessage());
        }
    }

    private java.io.BufferedReader openReader(File file) throws IOException {
        if (file.getName().endsWith(".gz")) {
            return new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(new FileInputStream(file)), java.nio.charset.StandardCharsets.UTF_8));
        } else {
            return new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private LocalDate extractDateFromFilename(String name) {
        try {
            String datePart = name.substring(6, 16);
            return LocalDate.parse(datePart, DateTimeFormatter.ISO_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    public void cancel() {
        if (archiveTask != null) {
            archiveTask.cancel();
        }
    }
}
