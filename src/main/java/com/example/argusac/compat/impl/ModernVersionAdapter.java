package com.example.argusac.compat.impl;

import com.example.argusac.compat.CompatAttribute;
import com.example.argusac.compat.CompatBoundingBox;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.EnumMap;
import java.util.Map;

/**
 * 现代版本适配实现 (面向 Paper 1.20+ 原生特性，具备最高的执行效率)
 * 注意：potionType 解析使用 getByName 动态查找+缓存，适配 1.20.4 ~ 1.21.x 常量名称变更
 */
@SuppressWarnings("deprecation")
public class ModernVersionAdapter implements VersionAdapter {

    /**
     * PotionEffectType 静态名称映射表 (优先使用新名，回退旧名)。
     * 1.20.4 API: SLOW / JUMP / FAST_DIGGING / SLOW_DIGGING
     * 1.20.6+ API: SLOWNESS / JUMP_BOOST / HASTE / MINING_FATIGUE (旧名保留为 alias)
     * 通过 getByName() 运行时动态解析，彻底规避跨版本常量丢失问题。
     */
    private static final Map<CompatPotionType, PotionEffectType> POTION_TYPE_CACHE;

    static {
        POTION_TYPE_CACHE = new EnumMap<>(CompatPotionType.class);
        register(CompatPotionType.SPEED,          "SPEED");
        register(CompatPotionType.SLOWNESS,        "SLOWNESS",       "SLOW");
        register(CompatPotionType.JUMP_BOOST,      "JUMP_BOOST",     "JUMP");
        register(CompatPotionType.LEVITATION,      "LEVITATION");
        register(CompatPotionType.SLOW_FALLING,    "SLOW_FALLING");
        register(CompatPotionType.DOLPHINS_GRACE,  "DOLPHINS_GRACE");
        register(CompatPotionType.HASTE,           "HASTE",          "FAST_DIGGING");
        register(CompatPotionType.MINING_FATIGUE,  "MINING_FATIGUE", "SLOW_DIGGING");
    }

