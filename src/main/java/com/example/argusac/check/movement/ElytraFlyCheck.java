package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.ElytraData;
import com.example.argusac.data.PlayerData;
import com.example.argusac.model.VelocitySourceType;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.Vector;

/**
 * 鞘翅滑翔与非法飞行集中检测模块 (ElytraFlyCheck)
 * <p>
 * 深度解析 Minecraft 鞘翅滑翔物理动力学，作为独立检测项完整接入 ArgusAC 架构体系。
 * 包含 8 大独立可配置子检测项：
 * 1. ElytraSpeed: 滑翔水平速度上限（综合俯冲 Pitch、烟花推进与外部推力）
 * 2. ElytraAscend: 无推进异常上升（严密区分烟花/激流/气泡柱/风弹）
 * 3. ElytraHover: 空中悬停滞空（容忍起飞缓冲、贴墙碰撞与缓降/悬浮药水）
 * 4. ElytraFirework: 烟花消耗与正向加速不匹配反向作弊判定
 * 5. ElytraGlideState: 滑翔装备与耐久一致性（防无鞘翅/破损滑翔状态伪造）
 * 6. ElytraTimer: 滑翔 Tick 速率异常（滑动窗口平均采样联动服务器 TPS）
 * 7. ElytraFlip: 高频开关滑翔状态刷初速度（GlideFlip 作弊）
 * 8. ElytraNoFall: 触地瞬间开关滑翔免摔滥用判定
 */
public class ElytraFlyCheck extends AbstractCheck {

    // 独立子检测项实例
    private final ElytraSubCheck speedSubCheck;
    private final ElytraSubCheck ascendSubCheck;
    private final ElytraSubCheck hoverSubCheck;
    private final ElytraSubCheck fireworkSubCheck;
    private final ElytraSubCheck glideStateSubCheck;
    private final ElytraSubCheck timerSubCheck;
    private final ElytraSubCheck flipSubCheck;
    private final ElytraSubCheck noFallSubCheck;

    // 全局豁免窗口与环境参数
    private long fireworkBoostDurationMs;
    private long riptideDurationMs;
    private long externalVelocityDurationMs;
    private long takeoffGraceDurationMs;
    private double minServerTps;

    // 1. ElytraSpeed 参数
    private double speedMaxUnboosted;
    private double speedMaxBoosted;
    private double speedPitchDiveMultiplier;

    // 2. ElytraAscend 参数
    private double ascendMaxVertical;
    private int ascendTicksThreshold;

    // 3. ElytraHover 参数
    private double hoverMaxHorizontalSpeed;
    private double hoverMaxVerticalSpeed;
    private int hoverTicksThreshold;

    // 4. ElytraFirework 参数
    private double fireworkMinUnpropelledAccel;
    private int fireworkContinuousHighSpeedTicks;

    // 5. ElytraGlideState 参数
    private boolean glideStateCheckDurability;

    // 6. ElytraTimer 参数
    private int timerSampleWindowSize;
    private double timerMinAverageIntervalMs;

    // 7. ElytraFlip 参数
    private long flipWindowMs;
    private int flipMaxToggles;

    // 8. ElytraNoFall 参数
    private float noFallMinFallDistance;
    private double noFallGroundProximityDistance;

