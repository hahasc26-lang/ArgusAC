package com.example.argusac.log.core;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.log.buffer.PlayerLogRingBuffer;
import com.example.argusac.log.model.LogEntry;
import com.example.argusac.log.model.LogType;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 审计日志写入总枢纽：
 * 1. 主线程零 I/O：仅入队引用
 * 2. 背压机制：满队列无异常丢弃并计数
 * 3. 单线程串行长驻 Writer
 * 4. 自动定期 Flush 与定期写 INTEGRITY 检查点
 */
public class AuditLogManager {

    private final ArgusACPlugin plugin;
    private final File baseLogDir;
    private final String serverId;
    private final boolean hashChainEnabled;

    // 内存环形缓冲集合 (玩家独立)
    private final Map<UUID, PlayerLogRingBuffer> memoryBuffers = new ConcurrentHashMap<>();
    private final int memoryBufferCapacity;

    // 异步背压队列 (默认 5000 条硬上限)
    private final BlockingQueue<LogEntry> diskQueue;
    private final int queueCapacity;
    private final AtomicLong droppedLogsCount = new AtomicLong(0);

    // 单线程异步执行器与状态
    private final ExecutorService diskExecutor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // 长驻 Writer 状态 (仅在单线程内操作)
    private BufferedWriter currentWriter;
    private File currentLogFile;
    private String currentDateStr;
    private final Object writerLock = new Object();

    // 防篡改哈希链
    private final HashChainEngine hashChainEngine;
    private final AtomicLong writtenLinesCount = new AtomicLong(0);
    private long lastFlushTime = System.currentTimeMillis();
    private int uncommittedEntries = 0;
    private long lastIntegrityCheckpointTime = System.currentTimeMillis();
    private final int integrityIntervalEntries;
    private BukkitTask flushTimerTask;

