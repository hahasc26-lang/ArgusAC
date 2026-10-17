package com.example.aegisac.log.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 轻量 SHA-256 链式防篡改哈希计算器
 */
public class HashChainEngine {

    private final String initialSeed;
    private String lastHash;

    public HashChainEngine(String initialSeed) {
        this.initialSeed = initialSeed != null && !initialSeed.isEmpty() ? initialSeed : "AegisAC-Default-Seed";
        this.lastHash = null;
    }

    public synchronized void reset(String lastHash) {
        this.lastHash = lastHash;
    }

    public synchronized String computeNextHash(String signablePayload) {
        String prev = (this.lastHash == null) ? this.initialSeed : this.lastHash;
        String raw = prev + ":" + signablePayload;
        String newHash = sha256(raw);
        this.lastHash = newHash;
        return newHash;
    }

    public synchronized String getLastHash() {
        return lastHash != null ? lastHash : initialSeed;
    }

    public static String computeVerificationHash(String prevHashOrSeed, String signablePayload) {
        return sha256(prevHashOrSeed + ":" + signablePayload);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不支持", e);
        }
    }
}
