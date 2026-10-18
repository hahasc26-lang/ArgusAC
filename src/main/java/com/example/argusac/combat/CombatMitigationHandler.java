package com.example.argusac.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独立战斗交互与伤害拦截处理器 (CombatMitigationHandler)
 * 职责：
 * 1. 在 Bukkit 伤害事件与网络层数据包双重阻断非法攻击交互
 * 2. 纯静默取消非法伤害，将伤害值归零，消灭击退、耐久与横扫扩散
 * 3. 维护微秒级阻断时效，杜绝同一 tick 内多重事件与数据包穿透漏网
 * 4. 彻底脱耦侵入式武器蓄力清零与战斗抑制，保障纯净 PVP 战斗手感
 */
public class CombatMitigationHandler {

    private final ArgusACPlugin plugin;

    // 记录各玩家最近一次被拦截攻击的时间戳 (毫秒)
    private final Map<UUID, Long> recentCancelledAttacks = new ConcurrentHashMap<>();

    // 同 tick 连续发包与事件微抑制窗口 (50ms，相当于 1 个完整服务端 tick)
    private static final long PACKET_SUPPRESSION_WINDOW_MS = 50L;

    public CombatMitigationHandler(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 核心处置入口：即时取消非法近战/交互伤害事件并记录违规
     *
     * @param event Bukkit 实体受损事件
     * @param attacker 攻击发起方
     * @param victim 受害实体
     * @param check 触发违规的检测模块
     * @param vl 违规等级递增量
     * @param reason 详细违规原因
     */
    public void mitigateDamage(EntityDamageByEntityEvent event, Player attacker, Entity victim,
                               AbstractCheck check, int vl, String reason) {
        if (event == null || attacker == null) {
            return;
        }

        // 1. 当帧直接取消伤害事件
        event.setCancelled(true);

        // 2. 伤害双重归零防护，防止个别其它第三方插件强行 setCancelled(false) 导致伤害击穿
        try {
            event.setDamage(0.0);
        } catch (Throwable ignored) {}

        // 3. 记录拦截时间戳，使同 tick 内的关联网络包与重入事件受到双重阻断
        recentCancelledAttacks.put(attacker.getUniqueId(), System.currentTimeMillis());

        // 4. 递交违规管理器 (累加 VL、触发管理告警、异步防篡改审计)
        // 遵循纯静默规则：不向作弊者发送任何 ActionBar 弹窗或嘲弄音效
        if (check != null && plugin.getViolationManager() != null) {
            plugin.getViolationManager().handleViolation(attacker, check, vl, reason, null);
        }
    }

    /**
     * 底层网络数据包级攻击包拦截处理 (PacketEvents Play.Client.INTERACT_ENTITY)
     *
     * @param attacker 发包攻击玩家
     * @param data 玩家运行时状态
     * @param targetEntityId 目标实体 ID
     * @param event 网络包接收事件
     * @return 是否已拦截并取消该攻击包
     */
    public boolean handlePacketAttack(Player attacker, PlayerData data, int targetEntityId, PacketReceiveEvent event) {
        if (attacker == null || event == null) {
            return false;
        }

        // 1. 死亡状态发包攻击拦截
        if (attacker.isDead() || attacker.getHealth() <= 0.0) {
            event.setCancelled(true);
            return true;
        }

        // 2. 攻击自身漏洞拦截
        if (attacker.getEntityId() == targetEntityId) {
            event.setCancelled(true);
            return true;
        }

        // 3. 检查玩家是否处于同 tick 拦截微窗口内
        if (isAttackSuppressed(attacker)) {
            event.setCancelled(true);
            return true;
        }

        return false;
    }

    /**
     * 判断玩家当前是否处于攻击抑制微窗口 (在最近 50ms 内已被判定为违规攻击)
     */
    public boolean isAttackSuppressed(Player attacker) {
        if (attacker == null) {
            return false;
        }
        Long lastCancel = recentCancelledAttacks.get(attacker.getUniqueId());
        if (lastCancel == null) {
            return false;
        }
        if (System.currentTimeMillis() - lastCancel < PACKET_SUPPRESSION_WINDOW_MS) {
            return true;
        } else {
            recentCancelledAttacks.remove(attacker.getUniqueId());
            return false;
        }
    }

    /**
     * 手动对玩家施加指定时长的攻击抑制
     */
    public void suppressAttack(Player attacker, long durationMs) {
        if (attacker != null) {
            recentCancelledAttacks.put(attacker.getUniqueId(), System.currentTimeMillis() + durationMs);
        }
    }

    /**
     * 玩家注销或离线时释放缓存
     */
    public void clearPlayer(UUID uuid) {
        if (uuid != null) {
            recentCancelledAttacks.remove(uuid);
        }
    }

    /**
     * 清空所有缓存
     */
    public void clearAll() {
        recentCancelledAttacks.clear();
    }
}