    public AuditLogManager(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.baseLogDir = new File(plugin.getDataFolder(), "logs/audit");
        if (!baseLogDir.exists()) baseLogDir.mkdirs();

        this.serverId = plugin.getConfig().getString("audit.server-id", "survival-01");
        this.hashChainEnabled = plugin.getConfig().getBoolean("audit.hash-chain.enabled", true);
        String seed = plugin.getConfig().getString("audit.hash-chain.seed", "ArgusAC-Secure-Seed-2026");
        this.hashChainEngine = new HashChainEngine(seed);
        this.integrityIntervalEntries = plugin.getConfig().getInt("audit.hash-chain.checkpoint-interval-lines", 100);

        this.memoryBufferCapacity = plugin.getConfig().getInt("audit.memory-buffer-size", 30);
        this.queueCapacity = plugin.getConfig().getInt("audit.queue-capacity", 5000);
        this.diskQueue = new ArrayBlockingQueue<>(queueCapacity);

        // 守护进程单线程池
        this.diskExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ArgusAC-AuditWriter");
            t.setDaemon(true);
            return t;
        });

        // 启动后台写入轮询任务
        this.diskExecutor.submit(this::processDiskQueueLoop);

        // 定期 Flush 调度 (每 5 秒 = 100 ticks)
        this.flushTimerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!running.get()) return;
            flushIfNeeded(false);
            checkAndAlertBackpressure();
        }, 100L, 100L);
    }

    /**
     * 主线程零 I/O 提交日志入口：
     * 1. 放入玩家内存环形缓冲
     * 2. offer 进异步队列；满时直接丢弃计数，无阻塞、无异常
     */
    public void submitLog(LogEntry entry) {
        if (entry == null) return;

        // 1. 写入玩家内存缓冲 (仅限关联玩家的事件)
        if (entry.getUuid() != null) {
            PlayerLogRingBuffer buf = memoryBuffers.computeIfAbsent(
                    entry.getUuid(),
                    k -> new PlayerLogRingBuffer(memoryBufferCapacity)
            );
            buf.push(entry);
        }

        // 2. 提交到异步队列进行背压落盘
        if (!diskQueue.offer(entry)) {
            droppedLogsCount.incrementAndGet();
        }
    }

    private void processDiskQueueLoop() {
        while (running.get() || !diskQueue.isEmpty()) {
            try {
                LogEntry entry = diskQueue.poll(500, TimeUnit.MILLISECONDS);
                if (entry != null) {
                    writeEntryDirectly(entry);
                    uncommittedEntries++;
                    if (uncommittedEntries >= 100) {
                        flushInternal();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "[AuditLog] 写入日志异常", ex);
            }
        }
        flushInternal();
        closeWriterInternal();
    }

    private void writeEntryDirectly(LogEntry entry) throws IOException {
        ensureWriterReady();

        if (hashChainEnabled) {
            String signablePayload = entry.buildSignablePayload();
            String hash = hashChainEngine.computeNextHash(signablePayload);
            entry.setHash(hash);
        }

        String jsonLine = entry.toJson();
        synchronized (writerLock) {
            if (currentWriter != null) {
                currentWriter.write(jsonLine);
                currentWriter.newLine();
                writtenLinesCount.incrementAndGet();
            }
        }

        maybeTriggerIntegrityLog();
    }

    private void ensureWriterReady() throws IOException {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        synchronized (writerLock) {
            if (currentWriter == null || !today.equals(currentDateStr)) {
                rotateDayFileInternal(today);
            }
        }
    }

    private void rotateDayFileInternal(String newDateStr) throws IOException {
        closeWriterInternal();

        this.currentDateStr = newDateStr;
        this.currentLogFile = new File(baseLogDir, "audit-" + newDateStr + ".jsonl");
        boolean isNewFile = !currentLogFile.exists();

        FileOutputStream fos = new FileOutputStream(currentLogFile, true);
        this.currentWriter = new BufferedWriter(new OutputStreamWriter(fos, StandardCharsets.UTF_8), 32768);

        if (isNewFile) {
            applyFilePermissions(currentLogFile);
        }
    }

    private void applyFilePermissions(File file) {
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-r-----");
            Files.setPosixFilePermissions(file.toPath(), perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows 系统不支持 POSIX 文件权限，安全跳过
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "设置文件权限跳过: " + e.getMessage());
        }
    }

    private void maybeTriggerIntegrityLog() {
        long lines = writtenLinesCount.get();
        long now = System.currentTimeMillis();
        boolean byLines = (lines % integrityIntervalEntries == 0);
        boolean byTime = (now - lastIntegrityCheckpointTime >= 60_000L);

        if (hashChainEnabled && (byLines || byTime)) {
            lastIntegrityCheckpointTime = now;
            LogEntry integrity = new LogEntry.Builder(LogType.INTEGRITY, serverId)
                    .latestHash(hashChainEngine.getLastHash())
                    .lineCount(lines)
                    .build();
            diskQueue.offer(integrity);
        }
    }

    private void flushIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (force || uncommittedEntries > 0 || (now - lastFlushTime >= 5000L)) {
            diskExecutor.submit(this::flushInternal);
        }
    }

    private void flushInternal() {
        synchronized (writerLock) {
            if (currentWriter != null) {
                try {
                    currentWriter.flush();
                    lastFlushTime = System.currentTimeMillis();
                    uncommittedEntries = 0;
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "[AuditLog] Flush 失败: " + e.getMessage());
                }
            }
        }
    }

    private void closeWriterInternal() {
        synchronized (writerLock) {
            if (currentWriter != null) {
                try {
                    currentWriter.flush();
                    currentWriter.close();
                } catch (IOException ignored) {}
                currentWriter = null;
            }
        }
    }

    private void checkAndAlertBackpressure() {
        int size = diskQueue.size();
        if (size > (queueCapacity * 0.5)) {
            String msg = String.format("§c[ArgusAC 警告] 审计日志队列积压已达 %d/%d (%.1f%%)！请注意磁盘 I/O！",
                    size, queueCapacity, (size * 100.0 / queueCapacity));
            Bukkit.getOnlinePlayers().stream()
                    .filter(p -> p.hasPermission("argusac.alert") || p.hasPermission("ArgusAC.alert"))
                    .filter(p -> !plugin.getAlertManager().isMuted(p))
                    .forEach(p -> p.sendMessage(msg));
            plugin.getLogger().warning(msg);
        }
    }

    public void rotateNow() {
        diskExecutor.submit(() -> {
            try {
                String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
                rotateDayFileInternal(today);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "手动轮转日志失败", e);
            }
        });
    }

    public PlayerLogRingBuffer getMemoryBuffer(UUID uuid) {
        return memoryBuffers.get(uuid);
    }

    public void removeMemoryBuffer(UUID uuid) {
        memoryBuffers.remove(uuid);
    }

    public void shutdown() {
        running.set(false);
        if (flushTimerTask != null) {
            flushTimerTask.cancel();
        }
        diskExecutor.shutdown();
        try {
            if (!diskExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                diskExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            diskExecutor.shutdownNow();
        }
    }

    // Status Getters
    public long getDroppedCount() { return droppedLogsCount.get(); }
    public int getQueueBacklog() { return diskQueue.size(); }
    public int getQueueCapacity() { return queueCapacity; }
    public long getCurrentFileSize() { return currentLogFile != null && currentLogFile.exists() ? currentLogFile.length() : 0L; }
    public long getLastFlushTime() { return lastFlushTime; }
    public String getLatestHash() { return hashChainEngine.getLastHash(); }
    public File getBaseLogDir() { return baseLogDir; }
    public String getServerId() { return serverId; }
}
