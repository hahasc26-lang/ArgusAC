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
}
