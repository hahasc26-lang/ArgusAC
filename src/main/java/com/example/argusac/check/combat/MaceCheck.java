package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.compat.CompatPotionType;
import com.example.argusac.compat.ServerVersion;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;

/**
 * 重锤战斗与作弊下落伤害检测 (MaceCheck / MaceDMG)
 * 专为 Minecraft 1.21+ 新增重锤 (Mace) 武器打造的全方位反作弊防护模块：
 *
 * 1. 虚假下落高度与真峰值校验 (True Apex Fall Verification - MaceDMG):
 *    反作弊独立追踪玩家自离开地面以来的物理真最高点 (True Apex Y)。
 *    玩家在本次滞空中所能达到的最大物理坠落高度严格受限于:
 *    maxPhysicalFall = max(0, trueApexY - currentY)。
 *    若客户端报告的 fallDistance 严重超出物理真高度 (+ 容差)，
 *    即可断定客户端通过数据包伪造了虚假下落高度 (Fake Fall)。
 *
 * 2. 地面异常砸击拦截 (Ground Smash Exploit):
 *    原版重锤重击 (Smash Attack) 必须满足处于空中且下落高度 > 1.5 格。
 *    在平地起跳 (最高仅约 1.25 格) 或站在地面上时无法合法触发砸击。
 *    若玩家在地面接地状态下造成远超普通挥砍的重击伤害，立即予以拦截。
 *
 * 3. 运动学下落速度与耗时违背 (Kinematic Fall Constraint):
 *    原版重力加速度 g=0.08, 空气阻力 drag=0.98, 终端极速约 3.92 blocks/tick。
 *    若玩家滞空 Tick 极短 (例如仅 1~2 ticks)，却声称下落了数十格，
 *    在物理运动学上完全不可能，直接判定为瞬发伪造包。
 *
 * 4. 理论极限伤害与伤害硬上限防护 (Theoretical Damage Ceiling & Damage Cap):
 *    精确计算重锤基础伤害 (6.0)、力量药水、致密 (Density I~V)、暴击加成
 *    以及基于物理真高度计算的下落增益上限。
 *    若事件伤害超出该物理极限，或超出配置的绝对硬上限，立即静默取消或削弱为合法值。
 *
 * 5. 风爆 (Wind Burst) 连击与瞬发频率限制 (Smash Rate Limiting):
 *    限制连续重击最小时间间隔，防御利用数据包连点实现原地连续无限砸击。
 */

public class MaceCheck extends AbstractCheck {

    private boolean checkGroundSmash;
    private boolean checkApexFall;
    private double apexTolerance;
    private boolean checkImpossibleFallSpeed;
    private boolean checkDamageCap;
    private double maxDamageLimit;
    private long minSmashIntervalMs;
    private String mitigationMode;

