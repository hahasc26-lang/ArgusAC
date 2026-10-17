package com.example.argusac.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 玩家最后合法位置数据模型 (LegitimateLocation)
 * <p>
 * 【核心设计原则】：
 * 回退目标是玩家最后一个通过验证的位置，不是安全位置。
 * 安全位置是复活点或传送落点的概念，不属于反作弊回退。
 * <p>
 * 本类只记录玩家通过反作弊移动验证时的瞬时合法物理状态，
 * 采用不可变数据模型设计，严格避免长期持有 org.bukkit.World 强引用。
 */
public final class LegitimateLocation {

    private final double x;
    private final double y;
    private final double z;
    private final float yaw;
    private final float pitch;
    private final double velocityX;
    private final double velocityY;
    private final double velocityZ;
    private final boolean onGround;
    private final UUID worldUid;
    private final String worldName;
    private final long timestamp;
    private final boolean inVehicle;
    private final UUID vehicleUid;

    public LegitimateLocation(double x, double y, double z,
                              float yaw, float pitch,
                              double velocityX, double velocityY, double velocityZ,
                              boolean onGround,
                              UUID worldUid, String worldName,
                              long timestamp,
                              boolean inVehicle, UUID vehicleUid) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        this.velocityZ = velocityZ;
        this.onGround = onGround;
        this.worldUid = worldUid;
        this.worldName = worldName != null ? worldName : "";
        this.timestamp = timestamp;
        this.inVehicle = inVehicle;
        this.vehicleUid = vehicleUid;
    }

    /**
     * 快捷静态构造方法：从 Bukkit 实体状态创建合法位置
     */
    public static LegitimateLocation from(Location loc, Vector velocity, boolean onGround,
                                          boolean inVehicle, UUID vehicleUid) {
        if (loc == null || loc.getWorld() == null) {
            return null;
        }
        double vx = (velocity != null) ? velocity.getX() : 0.0;
        double vy = (velocity != null) ? velocity.getY() : 0.0;
        double vz = (velocity != null) ? velocity.getZ() : 0.0;

        return new LegitimateLocation(
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getYaw(), loc.getPitch(),
                vx, vy, vz,
                onGround,
                loc.getWorld().getUID(),
                loc.getWorld().getName(),
                System.currentTimeMillis(),
                inVehicle,
                vehicleUid
        );
    }

    /**
     * 还原为 Bukkit Location 实例
     */
    public Location toLocation() {
        World world = null;
        if (worldUid != null) {
            world = Bukkit.getWorld(worldUid);
        }
        if (world == null && !worldName.isEmpty()) {
            world = Bukkit.getWorld(worldName);
        }
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * 还原为运动速度向量
     */
    public Vector toVelocity() {
        return new Vector(velocityX, velocityY, velocityZ);
    }

    /**
     * 边界可用性检测：使用跨版本兼容的完整玩家碰撞箱 (0.6x1.8) 检查目标空间是否可通行
     * 替代粗糙的 isOccluding 判定，正确识别栅栏、铁栅栏、台阶、楼梯、门等有碰撞箱的非 occluding 方块
     *
     * @return 若未被外部实体方块阻挡/窒息则返回 true
     */
    public boolean isPassable() {
        Location loc = toLocation();
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        World world = loc.getWorld();
        int chunkX = loc.getBlockX() >> 4;
        int chunkZ = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }

        // 构建标准玩家物理碰撞体 (宽度 0.6，高度 1.8，内缩 0.01 避免贴地/贴墙浮点误差)
        com.example.argusac.compat.CompatBoundingBox playerBox = new com.example.argusac.compat.CompatBoundingBox(
                x - 0.29, y + 0.01, z - 0.29,
                x + 0.29, y + 1.79, z + 0.29
        );

        return com.example.argusac.compat.VersionAdapterFactory.getAdapter().isSpacePassable(world, playerBox);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public double getVelocityX() {
        return velocityX;
    }

    public double getVelocityY() {
        return velocityY;
    }

    public double getVelocityZ() {
        return velocityZ;
    }

    public boolean isOnGround() {
        return onGround;
    }

    public UUID getWorldUid() {
        return worldUid;
    }

    public String getWorldName() {
        return worldName;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isInVehicle() {
        return inVehicle;
    }

    public UUID getVehicleUid() {
        return vehicleUid;
    }

    @Override
    public String toString() {
        return String.format("[%s] %.2f, %.2f, %.2f (yaw:%.1f, pitch:%.1f)", worldName, x, y, z, yaw, pitch);
    }
}
