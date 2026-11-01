package com.example.argusac.compat;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * 跨版本多态抽象接口：所有存在 NoSuchMethodError 风险的操作必须经过本接口
 */
public interface VersionAdapter {

    // 玩家状态
    int getPing(Player player);

    boolean isSwimming(Player player);

    boolean isGliding(Player player);

    boolean isRiptiding(Player player);

    boolean isInWater(Player player);

    float getAttackCooldown(Player player);

    String getClientBrand(Player player);

    // 几何与碰撞
    CompatBoundingBox getEntityBoundingBox(Entity entity);

    CompatBoundingBox getBlockBoundingBox(Block block);

    boolean isSolidBlock(Block block);

    boolean isSpacePassable(World world, CompatBoundingBox box);

    // 环境与方块材质特性安全判定 (彻底消除 NoSuchFieldError)
    boolean isIce(Block block);

    boolean isSlime(Block block);

    boolean isHoney(Block block);

    boolean isSoulSand(Block block);

    boolean isClimbable(Block block);

    /**
     * 判定指定玩家与坐标周围是否存在可攀爬方块（梯子、藤蔓、脚手架等）。
     * 覆盖 AABB 碰撞箱（0.6 宽）及潜行边缘探出（最大 0.45 格）的多方位探针与垂直高度（脚下至头部），
     * 同时检测当前方块及脚下方块四周相邻附着的梯子，彻底消除在带梯子方块边缘潜行时的 Fly 与 NoFall 误报。
     */
    default boolean isClimbableAround(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        double reach = (player != null && player.isSneaking()) ? 0.45 : 0.35;
        double diag = reach * 0.72;
        double[][] points = {
                {0, 0},
                {reach, 0}, {-reach, 0}, {0, reach}, {0, -reach},
                {diag, diag}, {-diag, diag}, {diag, -diag}, {-diag, -diag}
        };
        // 覆盖脚下 (-0.60格，梯子顶面/下梯)、脚底 (0.0格)、身体躯干 (0.8格)、头部 (1.5格)
        double[] yOffsets = {-0.60, 0.0, 0.8, 1.5};
        for (double[] p : points) {
            Location checkLoc = loc.clone().add(p[0], 0, p[1]);
            for (double yOff : yOffsets) {
                Block b = checkLoc.clone().add(0, yOff, 0).getBlock();
                if (isClimbable(b)) {
                    return true;
                }
            }
        }

        // 检查当前方块与下方一格方块水平相邻四周附着的梯子（针对站在挂有梯子的方块顶面潜行挪动）
        Block current = loc.getBlock();
        Block below = loc.clone().subtract(0, 1.0, 0).getBlock();
        org.bukkit.block.BlockFace[] faces = {
                org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST
        };
        for (org.bukkit.block.BlockFace face : faces) {
            if (isClimbable(current.getRelative(face)) || isClimbable(below.getRelative(face))) {
                return true;
            }
        }
        return false;
    }

    boolean isLiquid(Block block);

    // 世界与环境
    int getMinHeight(World world);

    int getMaxHeight(World world);

    double[] getTPS();

    // 状态属性
    boolean hasPotionEffect(LivingEntity entity, CompatPotionType potionType);

    int getPotionAmplifier(LivingEntity entity, CompatPotionType potionType);

    double getAttributeValue(LivingEntity entity, CompatAttribute attribute, double defaultValue);

    // 交互与渲染
    void sendMessage(CommandSender sender, String colorFormattedText);

    void sendActionBar(Player player, String colorFormattedText);

    void playSound(Player player, Location loc, String soundName, float volume, float pitch);

    // 视线射线碰撞遮挡判定 (跨版本安全判定隔墙攻击)
    boolean isRayOccluded(Location from, Location to);

    // 战斗反应手段：重置玩家武器充能冷却
    void resetAttackCooldown(Player player);

    // 方块落足站立物理判定 (包含地毯、雪片、睡莲、台阶等)
    boolean isStandable(Block block);

    // 附魔等级安全获取
    int getSoulSpeedLevel(Player player);

    int getDepthStriderLevel(Player player);

    int getEfficiencyLevel(Player player);

    // 跨版本安全获取方块硬度 (消除 1.12.2 Material.getHardness 的 NoSuchMethodError)
    float getBlockHardness(Block block);
}
