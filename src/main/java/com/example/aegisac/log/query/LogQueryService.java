package com.example.aegisac.log.query;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.log.buffer.PlayerLogRingBuffer;
import com.example.aegisac.log.core.AuditLogManager;
import com.example.aegisac.log.core.HashChainEngine;
import com.example.aegisac.log.model.LogEntry;
import com.example.aegisac.log.model.LogType;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 审计查询、流式过滤、聚合榜单与哈希链核验服务
 */
public class LogQueryService {

    private final AegisACPlugin plugin;
    private final AuditLogManager logManager;

    public LogQueryService(AegisACPlugin plugin, AuditLogManager logManager) {
        this.plugin = plugin;
        this.logManager = logManager;
    }

    public List<LogEntry> queryMemoryLogs(UUID uuid, String checkFilter, long sinceMs, boolean setbackOnly) {
        PlayerLogRingBuffer buf = logManager.getMemoryBuffer(uuid);
        if (buf == null)
            return Collections.emptyList();

        List<LogEntry> raw = buf.snapshot();
        long now = System.currentTimeMillis();
        return raw.stream()
                .filter(e -> sinceMs <= 0 || (now - e.getTime()) <= sinceMs)
                .filter(e -> checkFilter == null
                        || (e.getCheck() != null && e.getCheck().equalsIgnoreCase(checkFilter)))
                .filter(e -> !setbackOnly || (e.getFrom() != null && e.getTo() != null))
                .toList();
    }

    public void streamHistoricalLogs(UUID targetUuid, int days, Predicate<LogEntry> filter,
            Consumer<List<LogEntry>> onComplete) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<LogEntry> result = new ArrayList<>();
            File dir = logManager.getBaseLogDir();
            File[] files = dir.listFiles(
                    (d, name) -> name.startsWith("audit-") && (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")));
            if (files == null) {
                onComplete.accept(result);
                return;
            }

            Arrays.sort(files, (f1, f2) -> f2.getName().compareTo(f1.getName()));
            int scannedDays = 0;

            for (File file : files) {
                if (scannedDays >= days)
                    break;
                scannedDays++;

                try (BufferedReader reader = openLogReader(file)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank())
                            continue;
                        try {
                            LogEntry entry = LogEntry.fromJson(line);
                            if (targetUuid == null || targetUuid.equals(entry.getUuid())) {
                                if (filter == null || filter.test(entry)) {
                                    result.add(entry);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("读取历史文件失败: " + file.getName());
                }
            }

            onComplete.accept(result);
        });
    }

    public void queryTopViolators(long sinceMs, Consumer<List<TopRecord>> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, TopRecordBuilder> stats = new HashMap<>();
            long now = System.currentTimeMillis();

            File dir = logManager.getBaseLogDir();
            File[] files = dir.listFiles(
                    (d, name) -> name.startsWith("audit-") && (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")));
            if (files != null) {
                for (File file : files) {
                    try (BufferedReader reader = openLogReader(file)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank())
                                continue;
                            try {
                                LogEntry e = LogEntry.fromJson(line);
                                if (e.getType() != LogType.VIOLATION)
                                    continue;
                                if (sinceMs > 0 && (now - e.getTime()) > sinceMs)
                                    continue;
                                if (e.getPlayer() == null)
                                    continue;

                                TopRecordBuilder b = stats.computeIfAbsent(e.getPlayer(), TopRecordBuilder::new);
                                b.recordViolation(e.getCheck(), e.getVl() != null ? e.getVl() : 1, e.getTo() != null);
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }

            List<TopRecord> sorted = stats.values().stream()
                    .map(b -> b.build())
                    .sorted((r1, r2) -> Integer.compare(r2.totalVl(), r1.totalVl()))
                    .limit(10)
                    .toList();

            callback.accept(sorted);
        });
    }

    public void verifyHashChainAsync(Consumer<VerificationResult> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File dir = logManager.getBaseLogDir();
            File[] files = dir.listFiles((d, name) -> name.startsWith("audit-") && name.endsWith(".jsonl"));
            if (files == null || files.length == 0) {
                callback.accept(new VerificationResult(true, 0, null, null));
                return;
            }

            Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
            String initialSeed = plugin.getConfig().getString("audit.hash-chain.seed", "AegisAC-Secure-Seed-2026");
            String previousHash = initialSeed;
            long currentLine = 0;

            for (File file : files) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank())
                            continue;
                        currentLine++;

                        LogEntry entry = LogEntry.fromJson(line);
                        String recordedHash = entry.getHash();
                        String signablePayload = entry.buildSignablePayload();
                        String expectedHash = HashChainEngine.computeVerificationHash(previousHash, signablePayload);

                        if (recordedHash == null || !recordedHash.equalsIgnoreCase(expectedHash)) {
                            VerificationResult fail = new VerificationResult(false, currentLine, file.getName(),
                                    recordedHash);
                            callback.accept(fail);
                            return;
                        }
                        previousHash = recordedHash;
                    }
                } catch (Exception e) {
                    callback.accept(
                            new VerificationResult(false, currentLine, file.getName(), "IO_ERROR: " + e.getMessage()));
                    return;
                }
            }