    public MaceCheck(ArgusACPlugin plugin) {
        super(plugin, "Mace", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.checkGroundSmash = getChecksConfig().getBoolean(path + "check-ground-smash", true);
        this.checkApexFall = getChecksConfig().getBoolean(path + "check-apex-fall", true);
        this.apexTolerance = getChecksConfig().getDouble(path + "apex-tolerance", 0.85);
        this.checkImpossibleFallSpeed = getChecksConfig().getBoolean(path + "check-impossible-fall-speed", true);
        this.checkDamageCap = getChecksConfig().getBoolean(path + "check-damage-cap", true);
        this.maxDamageLimit = getChecksConfig().getDouble(path + "max-damage-limit", 48.0);
        this.minSmashIntervalMs = getChecksConfig().getLong(path + "min-smash-interval-ms", 350L);
        this.mitigationMode = getChecksConfig().getString(path + "mitigation-mode", "CANCEL").toUpperCase(Locale.ROOT);
        if (!"NERF".equals(this.mitigationMode) && !"CANCEL".equals(this.mitigationMode)) {
            this.mitigationMode = "CANCEL";
        }
    }

    /**
     * 核心检测入口：在实体受到近战伤害时进行重锤作弊校验
     */
    public void handleAttack(Player attacker, Entity victim, EntityDamageByEntityEvent event) {
        if (!enabled || attacker == null || !(victim instanceof LivingEntity)) {
            return;
        }

        // Minecraft 1.21 以前不存在重锤机制，直接放行
        if (ServerVersion.isOlderThan(ServerVersion.V1_21)) {
            return;
        }

        // 创造模式或旁观模式豁免
        if (attacker.getGameMode() == GameMode.CREATIVE || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(attacker);
        if (data == null || data.isInGracePeriod() || data.isSetbackImmune()) {
            return;
        }

        // 检查主手是否持有重锤 (Mace)
        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        if (!isMace(weapon)) {
            return;
        }

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        double currentY = attacker.getLocation().getY();
        double maxNonSmash = calculateMaxNonSmashDamage(attacker, weapon);

        // 1. 载具与坐骑砸击拦截：在坐骑/船/矿车上严禁触发重锤重击
        if (attacker.isInsideVehicle()) {
            if (event.getDamage() > maxNonSmash + 1.0) {
                flagViolation(attacker, event, 2,
                        String.format("Mace smash inside vehicle (damage=%.1f, maxNonSmash=%.1f)",
                                event.getDamage(), maxNonSmash));
                return;
            }
        }

        // 2. 鞘翅滑翔与飞行砸击拦截：原版严格限制 !isFallFlying() 与 !isFlying()
        if (attacker.isGliding() || data.isElytraGliding() || attacker.isFlying()) {
            if (event.getDamage() > maxNonSmash + 1.0) {
                flagViolation(attacker, event, 2,
                        String.format("Mace smash while gliding/flying (damage=%.1f, maxNonSmash=%.1f)",
                                event.getDamage(), maxNonSmash));
                return;
            }
        }

        // 3. 流体与蜘蛛网中砸击拦截：液体与蛛网中重力与下落速度衰减，重锤下落距离清零
        Location feetLoc = attacker.getLocation();
        if (adapter.isLiquid(feetLoc.getBlock()) || feetLoc.getBlock().getType().name().contains("COBWEB")) {
            if (event.getDamage() > maxNonSmash + 1.0 || attacker.getFallDistance() > 1.5f) {
                flagViolation(attacker, event, 2,
                        String.format("Mace smash in liquid/cobweb (damage=%.1f, fall=%.2f)",
                                event.getDamage(), attacker.getFallDistance()));
                return;
            }
        }

        // 4. 连续重击最小间隔限制 (Smash Rate Limiting)
        long now = System.currentTimeMillis();
        boolean isSmash = event.getDamage() > (maxNonSmash + 0.5) || attacker.getFallDistance() > 1.5f;
        if (isSmash && minSmashIntervalMs > 0) {
            long lastSmash = data.getLastMaceSmashTime();
            if (lastSmash > 0 && (now - lastSmash < minSmashIntervalMs)) {
                flagViolation(attacker, event, 2,
                        String.format("Rapid consecutive smash attacks (interval=%dms < %dms)",
                                (now - lastSmash), minSmashIntervalMs));
                return;
            }
        }

        double trueApexY = data.getMaceApexY();
        // 若真最高点低于当前位置 (例如刚起跳或异常漂移)，校准为当前位置
        if (trueApexY < currentY) {
            trueApexY = currentY;
            data.setMaceApexY(currentY);
        }

        double maxPhysicalFall = Math.max(0.0, trueApexY - currentY);
        float reportedFall = attacker.getFallDistance();
        int airTicks = data.getMaceAirTicks();
        boolean grounded = data.isOnGround() || ((Entity) attacker).isOnGround() || data.getGroundTicks() > 0;

        // 5. 地面异常砸击校验 (Ground Smash Check)
        if (checkGroundSmash) {
            if (grounded) {
                // 地面接地状态下，绝不可能合法造成重锤砸击伤害
                if (event.getDamage() > maxNonSmash + 1.0 || reportedFall > 1.5f) {
                    flagViolation(attacker, event, 2,
                            String.format("Ground smash exploit: damage=%.1f, maxNonSmash=%.1f, reportedFall=%.2f, grounded=%b",
                                    event.getDamage(), maxNonSmash, reportedFall, grounded));
                    return;
                }
            } else if (maxPhysicalFall < 1.45) {
                // 原版平地正常起跳最高约 1.25 格 (< 1.5 格必须门槛)，若真物理落差不足 1.45 格，无法合法触发砸击
                if (event.getDamage() > maxNonSmash + 1.5 || reportedFall > 1.5f) {
                    flagViolation(attacker, event, 2,
                            String.format("Insufficient physical fall for smash: physicalFall=%.2f < 1.50, reportedFall=%.2f, damage=%.1f",
                                    maxPhysicalFall, reportedFall, event.getDamage()));
                    return;
                }
            }
        }

        // 6. 虚假下落高度与物理真峰值校验 (True Apex Fall Verification - MaceDMG)
        if (checkApexFall) {
            if (reportedFall > (maxPhysicalFall + apexTolerance)) {
                double delta = reportedFall - maxPhysicalFall;
                int vl = Math.min(5, Math.max(1, (int) (delta / 3.0)));
                flagViolation(attacker, event, vl,
                        String.format("MaceDMG spoofed fall distance: reported=%.2f, trueMax=%.2f, diff=%.2f (tolerance=%.2f)",
                                reportedFall, maxPhysicalFall, delta, apexTolerance));
                return;
            }
        }

        // 7. 运动学下落速度与耗时校验 (Kinematic Fall Constraint)
        if (checkImpossibleFallSpeed && !data.hasAnyLegalVelocity()) {
            double kinematicMax = calculateKinematicMaxFall(airTicks);
            if (reportedFall > kinematicMax) {
                flagViolation(attacker, event, 2,
                        String.format("Impossible fall velocity: reported=%.2f in %d airTicks (kinematicMax=%.2f)",
                                reportedFall, airTicks, kinematicMax));
                return;
            }
        }

        // 8. 理论极限伤害与伤害硬上限防护 (Theoretical Damage Ceiling & Damage Cap)
        if (checkDamageCap) {
            double verifiedFall = Math.min(reportedFall, maxPhysicalFall);
            double theoreticalMax = calculateTheoreticalMaxMaceDamage(attacker, weapon, verifiedFall);

            // 8.1 伤害硬上限拦截
            if (maxDamageLimit > 0 && event.getDamage() > maxDamageLimit) {
                flagViolation(attacker, event, 2,
                        String.format("Mace damage exceeded hard cap: damage=%.1f > limit=%.1f",
                                event.getDamage(), maxDamageLimit));
                return;
            }

            // 8.2 理论计算极限拦截 (留出 25% 容错与 2.0 浮点浮动)
            double allowedCeiling = theoreticalMax * 1.25 + 2.0;
            if (event.getDamage() > allowedCeiling) {
                flagViolation(attacker, event, 2,
                        String.format("Mace damage exceeded theoretical ceiling: damage=%.1f > ceiling=%.1f (verifiedFall=%.2f)",
                                event.getDamage(), allowedCeiling, verifiedFall));
                return;
            }
        }

        // 9. 砸击判定通过：记录砸击时间戳并重置下落状态 (对齐原版砸击后重置 fallDistance)
        if (isSmash) {
            data.setLastMaceSmashTime(now);
            data.resetMaceFallState(currentY);
        }
    }

    /**
     * 违规拦截处理：支持静默取消 (CANCEL) 与伤害削减 (NERF)
     */
    private void flagViolation(Player attacker, EntityDamageByEntityEvent event, int vl, String reason) {
        if ("NERF".equals(mitigationMode)) {
            // 削弱伤害至普通武器合法基础伤害，并重置伪造的下落距离
            double legalBase = calculateMaxNonSmashDamage(attacker, attacker.getInventory().getItemInMainHand());
            event.setDamage(Math.min(event.getDamage(), legalBase));
            attacker.setFallDistance(0.0f);
            if (plugin.getViolationManager() != null) {
                plugin.getViolationManager().handleViolation(attacker, this, vl, reason, null);
            }
        } else {
            // 默认 CANCEL: 纯静默拦截，伤害双重归零，并设置微秒级攻击抑制
            plugin.getCombatMitigationHandler().mitigateDamage(event, attacker, event.getEntity(), this, vl, reason);
        }
    }

    /**
     * 判断指定物品是否为重锤 (Mace)
     */
    public boolean isMace(ItemStack item) {
        if (item == null) {
            return false;
        }
        String typeName = item.getType().name();
        return typeName.equals("MACE") || typeName.equalsIgnoreCase("MACE");
    }

    /**
     * 计算非砸击状态下的合法最大挥击伤害 (包含力量药水与常规暴击加成)
     */
    public double calculateMaxNonSmashDamage(Player attacker, ItemStack weapon) {
        double base = 6.0; // 重锤基础伤害为 6.0

        // 力量药水效果加成 (力量 I +3, 力量 II +6)
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        if (adapter.hasPotionEffect(attacker, CompatPotionType.STRENGTH)) {
            int amp = adapter.getPotionAmplifier(attacker, CompatPotionType.STRENGTH);
            base += (amp + 1) * 3.0;
        }

        // 常规暴击 (1.5x)
        double crit = base * 1.5;
        return crit + 1.5; // 附加 1.5 缓冲容差
    }

    /**
     * 计算基于真实下落距离的重锤理论最大总伤害
     *
     * 原版 1.21 重锤下落伤害公式:
     * - 前 3 格: 每格 +4.0 伤害 (累计可达 12.0)
     * - 4~8 格: 每格 +2.0 伤害 (累计可达 10.0)
     * - 8 格以后: 每格 +1.0 伤害
     * - 致密 (Density I~V) 附魔: 每级每格下落额外 +0.5 伤害 (Density V: 每格 +2.5)
     */
    public double calculateTheoreticalMaxMaceDamage(Player attacker, ItemStack weapon, double fallDistance) {
        double base = calculateMaxNonSmashDamage(attacker, weapon);
        if (fallDistance <= 1.5) {
            return base;
        }

        double bonus = 0.0;
        double d = fallDistance;
        if (d <= 3.0) {
            bonus += d * 4.0;
        } else if (d <= 8.0) {
            bonus += 3.0 * 4.0 + (d - 3.0) * 2.0;
        } else {
            bonus += 3.0 * 4.0 + 5.0 * 2.0 + (d - 8.0) * 1.0;
        }

        // 致密附魔增益
        int density = getDensityLevel(weapon);
        if (density > 0) {
            bonus += (density * 0.5) * d;
        }

        // 亡灵杀手 / 节肢杀手加成 (每级 +2.5)
        int smite = getEnchantmentLevel(weapon, "smite");
        int bane = getEnchantmentLevel(weapon, "bane_of_arthropods");
        int extra = Math.max(smite, bane);
        if (extra > 0) {
            bonus += extra * 2.5;
        }

        return base + bonus;
    }

    /**
     * 根据滞空 Tick 数推算原版重力运动学允许的最大下落距离
     */
    public double calculateKinematicMaxFall(int airTicks) {
        if (airTicks <= 0) {
            return 1.5;
        }
        // 原版终端下落速度约为 3.92 blocks/tick，给 2.0 格初速/击退缓冲
        return airTicks * 3.92 + 2.0;
    }

    /**
     * 跨版本动态获取致密 (Density) 附魔等级
     */
    private int getDensityLevel(ItemStack item) {
        return getEnchantmentLevel(item, "density");
    }

    /**
     * 跨版本安全获取指定名称的附魔等级 (防止枚举缺少导致 NoSuchFieldError)
     */
    private int getEnchantmentLevel(ItemStack item, String matchKey) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        try {
            for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
                Enchantment ench = entry.getKey();
                if (ench == null) continue;
                String key = "";
                try {
                    key = ench.getKey().getKey().toLowerCase(Locale.ROOT);
                } catch (Throwable t) {
                    try {
                        java.lang.reflect.Method m = ench.getClass().getMethod("getName");
                        Object res = m.invoke(ench);
                        if (res != null) {
                            key = res.toString().toLowerCase(Locale.ROOT);
                        }
                    } catch (Throwable ignored) {}
                }
                if (key.equals(matchKey) || key.contains(matchKey)) {
                    return entry.getValue() != null ? entry.getValue() : 0;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}

