package com.example.argusac.util;

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
     * 高性能 3D 轴对齐包围盒 (AABB) 射线相交测试 (Slab Algorithm)
     * 时间复杂度 O(1)，零对象分配。
     *
     * @param origin 射线起点 (玩家眼睛坐标)
     * @param dir 射线方向单位向量
     * @param box 目标碰撞箱
     * @param maxDist 最大射线检测距离
     * @param margin 容差外扩裕度 (如 0.05 格，抵抗浮点舍入)
     * @return 射线是否在 maxDist 范围内穿透了目标包围盒
     */
    public static boolean rayIntersectsAABB(Vector origin, Vector dir, com.example.argusac.compat.CompatBoundingBox box,
                                            double maxDist, double margin) {
        if (origin == null || dir == null || box == null) {
            return false;
        }

        double minX = box.minX() - margin;
        double minY = box.minY() - margin;
        double minZ = box.minZ() - margin;
        double maxX = box.maxX() + margin;
        double maxY = box.maxY() + margin;
        double maxZ = box.maxZ() + margin;

        double ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        double dx = dir.getX(), dy = dir.getY(), dz = dir.getZ();

        double tmin = 0.0;
        double tmax = maxDist;

        // X slab
        if (Math.abs(dx) < 1.0E-8) {
            if (ox < minX || ox > maxX) return false;
        } else {
            double invD = 1.0 / dx;
            double t1 = (minX - ox) * invD;
            double t2 = (maxX - ox) * invD;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return false;
        }

        // Y slab
        if (Math.abs(dy) < 1.0E-8) {
            if (oy < minY || oy > maxY) return false;
        } else {
            double invD = 1.0 / dy;
            double t1 = (minY - oy) * invD;
            double t2 = (maxY - oy) * invD;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return false;
        }

        // Z slab
        if (Math.abs(dz) < 1.0E-8) {
            if (oz < minZ || oz > maxZ) return false;
        } else {
            double invD = 1.0 / dz;
            double t1 = (minZ - oz) * invD;
            double t2 = (maxZ - oz) * invD;
            if (t1 > t2) { double tmp = t1; t1 = t2; t2 = tmp; }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) return false;
        }

        return tmax >= 0.0 && tmin <= maxDist;
    }

    /**
     * 计算玩家视线与目标 AABB 碰撞盒之间的最小视线夹角 (Min Angle to Box)
     * 若视线射线直接命中目标身体或外缘，夹角恒为 0.0 度；
     * 若未直接命中，综合遍历盒关键特征点 (中心、眼睛、胸口、腹部、脚底、8顶点、视线最近表面点) 计算最小偏离角。
     */
    public static double getMinAngleToBox(Location eyeLoc, com.example.argusac.compat.CompatBoundingBox box, double victimEyeHeight) {
        if (eyeLoc == null || box == null) {
            return 0.0;
        }

        Vector origin = eyeLoc.toVector();
        Vector dir = eyeLoc.getDirection();

        // 1. 若射线直接与目标碰撞盒相交 (给予 0.05 格微小碰撞宽容)，视线正中目标，夹角为 0.0°
        if (rayIntersectsAABB(origin, dir, box, 6.0, 0.05)) {
            return 0.0;
        }

        double minAngle = 180.0;

        // 2. 盒特征点采样
        double cx = box.getCenterX();
        double cy = box.getCenterY();
        double cz = box.getCenterZ();
        double boxHeight = box.getHeight();

        // 视线在目标距离处的最近表面投影点 (Clamped Closest Point to Ray)
        double eyeDist = Math.max(0.5, eyeLoc.distance(new Location(eyeLoc.getWorld(), cx, cy, cz)));
        double rayPointX = origin.getX() + dir.getX() * eyeDist;
        double rayPointY = origin.getY() + dir.getY() * eyeDist;
        double rayPointZ = origin.getZ() + dir.getZ() * eyeDist;
        double clampedX = Math.max(box.minX(), Math.min(rayPointX, box.maxX()));
        double clampedY = Math.max(box.minY(), Math.min(rayPointY, box.maxY()));
        double clampedZ = Math.max(box.minZ(), Math.min(rayPointZ, box.maxZ()));

        double[][] samplePoints = new double[][]{
                {clampedX, clampedY, clampedZ},                                       // 视线投影最近表面点
                {cx, cy, cz},                                                         // 盒几何中心
                {cx, box.minY() + Math.min(victimEyeHeight, boxHeight * 0.90), cz},   // 眼睛高度点
                {cx, box.minY() + boxHeight * 0.60, cz},                              // 胸口点
                {cx, box.minY() + boxHeight * 0.35, cz},                              // 腹部点
                {cx, box.minY() + 0.10, cz},                                          // 脚底点
                {box.minX(), box.minY(), box.minZ()},                                 // 8 角顶点
                {box.minX(), box.minY(), box.maxZ()},
                {box.minX(), box.maxY(), box.minZ()},
                {box.minX(), box.maxY(), box.maxZ()},
                {box.maxX(), box.minY(), box.minZ()},
                {box.maxX(), box.minY(), box.maxZ()},
                {box.maxX(), box.maxY(), box.minZ()},
                {box.maxX(), box.maxY(), box.maxZ()}
        };

        for (double[] pt : samplePoints) {
            Vector toPt = new Vector(pt[0] - origin.getX(), pt[1] - origin.getY(), pt[2] - origin.getZ());
            double angle = getAngleBetweenVectors(dir, toPt);
            if (angle < minAngle) {
                minAngle = angle;
                if (minAngle < 0.5) {
                    return 0.0;
                }
            }
        }

        return minAngle;
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
