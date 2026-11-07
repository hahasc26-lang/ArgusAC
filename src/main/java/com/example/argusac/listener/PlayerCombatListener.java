package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import com.example.argusac.manager.CheckManager;
import com.example.argusac.model.VelocitySourceType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;

/**
 * 玩家战斗与伤害监听器 (PlayerCombatListener)
 * 统一调度攻击、受击、射箭、击退豁免及攻击作弊 (KillAura, Reach, Mace) 兜底检测。
 */
public class PlayerCombatListener implements Listener {

    private final ArgusACPlugin plugin;
    private final CheckManager checkManager;

    public PlayerCombatListener(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.checkManager = plugin.getCheckManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.setLastDamageTime(System.currentTimeMillis());
                data.recordDamageLocation(player.getLocation());
                // 受伤击退赋予合法速度豁免（覆盖高 Ping 玩家的 RTT + 击退抛物线周期）
                data.grantLegalVelocity(VelocitySourceType.EXPLOSION, 1500L);
                checkManager.getElytraFlyCheck().handleDamage(player, data, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        long now = System.currentTimeMillis();

        // 若受击方为玩家，立即将攻击源生物录入历史追踪器并赋予受击击退豁免，消除玩家受击反击时的延迟偏差误报
        if (event.getEntity() instanceof Player victimPlayer) {
            PlayerData victimData = plugin.getPlayerManager().getPlayerData(victimPlayer);
            if (victimData != null) {
                victimData.setLastDamageTime(now);
                victimData.recordDamageLocation(victimPlayer.getLocation());
                victimData.grantLegalVelocity(VelocitySourceType.ATTACK_KNOCKBACK, 1500L);
            }
            if (plugin.getHistoryTracker() != null && damager != null) {
                Entity trueDamager = damager;
                if (damager instanceof org.bukkit.entity.Projectile projectile) {
                    if (projectile.getShooter() instanceof Entity shooter) {
                        trueDamager = shooter;
                    }
                }
                if (trueDamager != null) {
                    plugin.getHistoryTracker().recordEntity(trueDamager, VersionAdapterFactory.getAdapter(), now);
                }
            }
        }

        if (!(damager instanceof Player attacker)) {
            return;
        }

        if (plugin.isPlayerExempt(attacker)) {
            return;
        }

        // 严格过滤所有非近战直接物理打击（如荆棘反伤 THORNS、远程抛射物 PROJECTILE、横扫 ENTITY_SWEEP_ATTACK、魔法 MAGIC 等）
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }

        // 伤害来源实体必须是攻击者自身
        if (event.getDamager() != attacker) {
            return;
        }

        // 第三阶段 Bukkit 层兜底防御 (Fallback)：
        // 正常情况下违规攻击已在 Netty 进包阶段 (PacketPlayInUseEntity) 被直接丢弃，本事件不应被触发；
        // 若因同 tick 多包穿透或外部插件直接构造事件触发至此，首先检查攻击者是否已处于抑制窗口
        if (plugin.getCombatMitigationHandler() != null
                && plugin.getCombatMitigationHandler().isAttackSuppressed(attacker)) {
            event.setCancelled(true);
            try {
                event.setDamage(0.0);
            } catch (Throwable ignored) {
            }
            return;
        }

        // 无论是否已在 Netty 初筛放行，主线程均将目标实体补录进 EntityHistoryTracker 索引缓存
        if (plugin.getHistoryTracker() != null && event.getEntity() != null) {
            plugin.getHistoryTracker().recordEntity(
                    event.getEntity(),
                    VersionAdapterFactory.getAdapter(),
                    now);
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(attacker);
        if (data != null && event.getEntity() != null) {
            // 若该次攻击已在 Netty 进包管道初筛放行，Bukkit 层无需二次重复核验，直接放行伤害
            if (data.isAttackRecentlyVerified(event.getEntity().getUniqueId(), 200L)) {
                return;
            }
        }

        // 仅在数据包协议管线未就绪时由 Bukkit 事件作为降级统计，杜绝网络层与主线程双重打点导致的 CPS 翻倍误报
        recordClickIfFallback(attacker);

        // 重锤下落伤害上限与最终伤害数值核验 (MaceDMG / Theoretical Damage Cap)
        checkManager.getMaceCheck().handleAttack(attacker, event.getEntity(), event);
        if (event.isCancelled()) {
            return;
        }

        // 当 Packet 管线未激活或目标实体此前未被缓存命中时，执行 KillAura 与 Reach 的 Bukkit 层兜底检测
        checkManager.getKillAuraCheck().handleAttack(attacker, event.getEntity(), event);
        if (event.isCancelled()) {
            return;
        }

        checkManager.getReachCheck().handleAttack(attacker, event.getEntity(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            checkManager.getFastBowCheck().handleShootBow(player, event);
        }
    }

    /**
     * 仅在 PacketEvents 底层数据包协议管线未就绪时，降级采用 Bukkit 事件统计 CPS
     */
    private void recordClickIfFallback(Player player) {
        if (plugin.getPacketPipelineManager() == null || !plugin.getPacketPipelineManager().isActive()) {
            checkManager.getAutoClickerCheck().handleClick(player);
        }
    }
}