    /**
     * 按名称列表优先级尝试注册 PotionEffectType，首个成功命中者缓存。
     */
    private static void register(CompatPotionType key, String... names) {
        for (String name : names) {
            PotionEffectType pet = PotionEffectType.getByName(name);
            if (pet != null) {
                POTION_TYPE_CACHE.put(key, pet);
                return;
            }
        }
        // getByName 全部失败时尝试通过枚举字段名反射匹配
        for (String name : names) {
            try {
                java.lang.reflect.Field f = PotionEffectType.class.getField(name);
                Object val = f.get(null);
                if (val instanceof PotionEffectType pet) {
                    POTION_TYPE_CACHE.put(key, pet);
                    return;
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public int getPing(Player player) {
        return player.getPing();
    }

    @Override
    public boolean isSwimming(Player player) {
        return player.isSwimming();
    }

    @Override
    public boolean isGliding(Player player) {
        return player.isGliding();
    }

    @Override
    public float getAttackCooldown(Player player) {
        return player.getCooledAttackStrength(0.0f);
    }

    @Override
    public String getClientBrand(Player player) {
        String brand = player.getClientBrandName();
        return brand != null ? brand : "vanilla";
    }

    @Override
    public CompatBoundingBox getEntityBoundingBox(Entity entity) {
        BoundingBox b = entity.getBoundingBox();
        return new CompatBoundingBox(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
    }

    @Override
    public CompatBoundingBox getBlockBoundingBox(Block block) {
        BoundingBox b = block.getBoundingBox();
        return new CompatBoundingBox(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
    }

    @Override
    public boolean isRiptiding(Player player) {
        return player.isRiptiding();
    }

    @Override
    public boolean isInWater(Player player) {
        return player.isInWater();
    }

    @Override
    public boolean isSolidBlock(Block block) {
        return block.getType().isSolid();
    }

    @Override
    public boolean isSpacePassable(World world, CompatBoundingBox box) {
        if (world == null || box == null) {
            return false;
        }
        int minBlockX = (int) Math.floor(box.minX());
        int maxBlockX = (int) Math.floor(box.maxX());
        int minBlockY = (int) Math.floor(box.minY());
        int maxBlockY = (int) Math.floor(box.maxY());
        int minBlockZ = (int) Math.floor(box.minZ());
        int maxBlockZ = (int) Math.floor(box.maxZ());

        int minChunkX = minBlockX >> 4;
        int maxChunkX = maxBlockX >> 4;
        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = maxBlockZ >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return false;
                }
            }
        }

        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int y = minBlockY; y <= maxBlockY; y++) {
                for (int z = minBlockZ; z <= maxBlockZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.isEmpty() || block.isLiquid()) {
                        continue;
                    }
                    if (block.isPassable()) {
                        continue;
                    }
                    org.bukkit.util.BoundingBox blockBox = block.getBoundingBox();
                    CompatBoundingBox compatBlockBox = new CompatBoundingBox(
                            blockBox.getMinX(), blockBox.getMinY(), blockBox.getMinZ(),
                            blockBox.getMaxX(), blockBox.getMaxY(), blockBox.getMaxZ()
                    );
                    if (box.intersectsWithMargin(compatBlockBox, 1.0e-4)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean isIce(Block block) {
        org.bukkit.Material m = block.getType();
        return m == org.bukkit.Material.ICE || m == org.bukkit.Material.PACKED_ICE
                || m == org.bukkit.Material.BLUE_ICE || m == org.bukkit.Material.FROSTED_ICE;
    }

    @Override
    public boolean isSlime(Block block) {
        return block.getType() == org.bukkit.Material.SLIME_BLOCK;
    }

    @Override
    public boolean isHoney(Block block) {
        return block.getType() == org.bukkit.Material.HONEY_BLOCK;
    }

    @Override
    public boolean isSoulSand(Block block) {
        org.bukkit.Material m = block.getType();
        return m == org.bukkit.Material.SOUL_SAND || m == org.bukkit.Material.SOUL_SOIL;
    }

    @Override
    public boolean isClimbable(Block block) {
        org.bukkit.Material m = block.getType();
        return m == org.bukkit.Material.LADDER || m == org.bukkit.Material.VINE || m == org.bukkit.Material.SCAFFOLDING
                || m == org.bukkit.Material.COBWEB || m == org.bukkit.Material.POWDER_SNOW
                || m == org.bukkit.Material.WEEPING_VINES || m == org.bukkit.Material.TWISTING_VINES;
    }

    @Override
    public boolean isLiquid(Block block) {
        return block.isLiquid();
    }

    @Override
    public int getMinHeight(World world) {
        return world.getMinHeight();
    }

    @Override
    public int getMaxHeight(World world) {
        return world.getMaxHeight();
    }

    @Override
    public double[] getTPS() {
        return Bukkit.getTPS();
    }

    @Override
    public boolean hasPotionEffect(LivingEntity entity, CompatPotionType potionType) {
        PotionEffectType pet = resolvePotionType(potionType);
        return pet != null && entity.hasPotionEffect(pet);
    }

    @Override
    public int getPotionAmplifier(LivingEntity entity, CompatPotionType potionType) {
        PotionEffectType pet = resolvePotionType(potionType);
        if (pet == null) return -1;
        PotionEffect effect = entity.getPotionEffect(pet);
        return effect != null ? effect.getAmplifier() : -1;
    }

    @Override
    public double getAttributeValue(LivingEntity entity, CompatAttribute attribute, double defaultValue) {
        Attribute bukkitAttr = resolveAttribute(attribute);
        if (bukkitAttr == null) return defaultValue;
        AttributeInstance inst = entity.getAttribute(bukkitAttr);
        return inst != null ? inst.getValue() : defaultValue;
    }

    @Override
    public void sendMessage(CommandSender sender, String colorFormattedText) {
        sender.sendMessage(ColorUtil.toComponent(colorFormattedText));
    }

    @Override
    public void sendActionBar(Player player, String colorFormattedText) {
        player.sendActionBar(ColorUtil.toComponent(colorFormattedText));
    }

    @Override
    public void playSound(Player player, Location loc, String soundName, float volume, float pitch) {
        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(loc, sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {}
    }

    private PotionEffectType resolvePotionType(CompatPotionType type) {
        return POTION_TYPE_CACHE.get(type);
    }

    private Attribute resolveAttribute(CompatAttribute attr) {
        return switch (attr) {
            case GENERIC_MOVEMENT_SPEED -> Attribute.GENERIC_MOVEMENT_SPEED;
            case GENERIC_ATTACK_SPEED -> Attribute.GENERIC_ATTACK_SPEED;
            case GENERIC_MAX_HEALTH -> Attribute.GENERIC_MAX_HEALTH;
        };
    }
}
