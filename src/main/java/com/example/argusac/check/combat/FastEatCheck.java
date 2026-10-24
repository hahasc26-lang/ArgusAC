package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 快速进食与异常物品使用检测 (FastEatCheck)
 * <p>
 * 深度针对 FastEat 外挂的核心欺骗机制进行确定性拦截：
 * 1. 服务端 Tick 级与毫秒时间差独立计时器 (Server-side Item Consume Counter)：
 *    原版食用标准物品需要严格的 32 ticks (1600ms)，系统在客户端右键交互使用物品时记录起始 Tick 与时间戳，
 *    在完成食用 (PlayerItemConsumeEvent) 时校验完整时长，放宽网络与帧率抖动容忍下限 (默认 >= 28 ticks / 1200ms)。
 * 2. 伪造完成数据包与 Instant Eat 拦截 (Packet Spoofing Defense)：
 *    作弊客户端未经过右键举物品交互即瞬间上报完成食用事件时，直接判定为无合法交互记录的 Instant Eat 并即时阻断。
 * 3. 动态 RTT 延迟补偿 (Ping-adaptive Lag Compensation)：
 *    结合玩家网络延迟 (Ping / 2) 动态放宽毫秒与 Tick 阈值，排除高 Ping 延迟堆积引起的误报。
 * 4. 客户端物品状态强制重置与同步 (Inventory & Slot Sync)：
 *    检测到 FastEat 违规时当帧取消事件并刷写客户端物品状态，防止产生“已消耗但无效果”的幽灵物品错乱。
 */
public class FastEatCheck extends AbstractCheck {

    private int minAllowedTicks;
    private long minAllowedMs;
    private boolean lagCompensation;
    private String mitigationMode;

    public FastEatCheck(ArgusACPlugin plugin) {
        super(plugin, "FastEat", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.minAllowedTicks = getChecksConfig().getInt(path + "min-allowed-ticks", 28);
        this.minAllowedMs = getChecksConfig().getLong(path + "min-allowed-ms", 1200L);
        this.lagCompensation = getChecksConfig().getBoolean(path + "lag-compensation", true);
        this.mitigationMode = getChecksConfig().getString(path + "mitigation-mode", "CANCEL").toUpperCase();
        if (!"CANCEL".equals(this.mitigationMode)) {
            this.mitigationMode = "CANCEL";
        }
    }

    /**
     * 玩家右键点击手持可食用/使用物品开始交互打点
     */
    public void handleItemStartUse(Player player, ItemStack item) {
        if (!enabled || player == null || item == null) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        if (!isConsumable(item)) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        long currentTick = getCurrentServerTick();
        long nowMs = System.currentTimeMillis();
        data.startEating(currentTick, nowMs, item.getType().name());
    }

    /**
     * 玩家切换手置物品、松开右键或被打断时重置进食状态
     */
    public void handleItemStopUse(Player player) {
        if (player == null) {
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.stopEating();
        }
    }

    /**
     * 玩家完成物品食用事件 (PlayerItemConsumeEvent) 核心核验流水线
     */
    public void handleItemConsume(Player player, PlayerItemConsumeEvent event) {
        if (!enabled || player == null || event == null) {
            return;
        }

        if (plugin.isPlayerExempt(player)) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null || !isConsumable(item)) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        long currentTick = getCurrentServerTick();
        long nowMs = System.currentTimeMillis();

        long startTick = data.getEatStartTick();
        long startTimeMs = data.getEatStartTimeMs();

        // 1. 无合法交互记录判定 (Instant Eat / Packet Spoofing)
        if (!data.isEating() || startTick <= 0 || startTimeMs <= 0) {
            flagViolation(player, data, event, 3,
                    String.format("Instant Consume without start interaction record (Spoof), item=%s", item.getType().name()));
            data.stopEating();
            return;
        }

        long elapsedTicks = Math.max(0, currentTick - startTick);
        long elapsedMs = Math.max(0, nowMs - startTimeMs);

        // 获取原版该物品的标准食用耗时
        Material mat = item.getType();
        int baseTicks = getExpectedItemTicks(mat);
        long baseMs = getExpectedItemMs(mat);

        // 计算此物品类型按配置比例放宽后的最小要求阈值
        float tickRatio = (float) minAllowedTicks / 32.0f;
        float msRatio = (float) minAllowedMs / 1600.0f;
        int expectedMinTicks = Math.max(4, Math.round(baseTicks * tickRatio));
        long expectedMinMs = Math.max(200L, Math.round(baseMs * msRatio));

        // 2. 动态 RTT 延迟补偿 (Ping-adaptive Lag Compensation)
        int adjustedMinTicks = expectedMinTicks;
        long adjustedMinMs = expectedMinMs;
        int ping = 0;

        if (lagCompensation) {
            ping = VersionAdapterFactory.getAdapter().getPing(player);
            int pingBufferMs = Math.min(300, Math.max(0, ping / 2));
            int pingBufferTicks = (int) Math.ceil(pingBufferMs / 50.0);
            adjustedMinTicks = Math.max(4, expectedMinTicks - pingBufferTicks);
            adjustedMinMs = Math.max(200L, expectedMinMs - pingBufferMs);
        }

        // 3. 时间差与 Tick 校验 (FastEat Flag)
        if (elapsedTicks < adjustedMinTicks || elapsedMs < adjustedMinMs) {
            flagViolation(player, data, event, 2,
                    String.format("FastEat: elapsed=%dt/%dms (req=%dt/%dms), item=%s, ping=%dms",
                            elapsedTicks, elapsedMs, adjustedMinTicks, adjustedMinMs, mat.name(), ping));
        }

        // 校验完毕，清除本次进食打点状态
        data.stopEating();
    }

    private void flagViolation(Player player, PlayerData data, PlayerItemConsumeEvent event, int vl, String details) {
        flag(player, vl, details);

        // 静默取消食用事件并刷写手持物品同步，防客户端虚假消耗
        event.setCancelled(true);
        syncPlayerItemHand(player);
    }

    private void syncPlayerItemHand(Player player) {
        try {
            player.updateInventory();
        } catch (Throwable ignored) {}
    }

    public boolean isConsumable(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        Material type = item.getType();
        if (type.isEdible()) {
            return true;
        }
        String name = type.name();
        return name.contains("POTION") || name.contains("MILK_BUCKET") || name.contains("HONEY_BOTTLE")
                || name.contains("SUSPICIOUS_STEW") || name.contains("OMINOUS_BOTTLE");
    }

    private int getExpectedItemTicks(Material material) {
        if (material == null) {
            return 32;
        }
        String name = material.name();
        if (name.equals("DRIED_KELP")) {
            return 16;
        }
        if (name.equals("HONEY_BOTTLE")) {
            return 40;
        }
        return 32;
    }

    private long getExpectedItemMs(Material material) {
        return getExpectedItemTicks(material) * 50L;
    }

    private long getCurrentServerTick() {
        try {
            return Bukkit.getCurrentTick();
        } catch (Throwable t) {
            return System.currentTimeMillis() / 50L;
        }
    }

    public long getMinAllowedMs() {
        return minAllowedMs;
    }

    public int getMinAllowedTicks() {
        return minAllowedTicks;
    }
}
