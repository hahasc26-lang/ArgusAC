package com.example.argusac.setback;

import com.example.argusac.compat.CompatBoundingBox;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * 稳固地面与安全落脚点探测器 (SafeLocationFinder)
 * 采用真实的方块 AABB 碰撞箱 (CompatBoundingBox) 进行精细 Top-Y 贴合，
 * 支持台阶 (0.5)、楼梯、雪层与半方块，杜绝回退后的二次抖落下落。
 */
public class SafeLocationFinder {

    /**
     * 判断指定玩家与位置是否处于有效支持面或地面上
     */
    public boolean isGrounded(Player player, Location loc, PlayerData pData) {
        if (loc == null || loc.getWorld() == null) return false;
        if (pData != null && pData.isOnGround()) return true;
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        // 原版潜行防掉落 (Sneak Edge Prevention) 允许探出极限为 0.40~0.42 格；
        // 探测中心、正向 4 点及斜向 4 点，全面覆盖十字方向与拐角边缘
        double reach = (player != null && player.isSneaking()) ? 0.42 : 0.32;
        double diag = reach * 0.72;
        double[][] points = {
                {0, 0},
                {reach, 0}, {-reach, 0}, {0, reach}, {0, -reach},
                {diag, diag}, {-diag, diag}, {diag, -diag}, {-diag, -diag}
        };
        for (double[] p : points) {
            Location checkLoc = loc.clone().add(p[0], 0, p[1]);
            for (double yOffset = 0.0; yOffset >= -0.60; yOffset -= 0.15) {
                Block b = checkLoc.clone().add(0, yOffset, 0).getBlock();
                if (b.getType().isSolid() || adapter.isStandable(b) || adapter.isClimbable(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 在限定垂直距离内向下探测坚实地面 (支持半方块/台阶/雪层等精细碰撞箱 Top-Y 贴合，杜绝二次下落抖动)
     */
    public Location findSafeGroundNear(Location loc, double maxDistance) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 水平邻近探测偏移：若中心点探出悬崖边缘，自动吸附至身后或身侧稳固台面上 (Edge Snap)
        double[][] horizontalOffsets = {
                {0, 0},
                {0.35, 0}, {-0.35, 0}, {0, 0.35}, {0, -0.35},
                {0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25}
        };

        for (double[] offset : horizontalOffsets) {
            Location sampleLoc = loc.clone().add(offset[0], 0, offset[1]);
            int startY = sampleLoc.getBlockY();
            int lowestY = Math.max(world.getMinHeight(), (int) Math.floor(sampleLoc.getY() - maxDistance));

            for (int y = startY; y >= lowestY; y--) {
                Block block = world.getBlockAt(sampleLoc.getBlockX(), y, sampleLoc.getBlockZ());
                if (block.getType().isSolid() || adapter.isStandable(block)) {
                    CompatBoundingBox blockBox = adapter.getBlockBoundingBox(block);
                    double surfaceY = (blockBox != null && blockBox.maxY() >= y) ? blockBox.maxY() : (y + 1.0);
                    if (loc.getY() >= surfaceY - 0.15 && (loc.getY() - surfaceY) <= maxDistance) {
                        Location candidate = new Location(world, sampleLoc.getX(), surfaceY, sampleLoc.getZ(), loc.getYaw(), loc.getPitch());
                        CompatBoundingBox playerBox = new CompatBoundingBox(
                                candidate.getX() - 0.29, candidate.getY() + 0.01, candidate.getZ() - 0.29,
                                candidate.getX() + 0.29, candidate.getY() + 1.79, candidate.getZ() + 0.29
                        );
                        if (adapter.isSpacePassable(world, playerBox)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 向下探测坚实地面方块 (支持台阶、楼梯与半砖精细表面判定，搜索深度扩大至 64 格)
     */
    public Location findSafeGroundBelow(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        World world = loc.getWorld();
        int startY = loc.getBlockY();
        int minHeight = world.getMinHeight();
        int lowestY = Math.max(minHeight, startY - 64);
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        for (int y = startY; y >= lowestY; y--) {
            Block block = world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType().isSolid() || adapter.isStandable(block)) {
                CompatBoundingBox blockBox = adapter.getBlockBoundingBox(block);
                double surfaceY = (blockBox != null && blockBox.maxY() >= y) ? blockBox.maxY() : (y + 1.0);
                Location candidate = new Location(world, loc.getX(), surfaceY, loc.getZ(), loc.getYaw(), loc.getPitch());
                CompatBoundingBox playerBox = new CompatBoundingBox(
                        candidate.getX() - 0.29, candidate.getY() + 0.01, candidate.getZ() - 0.29,
                        candidate.getX() + 0.29, candidate.getY() + 1.79, candidate.getZ() + 0.29
                );
                if (adapter.isSpacePassable(world, playerBox)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    public static String formatLoc(Location loc) {
        if (loc == null) return "null";
        return String.format("%.2f,%.2f,%.2f", loc.getX(), loc.getY(), loc.getZ());
    }
}
