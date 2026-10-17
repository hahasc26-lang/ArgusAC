package com.example.aegisac.compat.impl;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.compat.CompatAttribute;
import com.example.aegisac.compat.CompatBoundingBox;
import com.example.aegisac.compat.CompatPotionType;
import com.example.aegisac.compat.ServerVersion;
import com.example.aegisac.compat.VersionAdapter;
import com.example.aegisac.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * 低版本与 Spigot 降级实现类 (包含反射穿透、NMS 碰撞箱安全采样与零崩溃兜底)
 */
@SuppressWarnings("deprecation")
public class LegacyVersionAdapter implements VersionAdapter {

    private Method getHandleMethod;
    private Field pingField;
    private boolean pingReflectionFailed = false;

    // NMS 实体 AxisAlignedBB 反射
    private Method getBoundingBoxMethod;
    private Field minXField, minYField, minZField, maxXField, maxYField, maxZField;
    private boolean aabbReflectionFailed = false;

    // Spigot TPS 反射缓存
    private Object minecraftServerInstance;
    private Field recentTpsField;
    private boolean tpsReflectionFailed = false;

    // 材质名称集合缓存 (避免使用高版本 Material 枚举静态常量导致 NoSuchFieldError)
    private static final Set<String> ICE_MATERIALS = new HashSet<>(Arrays.asList("ICE", "PACKED_ICE", "FROSTED_ICE", "BLUE_ICE"));
    private static final Set<String> CLIMBABLE_MATERIALS = new HashSet<>(Arrays.asList(
            "LADDER", "VINE", "SCAFFOLDING", "COBWEB", "POWDER_SNOW", "WEEPING_VINES", "TWISTING_VINES"
    ));

    public LegacyVersionAdapter() {
        initPlayerReflection();
        initAabbReflection();
        initTpsReflection();
    }

    private void initPlayerReflection() {
        try {
            Class<?> craftPlayerClass = Class.forName(Bukkit.getServer().getClass().getPackage().getName() + ".entity.CraftPlayer");
            this.getHandleMethod = craftPlayerClass.getMethod("getHandle");
        } catch (Exception ignored) {}
    }

    private void initAabbReflection() {
        try {
            Class<?> nmsEntityClass = Class.forName(Bukkit.getServer().getClass().getPackage().getName().replace("org.bukkit.craftbukkit", "net.minecraft.server") + ".Entity");
            this.getBoundingBoxMethod = nmsEntityClass.getMethod("getBoundingBox");
            Class<?> aabbClass = Class.forName(Bukkit.getServer().getClass().getPackage().getName().replace("org.bukkit.craftbukkit", "net.minecraft.server") + ".AxisAlignedBB");
            this.minXField = aabbClass.getField("a");
            this.minYField = aabbClass.getField("b");
            this.minZField = aabbClass.getField("c");
            this.maxXField = aabbClass.getField("d");
            this.maxYField = aabbClass.getField("e");
            this.maxZField = aabbClass.getField("f");
        } catch (Throwable ignored) {
            this.aabbReflectionFailed = true;
        }
    }

    private void initTpsReflection() {
        try {
            Class<?> craftServerClass = Class.forName(Bukkit.getServer().getClass().getPackage().getName() + ".CraftServer");
            Method getServerMethod = craftServerClass.getMethod("getServer");
            this.minecraftServerInstance = getServerMethod.invoke(Bukkit.getServer());
            Class<?> mcServerClass = this.minecraftServerInstance.getClass();
            this.recentTpsField = mcServerClass.getField("recentTps");
        } catch (Throwable ignored) {
            this.tpsReflectionFailed = true;
        }
    }

