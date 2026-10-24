package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * 快速射箭与加特林弓外挂检测 (FastBowCheck)
 * <p>
 * 深度针对 FastBow 外挂的三大核心机制进行确定性拦截：
 * 1. 服务端 Tick 级蓄力计时器 (Server-side Charge Tick Counter)：
 *    原版弓箭拉满蓄力需要严格的 20 ticks (1000ms)，系统在客户端举弓交互时记录起始 Tick 与时间戳，
 *    在箭矢发射时校验蓄力时长，放宽 1~2 ticks 网络抖动容差 (默认 >= 18 ticks / 850ms)，
 *    对瞬发箭或蓄力严重不足的满速箭予以即时阻断。
 * 2. 结合事务机制与滑动窗口防御延迟堆包 (Transaction / Ping-based Validation & Anti-Burst)：
 *    防御作弊端通过 FakeLag / Blink 制造断网假象后瞬间释放海量箭矢的包轰炸 (Packet Spoil)，
 *    对 1000ms 滑动窗口内的满速箭释放频次设置物理上限 (默认 <= 3 支/秒)。
 * 3. 箭矢射击冷却与频率限制 (Shot Rate Limiting)：
 *    维护全局射箭冷却窗，强制两箭之间的绝对物理间歇 (默认 >= 250ms)。
 */
public class FastBowCheck extends AbstractCheck {

    private int minChargeTicks;
    private long minChargeMs;
    private long minShotIntervalMs;
    private int maxShotsPerSecond;
    private String mitigationMode;