    public ElytraFlyCheck(ArgusACPlugin plugin) {
        super(plugin, "ElytraFly", CheckType.MOVEMENT);

        // 初始化 8 个子检测项实体，每个子项持有独立名称、回退模式与 min-vl 状态
        this.speedSubCheck = new ElytraSubCheck(this, "ElytraSpeed");
        this.ascendSubCheck = new ElytraSubCheck(this, "ElytraAscend");
        this.hoverSubCheck = new ElytraSubCheck(this, "ElytraHover");
        this.fireworkSubCheck = new ElytraSubCheck(this, "ElytraFirework");
        this.glideStateSubCheck = new ElytraSubCheck(this, "ElytraGlideState");
        this.timerSubCheck = new ElytraSubCheck(this, "ElytraTimer");
        this.flipSubCheck = new ElytraSubCheck(this, "ElytraFlip");
        this.noFallSubCheck = new ElytraSubCheck(this, "ElytraNoFall");
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        FileConfiguration config = getChecksConfig();
        String basePath = "checks.ElytraFly.";

        // 全局豁免与环境配置
        this.fireworkBoostDurationMs = config.getLong(basePath + "exemptions.firework-boost-duration-ms", 1500L);
        this.riptideDurationMs = config.getLong(basePath + "exemptions.riptide-duration-ms", 2000L);
        this.externalVelocityDurationMs = config.getLong(basePath + "exemptions.external-velocity-duration-ms", 1200L);
        this.takeoffGraceDurationMs = config.getLong(basePath + "exemptions.takeoff-grace-duration-ms", 1000L);
        this.minServerTps = config.getDouble(basePath + "exemptions.min-server-tps", 17.5);

        // 子项一: ElytraSpeed
        this.speedSubCheck.reload(
                config.getBoolean(basePath + "speed.enabled", true),
                config.getInt(basePath + "speed.min-vl", 3),
                config.getString(basePath + "speed.setback-mode", "HARD")
        );
        this.speedMaxUnboosted = config.getDouble(basePath + "speed.max-unboosted-speed", 1.45);
        this.speedMaxBoosted = config.getDouble(basePath + "speed.max-boosted-speed", 2.60);
        this.speedPitchDiveMultiplier = config.getDouble(basePath + "speed.pitch-dive-multiplier", 0.55);

        // 子项二: ElytraAscend
        this.ascendSubCheck.reload(
                config.getBoolean(basePath + "ascend.enabled", true),
                config.getInt(basePath + "ascend.min-vl", 3),
                config.getString(basePath + "ascend.setback-mode", "HARD")
        );
        this.ascendMaxVertical = config.getDouble(basePath + "ascend.max-vertical-ascent", 0.15);
        this.ascendTicksThreshold = config.getInt(basePath + "ascend.ascend-ticks-threshold", 4);

        // 子项三: ElytraHover
        this.hoverSubCheck.reload(
                config.getBoolean(basePath + "hover.enabled", true),
                config.getInt(basePath + "hover.min-vl", 3),
                config.getString(basePath + "hover.setback-mode", "HARD")
        );
        this.hoverMaxHorizontalSpeed = config.getDouble(basePath + "hover.max-horizontal-speed", 0.08);
        this.hoverMaxVerticalSpeed = config.getDouble(basePath + "hover.max-vertical-speed", 0.05);
        this.hoverTicksThreshold = config.getInt(basePath + "hover.hover-ticks-threshold", 15);

        // 子项四: ElytraFirework
        this.fireworkSubCheck.reload(
                config.getBoolean(basePath + "firework.enabled", true),
                config.getInt(basePath + "firework.min-vl", 3),
                config.getString(basePath + "firework.setback-mode", "HARD")
        );
        this.fireworkMinUnpropelledAccel = config.getDouble(basePath + "firework.min-unpropelled-accel", 0.12);
        this.fireworkContinuousHighSpeedTicks = config.getInt(basePath + "firework.continuous-high-speed-ticks", 50);

        // 子项五: ElytraGlideState (伪造鞘翅状态默认 min-vl 调低至 1，置信度极高)
        this.glideStateSubCheck.reload(
                config.getBoolean(basePath + "glide-state.enabled", true),
                config.getInt(basePath + "glide-state.min-vl", 1),
                config.getString(basePath + "glide-state.setback-mode", "HARD")
        );
        this.glideStateCheckDurability = config.getBoolean(basePath + "glide-state.check-chestplate-durability", true);

        // 子项六: ElytraTimer
        this.timerSubCheck.reload(
                config.getBoolean(basePath + "timer.enabled", true),
                config.getInt(basePath + "timer.min-vl", 3),
                config.getString(basePath + "timer.setback-mode", "SOFT")
        );
        this.timerSampleWindowSize = config.getInt(basePath + "timer.sample-window-size", 20);
        this.timerMinAverageIntervalMs = config.getDouble(basePath + "timer.min-average-interval-ms", 44.0);

        // 子项七: ElytraFlip
        this.flipSubCheck.reload(
                config.getBoolean(basePath + "flip.enabled", true),
                config.getInt(basePath + "flip.min-vl", 3),
                config.getString(basePath + "flip.setback-mode", "SOFT")
        );
        this.flipWindowMs = config.getLong(basePath + "flip.window-ms", 1000L);
        this.flipMaxToggles = config.getInt(basePath + "flip.max-toggles", 4);

        // 子项八: ElytraNoFall
        this.noFallSubCheck.reload(
                config.getBoolean(basePath + "nofall.enabled", true),
                config.getInt(basePath + "nofall.min-vl", 3),
                config.getString(basePath + "nofall.setback-mode", "HARD")
        );
        this.noFallMinFallDistance = (float) config.getDouble(basePath + "nofall.min-fall-distance", 3.0);
        this.noFallGroundProximityDistance = config.getDouble(basePath + "nofall.ground-proximity-distance", 1.8);
    }

