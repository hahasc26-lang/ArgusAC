package com.example.aegisac.log.buffer;

import com.example.aegisac.log.model.LogEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 玩家独立定容环形缓冲 (默认 30 条)
 * 采用读写锁：满时自动覆盖最老元素，读取快照时不阻塞追加写入
 */
public class PlayerLogRingBuffer {

    private final int capacity;
    private final LogEntry[] buffer;
    private int head = 0;
    private int size = 0;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    public PlayerLogRingBuffer(int capacity) {
        this.capacity = Math.max(5, capacity);
        this.buffer = new LogEntry[this.capacity];
    }

    public void push(LogEntry entry) {
        rwLock.writeLock().lock();
        try {
            int writeIndex = (head + size) % capacity;
            if (size == capacity) {
                buffer[writeIndex] = entry;
                head = (head + 1) % capacity;
            } else {
                buffer[writeIndex] = entry;
                size++;
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public List<LogEntry> snapshot() {
        rwLock.readLock().lock();
        try {
            if (size == 0) return Collections.emptyList();
            List<LogEntry> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int idx = (head + i) % capacity;
                list.add(buffer[idx]);
            }
            return list;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int size() {
        rwLock.readLock().lock();
        try {
            return size;
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