            callback.accept(new VerificationResult(true, currentLine, null, null));
        });
    }

    public void exportPlayerData(String targetName, UUID targetUuid, Consumer<File> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            File exportDir = new File(plugin.getDataFolder(), "exports");
            if (!exportDir.exists())
                exportDir.mkdirs();

            String timeStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(java.time.LocalDateTime.now());
            File zipFile = new File(exportDir, "export_" + targetName + "_" + timeStamp + ".zip");

            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                zos.putNextEntry(new ZipEntry("player_audit_" + targetName + ".jsonl"));

                File dir = logManager.getBaseLogDir();
                File[] files = dir.listFiles((d, name) -> name.startsWith("audit-")
                        && (name.endsWith(".jsonl") || name.endsWith(".jsonl.gz")));
                if (files != null) {
                    for (File file : files) {
                        try (BufferedReader reader = openLogReader(file)) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank())
                                    continue;
                                if (line.contains(targetUuid.toString())
                                        || line.contains("\"player\":\"" + targetName + "\"")) {
                                    zos.write(line.getBytes(StandardCharsets.UTF_8));
                                    zos.write("\n".getBytes(StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                }
                zos.closeEntry();
                callback.accept(zipFile);
            } catch (IOException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "导出玩家数据失败: " + e.getMessage(), e);
                callback.accept(null);
            }
        });
    }

    private BufferedReader openLogReader(File file) throws IOException {
        if (file.getName().endsWith(".gz")) {
            return new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(new FileInputStream(file)), StandardCharsets.UTF_8));
        } else {
            return new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        }
    }

    public record VerificationResult(boolean valid, long line, String filename, String detail) {
    }

    public record TopRecord(String player, String mainCheck, int totalVl, int setbackCount) {
    }

    private static class TopRecordBuilder {
        private final String player;
        private int totalVl = 0;
        private int setbackCount = 0;
        private final Map<String, Integer> checkCounts = new HashMap<>();

        public TopRecordBuilder(String player) {
            this.player = player;
        }

        public void recordViolation(String check, int vl, boolean setback) {
            this.totalVl += vl;
            if (setback)
                this.setbackCount++;
            if (check != null)
                checkCounts.merge(check, 1, (oldV, newV) -> oldV + newV);
        }

        public TopRecord build() {
            String mainCheck = checkCounts.entrySet().stream()
                    .max((e1, e2) -> Integer.compare(e1.getValue(), e2.getValue()))
                    .map(e -> e.getKey())
                    .orElse("Unknown");
            return new TopRecord(player, mainCheck, totalVl, setbackCount);
        }
    }
}
