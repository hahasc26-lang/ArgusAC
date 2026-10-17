package com.example.aegisac.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
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
     * 边界可用性检测：检测该合法位置在当前世界中脚部与头部是否可通行
     * 仅用于应对“回退点被其他玩家放置方块/活塞外力填成实心”的极罕见外部改变情况
     *
     * @return 若未被外部实心方块填充窒息则返回 true
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
        Block feet = loc.clone().add(0, 0.1, 0).getBlock();
        Block head = loc.clone().add(0, 1.6, 0).getBlock();
        return !feet.getType().isOccluding() && !head.getType().isOccluding();
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