    public FastBowCheck(ArgusACPlugin plugin) {
        super(plugin, "FastBow", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.minChargeTicks = getChecksConfig().getInt(path + "min-charge-ticks", 18);
        this.minChargeMs = getChecksConfig().getLong(path + "min-charge-ms", 850L);
        this.minShotIntervalMs = getChecksConfig().getLong(path + "min-shot-interval-ms", 250L);
        this.maxShotsPerSecond = getChecksConfig().getInt(path + "max-shots-per-second", 3);
        this.mitigationMode = getChecksConfig().getString(path + "mitigation-mode", "CANCEL").toUpperCase();
        if (!"NERF".equals(this.mitigationMode) && !"CANCEL".equals(this.mitigationMode)) {
            this.mitigationMode = "CANCEL";
        }
    }

    /**
     * 玩家右键举弓拉弦开始蓄力打点
     */
    public void handleBowStart(Player player, ItemStack item) {
        if (!enabled || player == null || item == null) {
            return;
        }

        String itemType = item.getType().name();
        if (!itemType.equals("BOW") && !itemType.equals("CROSSBOW")) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        long currentTick = getCurrentServerTick();
        long now = System.currentTimeMillis();
        data.startBowCharge(currentTick, now);
    }

    /**
     * 玩家松开弓弦释放箭矢 (EntityShootBowEvent) 核心核验流水线
     */
    public void handleShootBow(Player player, EntityShootBowEvent event) {
        if (!enabled || player == null || event == null) {
            return;
        }

        if (plugin.isPlayerExempt(player)) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        long currentTick = getCurrentServerTick();
        long now = System.currentTimeMillis();

        // 1. 全局射击间隔硬限制 (Shot Rate Limiting)
        long lastShotTime = data.getLastBowShotTime();
        if (lastShotTime > 0) {
            long shotInterval = now - lastShotTime;
            if (shotInterval < minShotIntervalMs) {
                flagViolation(player, data, event, 3,
                        String.format("Gatling Rapid Fire: interval=%dms (min=%dms)", shotInterval, minShotIntervalMs));
                return;
            }
        }

        // 2. 滑动窗口包轰炸与延迟堆包拦截 (Transaction / Ping-based Burst Validation)
        int shotsInWindow = data.recordBowShot(currentTick, now);
        if (shotsInWindow > maxShotsPerSecond) {
            flagViolation(player, data, event, 4,
                    String.format("Packet Spoil Burst: %d shots in 1000ms window (max=%d)", shotsInWindow, maxShotsPerSecond));
            return;
        }

        // 3. 服务端 Tick 级蓄力计时器检验 (Server-side Charge Tick Counter)
        long startTick = data.getBowChargeStartTick();
        long startTimeMs = data.getBowChargeStartTimeMs();
        float force = event.getForce();
        ItemStack bow = event.getBow();

        int expectedTicks = minChargeTicks;
        long expectedMs = minChargeMs;

        // 兼容 Crossbow 快速装填 (Quick Charge) 附魔缩短蓄力时间
        if (bow != null && bow.getType().name().equals("CROSSBOW")) {
            int quickChargeLevel = getQuickChargeLevel(bow);
            // 弩基础装填 25 ticks (1250ms)，每级快速装填减少 5 ticks (250ms)
            expectedTicks = Math.max(8, 25 - (quickChargeLevel * 5) - 2);
            expectedMs = Math.max(400L, 1250L - (quickChargeLevel * 250L) - 100L);
        } else {
            // 普通弓根据释放力度 (Force) 进行线性比例降级检验
            if (force < 0.8f) {
                expectedTicks = Math.max(3, Math.round(force * minChargeTicks));
                expectedMs = Math.max(150L, Math.round(force * minChargeMs));
            }
        }

        boolean insufficientCharge = false;
        long elapsedTicks = -1;
        long elapsedMs = -1;

        if (startTick <= 0 || startTimeMs <= 0) {
            // 从未经过右键举弓蓄力直接发包射出 (普通弓 No-Draw / Instant Release Spoof)
            // 注意：预先填装好的弩 (Crossbow) 可随时瞬间发包射击，由全局冷却与滑动窗口限制其连发频次，豁免 charge 0 打点
            boolean isCrossbow = (bow != null && bow.getType().name().equals("CROSSBOW"));
            if (force >= 0.5f && !isCrossbow) {
                insufficientCharge = true;
            }
        } else {
            elapsedTicks = Math.max(0, currentTick - startTick);
            elapsedMs = Math.max(0, now - startTimeMs);

            // 当蓄力 Tick 数与真实耗时均低于下限且射击力度较大时，判定为 FastBow
            if (elapsedTicks < expectedTicks && elapsedMs < expectedMs) {
                insufficientCharge = true;
            }
        }

        if (insufficientCharge) {
            flagViolation(player, data, event, 3,
                    String.format("Fast Charge: force=%.2f, elapsed=%dt/%dms (req=%dt/%dms)",
                            force, elapsedTicks, elapsedMs, expectedTicks, expectedMs));
        }

        // 射击完成，重置当前举弓蓄力标记
        data.stopBowCharge();
    }

    private void flagViolation(Player player, PlayerData data, EntityShootBowEvent event, int vl, String details) {
        flag(player, vl, details);

        // 执行防御处置策略 (仅保留 CANCEL 与 NERF 两种模式)
        if ("NERF".equalsIgnoreCase(mitigationMode)) {
            // 软绵箭：强行将箭矢初速度削减归零，直接垂直掉落在脚下
            Entity projectile = event.getProjectile();
            if (projectile != null) {
                projectile.setVelocity(new Vector(0, -0.05, 0));
            }
        } else {
            // 默认 CANCEL: 静默拦截箭矢生成并清除实体，彻底阻断加特林连发
            event.setCancelled(true);
            Entity projectile = event.getProjectile();
            if (projectile != null) {
                projectile.remove();
            }
        }
    }

    private long getCurrentServerTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable t) {
            return System.currentTimeMillis() / 50L;
        }
    }

    @SuppressWarnings("deprecation")
    private int getQuickChargeLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        for (java.util.Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            if (entry.getKey() != null) {
                String enchName = entry.getKey().getName();
                if (enchName != null && enchName.toUpperCase().contains("QUICK_CHARGE")) {
                    return entry.getValue();
                }
            }
        }
        return 0;
    }
}