    @Override
    public int getPing(Player player) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_16_5)) {
            try {
                return player.getPing();
            } catch (Throwable ignored) {}
        }

        if (pingReflectionFailed || getHandleMethod == null) return -1;

        try {
            Object nmsPlayer = getHandleMethod.invoke(player);
            if (pingField == null) {
                try {
                    pingField = nmsPlayer.getClass().getField("ping");
                } catch (NoSuchFieldException e) {
                    pingField = nmsPlayer.getClass().getDeclaredField("ping");
                    pingField.setAccessible(true);
                }
            }
            return pingField.getInt(nmsPlayer);
        } catch (Exception e) {
            pingReflectionFailed = true;
            Bukkit.getLogger().log(Level.FINE, "[AegisAC-Compat] 反射读取 NMS 延迟失败，后续转为默认容差: " + e.getMessage());
            return -1;
        }
    }

    @Override
    public boolean isSwimming(Player player) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_13)) {
            try {
                return player.isSwimming();
            } catch (Throwable ignored) {}
        }
        // 1.12.2 降级逻辑：玩家处于水中
        return isInWater(player);
    }

    @Override
    public boolean isGliding(Player player) {
        try {
            return player.isGliding();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean isRiptiding(Player player) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_13)) {
            try {
                return player.isRiptiding();
            } catch (Throwable ignored) {}
        }
        return false;
    }

    @Override
    public boolean isInWater(Player player) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_15_2)) {
            try {
                return player.isInWater();
            } catch (Throwable ignored) {}
        }
        String matName = player.getLocation().getBlock().getType().name();
        return matName.contains("WATER");
    }

    @Override
    public float getAttackCooldown(Player player) {
        try {
            return player.getCooledAttackStrength(0.0f);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    @Override
    public String getClientBrand(Player player) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_13)) {
            try {
                String brand = player.getClientBrandName();
                if (brand != null) return brand;
            } catch (Throwable ignored) {}
        }
        if (AegisACPlugin.getInstance() != null && AegisACPlugin.getInstance().getPlayerManager() != null) {
            var data = AegisACPlugin.getInstance().getPlayerManager().getPlayerData(player);
            if (data != null && data.getClientBrand() != null) {
                return data.getClientBrand();
            }
        }
        return "legacy/vanilla";
    }

    @Override
    public CompatBoundingBox getEntityBoundingBox(Entity entity) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_13)) {
            try {
                org.bukkit.util.BoundingBox b = entity.getBoundingBox();
                return new CompatBoundingBox(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
            } catch (Throwable ignored) {}
        }

        // 1.12.2 尝试反射 NMS AxisAlignedBB
        if (!aabbReflectionFailed && getHandleMethod != null && getBoundingBoxMethod != null) {
            try {
                Object nmsEntity = getHandleMethod.invoke(entity);
                Object aabb = getBoundingBoxMethod.invoke(nmsEntity);
                if (aabb != null) {
                    return new CompatBoundingBox(
                            minXField.getDouble(aabb),
                            minYField.getDouble(aabb),
                            minZField.getDouble(aabb),
                            maxXField.getDouble(aabb),
                            maxYField.getDouble(aabb),
                            maxZField.getDouble(aabb)
                    );
                }
            } catch (Throwable ignored) {
                this.aabbReflectionFailed = true;
            }
        }

        // 安全数学兜底：基于中心坐标与标准尺寸构建盒模型
        Location loc = entity.getLocation();
        double w = 0.6 / 2.0;
        double h = 1.8;
        return new CompatBoundingBox(loc.getX() - w, loc.getY(), loc.getZ() - w, loc.getX() + w, loc.getY() + h, loc.getZ() + w);
    }

    @Override
    public CompatBoundingBox getBlockBoundingBox(Block block) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_13)) {
            try {
                org.bukkit.util.BoundingBox b = block.getBoundingBox();
                return new CompatBoundingBox(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ());
            } catch (Throwable ignored) {}
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        return new CompatBoundingBox(x, y, z, x + 1.0, y + 1.0, z + 1.0);
    }

    @Override
    public boolean isSolidBlock(Block block) {
        try {
            return block.getType().isSolid();
        } catch (Throwable e) {
            return true;
        }
    }

    @Override
    public boolean isIce(Block block) {
        return ICE_MATERIALS.contains(block.getType().name());
    }

    @Override
    public boolean isSlime(Block block) {
        return "SLIME_BLOCK".equals(block.getType().name());
    }

    @Override
    public boolean isHoney(Block block) {
        return "HONEY_BLOCK".equals(block.getType().name());
    }

    @Override
    public boolean isSoulSand(Block block) {
        String name = block.getType().name();
        return "SOUL_SAND".equals(name) || "SOUL_SOIL".equals(name);
    }

    @Override
    public boolean isClimbable(Block block) {
        String name = block.getType().name();
        return CLIMBABLE_MATERIALS.contains(name) || name.contains("VINE") || name.contains("BERRY");
    }

    @Override
    public boolean isLiquid(Block block) {
        try {
            return block.isLiquid();
        } catch (Throwable ignored) {
            String name = block.getType().name();
            return name.contains("WATER") || name.contains("LAVA");
        }
    }

    @Override
    public int getMinHeight(World world) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_17_1)) {
            try {
                return world.getMinHeight();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    @Override
    public int getMaxHeight(World world) {
        if (ServerVersion.isAtLeast(ServerVersion.V1_17_1)) {
            try {
                return world.getMaxHeight();
            } catch (Throwable ignored) {}
        }
        return 256;
    }

    @Override
    public double[] getTPS() {
        if (ServerVersion.isPaper()) {
            try {
                return Bukkit.getTPS();
            } catch (Throwable ignored) {}
        }

        // Spigot 环境反射读取 recentTps
        if (!tpsReflectionFailed && minecraftServerInstance != null && recentTpsField != null) {
            try {
                return (double[]) recentTpsField.get(minecraftServerInstance);
            } catch (Throwable ignored) {
                tpsReflectionFailed = true;
            }
        }

        // 最终兜底：内置 TickMonitor 采样器或安全默认值
        double currentTps = TickMonitor.getEstimatedTps();
        return new double[]{ currentTps, currentTps, currentTps };
    }

    @Override
    public boolean hasPotionEffect(LivingEntity entity, CompatPotionType potionType) {
        PotionEffectType type = matchLegacyPotionType(potionType);
        return type != null && entity.hasPotionEffect(type);
    }

    @Override
    public int getPotionAmplifier(LivingEntity entity, CompatPotionType potionType) {
        PotionEffectType type = matchLegacyPotionType(potionType);
        if (type == null) return -1;
        for (PotionEffect effect : entity.getActivePotionEffects()) {
            if (effect.getType().equals(type)) {
                return effect.getAmplifier();
            }
        }
        return -1;
    }

    @Override
    public double getAttributeValue(LivingEntity entity, CompatAttribute attribute, double defaultValue) {
        try {
            org.bukkit.attribute.Attribute bukkitAttr = org.bukkit.attribute.Attribute.valueOf(attribute.name());
            org.bukkit.attribute.AttributeInstance instance = entity.getAttribute(bukkitAttr);
            return instance != null ? instance.getValue() : defaultValue;
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    @Override
    public void sendMessage(CommandSender sender, String colorFormattedText) {
        try {
            sender.sendMessage(ColorUtil.toComponent(colorFormattedText));
        } catch (Throwable t) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', colorFormattedText));
        }
    }

    @Override
    public void sendActionBar(Player player, String colorFormattedText) {
        try {
            player.sendActionBar(ColorUtil.toComponent(colorFormattedText));
            return;
        } catch (Throwable ignored) {}

        String msg = ChatColor.translateAlternateColorCodes('&', colorFormattedText);
        try {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg));
        } catch (Throwable t) {
            player.sendMessage(msg);
        }
    }

    @Override
    public void playSound(Player player, Location loc, String soundName, float volume, float pitch) {
        try {
            Sound sound = Sound.valueOf(mapLegacySoundName(soundName));
            player.playSound(loc, sound, volume, pitch);
        } catch (Throwable ignored) {}
    }

    private PotionEffectType matchLegacyPotionType(CompatPotionType type) {
        return switch (type) {
            case SPEED -> PotionEffectType.SPEED;
            case SLOWNESS -> PotionEffectType.SLOW;
            case JUMP_BOOST -> PotionEffectType.JUMP;
            case LEVITATION -> PotionEffectType.getByName("LEVITATION");
            case SLOW_FALLING -> PotionEffectType.getByName("SLOW_FALLING");
            case DOLPHINS_GRACE -> PotionEffectType.getByName("DOLPHINS_GRACE");
            case HASTE -> PotionEffectType.FAST_DIGGING;
            case MINING_FATIGUE -> PotionEffectType.SLOW_DIGGING;
        };
    }

    private String mapLegacySoundName(String soundName) {
        if (ServerVersion.isOlderThan(ServerVersion.V1_13)) {
            if ("BLOCK_NOTE_BLOCK_BASS".equalsIgnoreCase(soundName)) return "BLOCK_NOTE_BASS";
            if ("ENTITY_EXPERIENCE_ORB_PICKUP".equalsIgnoreCase(soundName)) return "ENTITY_EXPERIENCE_ORB_TOUCH";
        }
        return soundName;
    }
}

