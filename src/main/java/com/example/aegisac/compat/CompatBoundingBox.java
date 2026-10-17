package com.example.aegisac.compat;

import org.bukkit.Location;

/**
 * 跨版本统一实体与方块碰撞箱结构体
 */
public record CompatBoundingBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public boolean intersects(CompatBoundingBox other) {
        return this.minX <= other.maxX && this.maxX >= other.minX &&
               this.minY <= other.maxY && this.maxY >= other.minY &&
               this.minZ <= other.maxZ && this.maxZ >= other.minZ;
    }

    public CompatBoundingBox expand(double amount) {
        return new CompatBoundingBox(
                minX - amount, minY - amount, minZ - amount,
                maxX + amount, maxY + amount, maxZ + amount
        );
    }

    public double distanceTo(Location loc) {
        double clampedX = Math.max(minX, Math.min(loc.getX(), maxX));
        double clampedY = Math.max(minY, Math.min(loc.getY(), maxY));
        double clampedZ = Math.max(minZ, Math.min(loc.getZ(), maxZ));
        double dx = loc.getX() - clampedX;
        double dy = loc.getY() - clampedY;
        double dz = loc.getZ() - clampedZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double getCenterX() { return (minX + maxX) / 2.0; }
    public double getCenterY() { return (minY + maxY) / 2.0; }
    public double getCenterZ() { return (minZ + maxZ) / 2.0; }

    public double getWidth() { return maxX - minX; }
    public double getHeight() { return maxY - minY; }
}