    // ==============================================================================
    // 核心移动事件入口 (PlayerMoveEvent)
    // ==============================================================================

    /**
     * 处理滑翔状态下的核心移动判定
     */
    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        ElytraData elytraData = data.getElytraData();

        // 极限性能优化：非滑翔玩家在第一行立即返回，零计算损耗
        if (!player.isGliding() && !elytraData.isGliding()) {
            return;
        }

        // 创造/旁观、允许原版飞行或乘坐载具豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) {
            elytraData.resetGlideState();
            return;
        }

        // 回退自免疫期保护
        if (data.isSetbackImmune()) {
            return;
        }

        // 服务器负载与卡顿保护：当 TPS 低于容忍阈值时，整体暂停移动检测，防止由于服务端卡顿批处理引发误判
        if (getRecentTps() < minServerTps) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            return;
        }

        long now = System.currentTimeMillis();

        // 检查 Paper 自动落地停止滑翔的合法边界
        if (!player.isGliding() && elytraData.isGliding()) {
            if (to.clone().subtract(0, 0.1, 0).getBlock().getType().isSolid() || to.clone().subtract(0, 0.2, 0).getBlock().getType().isSolid()) {
                elytraData.resetGlideState();
                return;
            }
        }

        // 首次进入滑翔状态时的状态同步
        if (player.isGliding() && !elytraData.isGliding()) {
            elytraData.setGliding(true);
            elytraData.setGlideStartTime(now);
            elytraData.setGlideTicks(0);
        }

        elytraData.incrementGlideTicks();

        // ==============================================================================
        // 子项五: ElytraGlideState (滑翔装备与耐久状态一致性检测)
        // 限频检测，避免每 tick 调用 getInventory()
        // ==============================================================================
        if (glideStateSubCheck.isEnabled()) {
            if (now - elytraData.getLastChestplateCheckTime() > 1000L || elytraData.getGlideTicks() == 1) {
                elytraData.setLastChestplateCheckTime(now);
                ItemStack chest = player.getInventory().getChestplate();
                if (chest == null || chest.getType() != Material.ELYTRA) {
                    flagSubCheck(player, data, glideStateSubCheck, 5,
                            "[ElytraGlideState] Spoofed glide: player is gliding without elytra chestplate", event);
                    player.setGliding(false);
                    elytraData.resetGlideState();
                    return;
                }
                if (glideStateCheckDurability && chest.getItemMeta() instanceof Damageable damageable) {
                    if (damageable.getDamage() >= Material.ELYTRA.getMaxDurability() - 1) {
                        flagSubCheck(player, data, glideStateSubCheck, 5,
                                "[ElytraGlideState] Spoofed glide: elytra durability is 0 (broken)", event);
                        player.setGliding(false);
                        elytraData.resetGlideState();
                        return;
                    }
                }
                elytraData.setElytraEquipped(true);
            }
        }

        // 计算当前 Tick 基础运动学参量
        double deltaX = to.getX() - from.getX();
        double deltaY = to.getY() - from.getY();
        double deltaZ = to.getZ() - from.getZ();
        double horizontalDistance = Math.hypot(deltaX, deltaZ);
        float pitch = to.getPitch();

        // 判断环境与推进状态
        boolean fireworkBoosted = (now - elytraData.getLastFireworkTime()) < fireworkBoostDurationMs;
        var adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        boolean riptideActive = (now - elytraData.getLastRiptideTime()) < riptideDurationMs || adapter.isRiptiding(player);
        boolean externalVelocityActive = (now - elytraData.getLastExternalVelocityTime()) < externalVelocityDurationMs
                || data.hasAnyLegalVelocity() || data.hasRecentVelocity(externalVelocityDurationMs);
        boolean inBubbleColumn = isInBubbleColumn(from) || isInBubbleColumn(to);
        boolean hasLevitationOrSlowFall = adapter.hasPotionEffect(player, com.example.argusac.compat.CompatPotionType.SLOW_FALLING)
                || adapter.hasPotionEffect(player, com.example.argusac.compat.CompatPotionType.LEVITATION);

        // ==============================================================================
        // 子项六: ElytraTimer (滑翔 Tick 速率异常检测)
        // ==============================================================================
        if (timerSubCheck.isEnabled()) {
            long lastMove = elytraData.getLastMoveTimestamp();
            if (lastMove > 0) {
                long interval = now - lastMove;
                elytraData.recordMoveInterval(interval);
                if (elytraData.getMoveIntervalSampleSize() >= timerSampleWindowSize) {
                    double avgInterval = elytraData.getAverageMoveInterval();
                    double tps = getRecentTps();
                    if (tps >= minServerTps && avgInterval < timerMinAverageIntervalMs) {
                        String details = String.format("[ElytraTimer] avgInterval=%.1fms (< %.1fms), tps=%.1f, samples=%d",
                                avgInterval, timerMinAverageIntervalMs, tps, timerSampleWindowSize);
                        flagSubCheck(player, data, timerSubCheck, 2, details, event);
                        elytraData.clearMoveIntervals();
                    }
                }
            }
            elytraData.setLastMoveTimestamp(now);
        }

        // ==============================================================================
        // 子项一: ElytraSpeed (滑翔速度上限)
        // ==============================================================================
        if (speedSubCheck.isEnabled() && !riptideActive && !externalVelocityActive) {
            double allowedSpeed;
            if (fireworkBoosted) {
                allowedSpeed = speedMaxBoosted;
            } else {
                allowedSpeed = speedMaxUnboosted;
                // 考虑俯冲角度 (pitch > 0 为低头俯冲，重力势能转化为水平速度)
                if (pitch > 0.0f) {
                    double diveBonus = Math.sin(Math.toRadians(pitch)) * speedPitchDiveMultiplier;
                    allowedSpeed += diveBonus;
                }
            }

            // 物理动量惯性补偿：俯冲拉平时因空气阻力 (~0.99/tick)，水平动量在 1-2 tick 内自然衰减
            double maxMomentumSpeed = elytraData.getLastHorizontalSpeed() * 0.992 + 0.05;

            if (horizontalDistance > allowedSpeed && horizontalDistance > maxMomentumSpeed) {
                double baseline = Math.max(allowedSpeed, maxMomentumSpeed);
                double ratio = horizontalDistance / baseline;
                int vlToAdd = (ratio >= 1.5) ? 3 : (ratio >= 1.2 ? 2 : 1);
                String details = String.format("[ElytraSpeed] speed=(%.2f,%.2f,%.2f)[len=%.2f], limit=%.2f, pitch=%.1f, fwBoost=%b, ticks=%d",
                        deltaX, deltaY, deltaZ, horizontalDistance, allowedSpeed, pitch, fireworkBoosted, elytraData.getGlideTicks());
                flagSubCheck(player, data, speedSubCheck, vlToAdd, details, event);
                if (hasSetbackOccurred(to, event.getTo())) {
                    elytraData.resetGlideState();
                    return;
                }
            }
        }

        // ==============================================================================
        // 子项二: ElytraAscend (无推进上升)
        // ==============================================================================
        if (ascendSubCheck.isEnabled()) {
            if (deltaY > ascendMaxVertical && !fireworkBoosted && !riptideActive && !externalVelocityActive
                    && !inBubbleColumn && !adapter.hasPotionEffect(player, com.example.argusac.compat.CompatPotionType.LEVITATION)) {
                // 原版物理仰角拉升爬升判定：前一 tick 拥有充沛初速度且当前水平速度正急剧衰减失速 (能量守恒)
                boolean lawfulPullUpClimb = elytraData.getLastHorizontalSpeed() > 0.75
                        && (horizontalDistance < elytraData.getLastHorizontalSpeed() - 0.02)
                        && elytraData.getAscendTicks() < 3;

                if (!lawfulPullUpClimb) {
                    elytraData.incrementAscendTicks();
                    if (elytraData.getAscendTicks() >= ascendTicksThreshold) {
                        String details = String.format("[ElytraAscend] deltaY=%.3f (> %.2f), ascendTicks=%d, speed=%.2f, pitch=%.1f, no-boost",
                                deltaY, ascendMaxVertical, elytraData.getAscendTicks(), horizontalDistance, pitch);
                        flagSubCheck(player, data, ascendSubCheck, 2, details, event);
                        if (hasSetbackOccurred(to, event.getTo())) {
                            elytraData.resetGlideState();
                            return;
                        }
                    }
                } else {
                    elytraData.setAscendTicks(0);
                }
            } else {
                elytraData.setAscendTicks(0);
            }
        }

        // ==============================================================================
        // 子项三: ElytraHover (空中悬停)
        // ==============================================================================
        if (hoverSubCheck.isEnabled()) {
            long glideDuration = now - elytraData.getGlideStartTime();
            // 起跳瞬间豁免、缓降/悬浮药水豁免、贴墙/方块碰撞豁免
            if (glideDuration >= takeoffGraceDurationMs && !hasLevitationOrSlowFall
                    && !isInLiquidOrClimbableOrNearWall(player, to) && !inBubbleColumn) {
                if (horizontalDistance < hoverMaxHorizontalSpeed && Math.abs(deltaY) < hoverMaxVerticalSpeed) {
                    elytraData.incrementHoverTicks();
                    if (elytraData.getHoverTicks() >= hoverTicksThreshold) {
                        String details = String.format("[ElytraHover] speed=%.3f, deltaY=%.3f, hoverTicks=%d, duration=%dms",
                                horizontalDistance, deltaY, elytraData.getHoverTicks(), glideDuration);
                        flagSubCheck(player, data, hoverSubCheck, 2, details, event);
                        if (hasSetbackOccurred(to, event.getTo())) {
                            elytraData.resetGlideState();
                            return;
                        }
                    }
                } else {
                    elytraData.setHoverTicks(0);
                }
            } else {
                elytraData.setHoverTicks(0);
            }
        }

        // ==============================================================================
        // 子项四: ElytraFirework (烟花消耗与实际加速不匹配)
        // ==============================================================================
        if (fireworkSubCheck.isEnabled() && !riptideActive && !externalVelocityActive) {
            double prevSpeed = elytraData.getLastHorizontalSpeed();
            // 1. 在平飞或仰角状态下持续正向异常加速 (当前与前一 tick 均无俯冲势能且无烟花推进)
            if (pitch <= 5.0f && elytraData.getLastPitch() <= 10.0f && !fireworkBoosted) {
                double accel = horizontalDistance - prevSpeed;
                if (accel > fireworkMinUnpropelledAccel && horizontalDistance > 0.85) {
                    String details = String.format("[ElytraFirework] Unpropelled acceleration in flat flight: accel=+%.2f, speed=%.2f, pitch=%.1f",
                            accel, horizontalDistance, pitch);
                    flagSubCheck(player, data, fireworkSubCheck, 2, details, event);
                    if (hasSetbackOccurred(to, event.getTo())) {
                        elytraData.resetGlideState();
                        return;
                    }
                }
            }

            // 2. 反向检测：滑翔中长期从未使用烟花，但水平速度持续维持在上限附近
            if (!fireworkBoosted && horizontalDistance >= 1.30 && pitch <= 10.0f) {
                elytraData.incrementConsecutiveHighSpeedTicks();
                if (elytraData.getConsecutiveHighSpeedTicks() >= fireworkContinuousHighSpeedTicks) {
                    String details = String.format("[ElytraFirework] Sustained max speed without firework: ticks=%d, speed=%.2f, pitch=%.1f",
                            elytraData.getConsecutiveHighSpeedTicks(), horizontalDistance, pitch);
                    flagSubCheck(player, data, fireworkSubCheck, 2, details, event);
                    if (hasSetbackOccurred(to, event.getTo())) {
                        elytraData.resetGlideState();
                        return;
                    }
                }
            } else {
                elytraData.setConsecutiveHighSpeedTicks(0);
            }
        }

        // 更新上一 Tick 运动学数据
        elytraData.setLastHorizontalSpeed(horizontalDistance);
        elytraData.setLastDeltaY(deltaY);
        elytraData.setLastPitch(pitch);
        elytraData.setLastLocation(to.clone());
        elytraData.setLastVelocity(new Vector(deltaX, deltaY, deltaZ));
    }

    // ==============================================================================
    // 状态切换与辅助事件接入
    // ==============================================================================

    /**
     * 监听滑翔状态切换事件 (EntityToggleGlideEvent)
     */
    public void handleToggleGlide(Player player, PlayerData data, EntityToggleGlideEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        // 创造/旁观、允许飞行或处于载具豁免
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()) {
            return;
        }

        ElytraData elytraData = data.getElytraData();
        boolean willGlide = event.isGliding();
        long now = System.currentTimeMillis();

        if (willGlide) {
            // 校验鞘翅装备
            ItemStack chest = player.getInventory().getChestplate();
            if (chest == null || chest.getType() != Material.ELYTRA) {
                flagSubCheck(player, data, glideStateSubCheck, 5,
                        "[ElytraGlideState] Toggle rejected: no elytra equipped", null);
                event.setCancelled(true);
                elytraData.resetGlideState();
                return;
            }
            if (glideStateCheckDurability && chest.getItemMeta() instanceof Damageable damageable) {
                if (damageable.getDamage() >= Material.ELYTRA.getMaxDurability() - 1) {
                    flagSubCheck(player, data, glideStateSubCheck, 5,
                            "[ElytraGlideState] Toggle rejected: elytra broken (0 durability)", null);
                    event.setCancelled(true);
                    elytraData.resetGlideState();
                    return;
                }
            }

            // 子项八: ElytraNoFall (触地瞬间频繁开关滑翔免摔滥用)
            if (noFallSubCheck.isEnabled()) {
                float fallDist = player.getFallDistance();
                if (fallDist >= noFallMinFallDistance) {
                    double distToGround = getDistanceToGround(player.getLocation());
                    if (distToGround <= noFallGroundProximityDistance) {
                        int attempts = elytraData.recordLowYToggle(8000L);
                        // 首次临地开鞘翅属于合法极限自救 (MLG Elytra)，仅对 8 秒内反复/连续开关刷免摔判定作弊
                        if (attempts >= 2) {
                            String details = String.format("[ElytraNoFall] Repeated glide toggles near ground: attempts=%d, dist=%.2fb, fallDist=%.1f",
                                    attempts, distToGround, fallDist);
                            flagSubCheck(player, data, noFallSubCheck, 2, details, null);
                        }
                    }
                }
            }

            elytraData.setGliding(true);
            elytraData.setGlideStartTime(now);
            elytraData.setGlideTicks(0);
            elytraData.setElytraEquipped(true);
            elytraData.setLastChestplateCheckTime(now);
        } else {
            elytraData.setGliding(false);
            elytraData.resetGlideState();
        }

        // 子项七: ElytraFlip (短时间内滑翔状态高频切换检测)
        if (flipSubCheck.isEnabled()) {
            elytraData.recordToggle(now, player.getLocation());
            int toggleCount = elytraData.getToggleCountInWindow(flipWindowMs);
            if (toggleCount > flipMaxToggles) {
                String details = String.format("[ElytraFlip] High frequency glide toggle: %d toggles in %dms (max=%d)",
                        toggleCount, flipWindowMs, flipMaxToggles);
                flagSubCheck(player, data, flipSubCheck, 3, details, null);
                elytraData.clearToggleTimestamps();
                elytraData.resetGlideState();
            }
        }
    }

    /**
     * 监听烟花使用 (PlayerInteractEvent - 支持主手与副手)
     */
    public void handleInteract(Player player, PlayerData data, PlayerInteractEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean isFirework = (item != null && item.getType() == Material.FIREWORK_ROCKET)
                || (mainHand != null && mainHand.getType() == Material.FIREWORK_ROCKET)
                || (offHand != null && offHand.getType() == Material.FIREWORK_ROCKET);

        if (isFirework) {
            if (player.isGliding() || data.isElytraGliding()) {
                long now = System.currentTimeMillis();
                data.getElytraData().setLastFireworkTime(now);
                data.getElytraData().setLastFireworkLocation(player.getLocation().clone());
                data.grantLegalVelocity(VelocitySourceType.FIREWORK, fireworkBoostDurationMs);
                data.getElytraData().setAscendTicks(0);
            }
        }
    }

    /**
     * 监听外部受力击退 (PlayerVelocityEvent)
     */
    public void handleVelocity(Player player, PlayerData data, PlayerVelocityEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }
        Vector v = event.getVelocity();
        if (v.lengthSquared() > 0.04) {
            data.getElytraData().setLastExternalVelocityTime(System.currentTimeMillis());
            data.grantLegalVelocity(VelocitySourceType.PISTON, externalVelocityDurationMs);
        }
    }

    /**
     * 监听爆炸、风弹及外部伤害 (EntityDamageEvent)
     */
    public void handleDamage(Player player, PlayerData data, EntityDamageEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }
        EntityDamageEvent.DamageCause cause = event.getCause();
        String causeName = cause.name();
        // 兼容 1.20.4 与 1.21+ 的爆炸、风弹 (WIND_CHARGE / BREEZE) 及雷击等外部推力源
        if (causeName.contains("EXPLOSION") || causeName.contains("WIND") || causeName.contains("CHARGE") || causeName.contains("LIGHTNING")) {
            data.getElytraData().setLastExternalVelocityTime(System.currentTimeMillis());
            data.grantLegalVelocity(VelocitySourceType.EXPLOSION, externalVelocityDurationMs);
        }
    }

    // ==============================================================================
    // 违规裁决与回退接入
    // ==============================================================================

    /**
     * 统一调用 ViolationManager 处理各子项判定与回退
     */
    private void flagSubCheck(Player player, PlayerData data, ElytraSubCheck subCheck, int vl, String details, PlayerMoveEvent moveEvent) {
        if (player == null || !player.isOnline()) {
            return;
        }

        // 规范日志字段：拼接穿戴状态、滑翔 tick 及最近烟花时间差
        String fullDetails = details;
        if (data != null) {
            ElytraData ed = data.getElytraData();
            long now = System.currentTimeMillis();
            long fwAgo = ed.getLastFireworkTime() > 0 ? (now - ed.getLastFireworkTime()) : -1L;
            String fwStr = fwAgo >= 0 ? (fwAgo + "ms ago") : "none";
            fullDetails = String.format("%s, elytra=%b, fw=%s", details, ed.isElytraEquipped(), fwStr);
        }

        // 调度至 ViolationManager 进行全局裁决
        plugin.getViolationManager().handleViolation(player, subCheck, vl, fullDetails, moveEvent);

        // 回退成功后重置滑翔状态数据，防止二次误报
        if (data != null && data.isSetbackImmune()) {
            data.getElytraData().resetGlideState();
        }
    }

    private boolean hasSetbackOccurred(Location origTo, Location currentTo) {
        if (currentTo == null) {
            return true;
        }
        return origTo.getX() != currentTo.getX()
                || origTo.getY() != currentTo.getY()
                || origTo.getZ() != currentTo.getZ();
    }

    // ==============================================================================
    // 环境物理采样辅助方法
    // ==============================================================================

    private boolean isInBubbleColumn(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        for (int y = 0; y <= 3; y++) {
            Block b = loc.clone().subtract(0, y, 0).getBlock();
            if (b.getType() == Material.BUBBLE_COLUMN || b.getType() == Material.SOUL_SAND) {
                return true;
            }
        }
        return false;
    }

    private boolean isInLiquidOrClimbableOrNearWall(Player player, Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        Block b = loc.getBlock();
        if (b.isLiquid()) {
            return true;
        }
        com.example.argusac.compat.VersionAdapter adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        if (adapter.isClimbable(b)) {
            return true;
        }
        String mName = b.getType().name();
        if (mName.contains("VINE") || mName.contains("BERRY") || mName.contains("BUBBLE") || mName.contains("POWDER_SNOW")) {
            return true;
        }
        // 检查脚下方块是否为支撑方块 (避免落脚在栅栏、台阶、地毯上误判悬停)
        Block feet = loc.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isStandable(feet)) {
            return true;
        }
        for (double dx = -0.4; dx <= 0.4; dx += 0.8) {
            for (double dz = -0.4; dz <= 0.4; dz += 0.8) {
                Block side = loc.clone().add(dx, 0, dz).getBlock();
                if (side.getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private double getDistanceToGround(Location loc) {
        if (loc == null || loc.getWorld() == null) {
            return 10.0;
        }
        Location check = loc.clone();
        for (double y = 0.0; y <= 3.0; y += 0.25) {
            Block b = check.clone().subtract(0, y, 0).getBlock();
            if (b.getType().isSolid()) {
                return y;
            }
        }
        return 10.0;
    }

    private double getRecentTps() {
        try {
            double[] tps = com.example.argusac.compat.VersionAdapterFactory.getAdapter().getTPS();
            if (tps != null && tps.length > 0) {
                return Math.min(20.0, Math.max(0.0, tps[0]));
            }
        } catch (Throwable ignored) {
        }
        return 20.0;
    }

    // ==============================================================================
    // 子检测项 Check 实现实体 (ElytraSubCheck)
    // 承载每个子项独立的回退模式与 min-vl 门槛
    // ==============================================================================

    public static class ElytraSubCheck implements Check {
        private final ElytraFlyCheck parent;
        private final String subName;
        private boolean enabled = true;
        private int minVl = 3;
        private String setbackMode = "HARD";

        public ElytraSubCheck(ElytraFlyCheck parent, String subName) {
            this.parent = parent;
            this.subName = subName;
        }

        public void reload(boolean enabled, int minVl, String setbackMode) {
            this.enabled = enabled;
            this.minVl = minVl;
            this.setbackMode = setbackMode != null ? setbackMode.toUpperCase() : "HARD";
        }

        public String getSubName() {
            return subName;
        }

        @Override
        public String getName() {
            // 保持主检测项唯一名 ElytraFly，使 VL、告警、日志归并集中管控
            return parent.getName();
        }

        @Override
        public CheckType getType() {
            return parent.getType();
        }

        @Override
        public boolean isEnabled() {
            return parent.isEnabled() && this.enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public int getMaxVl() {
            return parent.getMaxVl();
        }

        @Override
        public boolean isSetbackEnabled() {
            return parent.isSetbackEnabled() && this.enabled;
        }

        @Override
        public int getSetbackMinVl() {
            return minVl;
        }

        @Override
        public String getSetbackMode() {
            return setbackMode;
        }

        @Override
        public void reloadConfig() {
            parent.reloadConfig();
        }
    }

    // ==================== Typed Getters ====================

    public ElytraSubCheck getSpeedSubCheck() { return speedSubCheck; }
    public ElytraSubCheck getAscendSubCheck() { return ascendSubCheck; }
    public ElytraSubCheck getHoverSubCheck() { return hoverSubCheck; }
    public ElytraSubCheck getFireworkSubCheck() { return fireworkSubCheck; }
    public ElytraSubCheck getGlideStateSubCheck() { return glideStateSubCheck; }
    public ElytraSubCheck getTimerSubCheck() { return timerSubCheck; }
    public ElytraSubCheck getFlipSubCheck() { return flipSubCheck; }
    public ElytraSubCheck getNoFallSubCheck() { return noFallSubCheck; }
}
