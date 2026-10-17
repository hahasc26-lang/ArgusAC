package com.example.aegisac.util;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.Collection;

/**
 * 高性能数学计算工具类
 * 提供距离、角度、方差等计算，规避不必要的开方运算以提升性能
 */
public final class MathUtil {

    private MathUtil() {}

    /**
     * 计算水平方向 (X, Z) 的欧氏距离
     */
    public static double getHorizontalDistance(Location from, Location to) {
        if (from == null || to == null || from.getWorld() != to.getWorld()) {
            return 0.0;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 计算水平方向平方距离，避免 Math.sqrt 降低 CPU 开销
     */
    public static double getHorizontalDistanceSquared(Location from, Location to) {
        if (from == null || to == null || from.getWorld() != to.getWorld()) {
            return 0.0;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * 计算三维欧氏距离平方
     */
    public static double getDistance3DSquared(Location from, Location to) {
        if (from == null || to == null || from.getWorld() != to.getWorld()) {
            return 0.0;
        }
        return from.distanceSquared(to);
    }

    /**
     * 计算玩家视线向量与目标朝向向量之间的夹角（度数，零对象分配高频优化）
     */
    public static double getAngleBetweenVectors(Vector eyeDir, Vector targetDir) {
        double lenSq1 = eyeDir.lengthSquared();
        double lenSq2 = targetDir.lengthSquared();
        if (lenSq1 == 0.0 || lenSq2 == 0.0) {
            return 0.0;
        }
        double dot = eyeDir.dot(targetDir) / (Math.sqrt(lenSq1) * Math.sqrt(lenSq2));
        // 限制在 [-1.0, 1.0] 防止精度误差产生 NaN
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /**
     * 计算数值集合的样本标准差（用于 AutoClicker 检测连点器机械一致性）
     */
    public static double getStandardDeviation(Collection<? extends Number> values) {
        if (values == null || values.size() <= 1) {
            return 999.0;
        }
        double sum = 0.0;
        for (Number val : values) {
            sum += val.doubleValue();
        }
        double mean = sum / values.size();
        double varianceSum = 0.0;
        for (Number val : values) {
            double diff = val.doubleValue() - mean;
            varianceSum += diff * diff;
        }
        return Math.sqrt(varianceSum / (values.size() - 1));
    }
}
