package com.example.argusac.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import com.example.argusac.manager.CheckManager;
import com.example.argusac.prediction.PlayerPhysicsState;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 独立战斗交互与数据包层拦截处理器 (CombatMitigationHandler)
 * <p>
 * 三阶段落地方案核心实现：
 * 1. 第一阶段（Netty/Packet 层进包拦截）：
 *    在接收到 PacketPlayInUseEntity / ServerboundInteractPacket (ATTACK) 的瞬间，
 *    同步调用核心检测（KillAura Angle/Raytrace、Reach 回溯、MaceDMG、AutoClicker），
 *    违规时直接 event.setCancelled(true) 丢弃该包，阻止其流向服务端主线程与 NMS 管线。
 * 2. 第二阶段（下游出包抑制与视觉状态清理）：
 *    - 拦截发往下游的 PacketPlayOutEntityStatus / ClientboundEntityEventPacket，抑制受害实体的受伤发红/抖动 (Status=2, 33, 36, 37) 与虚假死亡动画 (Status=3)；
 *    - 拦截攻击者派生的挥手动画广播包 (PacketPlayOutAnimation / ClientboundAnimatePacket)；
 *    - 拦截服务端因受击生成的命名音效包 (PacketPlayOutNamedSoundEffect / ClientboundSoundPacket)，消除虚假受击/暴击/横扫音效。
 * 3. 第三阶段（分级处置策略与 Bukkit 兜底防御）：
 *    - 低确信/微量超出：仅在底层静默 Drop 攻击包，不增加严重 VL，防止网络抖动误伤；
 *    - 高确信/明显作弊：累加 VL (VL += step)，若伴随移动预测异常则联动 SetbackManager 触发标准位置矫正，达到阈值自动踢出或封禁；
 *    - 保留 EntityDamageByEntityEvent 作为未命中缓存或外部插件兼容的最终兜底防线 (Fallback)。
 */
public class CombatMitigationHandler {

    private final ArgusACPlugin plugin;

    // 记录攻击者最近一次被拦截攻击的时间戳 (Key: Attacker UUID, Value: Timestamp ms)
    private final Map<UUID, Long> recentCancelledAttacks = new ConcurrentHashMap<>();

    // 记录攻击者实体 ID 最近一次被阻断的时间戳，用于快速抑制挥手广播包 (Key: Attacker EntityId, Value: Timestamp ms)
    private final Map<Integer, Long> suppressedAttackerEntityIds = new ConcurrentHashMap<>();

    // 记录受害者实体最近一次被违规攻击锁定的时间戳，用于抑制下游受伤状态包与音效 (Key: Victim EntityId, Value: Timestamp ms)
    private final Map<Integer, Long> suppressedVictimEntityIds = new ConcurrentHashMap<>();

    // 记录受害者受抑制时的位置快照，用于精准过滤周围的受击音效包 (Key: Victim EntityId, Value: Location)
    private final Map<Integer, Location> suppressedVictimLocations = new ConcurrentHashMap<>();

    // 同 tick 连续发包与下游出包抑制窗口 (75ms，覆盖完整 1~2 个服务端 tick 的网络包广播周期)
    private static final long PACKET_SUPPRESSION_WINDOW_MS = 75L;

    public CombatMitigationHandler(ArgusACPlugin plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    // 第一阶段：Netty 进包阶段合规性初筛与即时阻断 (Inbound Packet Interception)
    // =========================================================================

    /**
     * 底层网络数据包级攻击包拦截处理 (PacketEvents Play.Client.INTERACT_ENTITY - Action: ATTACK)
     *
     * @param attacker 发包攻击玩家
     * @param data 玩家运行时状态
     * @param targetEntityId 目标实体 ID
     * @param event 网络包接收事件
     * @return 是否已拦截并丢弃该攻击包
     */
    public boolean handlePacketAttack(Player attacker, PlayerData data, int targetEntityId, PacketReceiveEvent event) {
        if (attacker == null || event == null) {
            return false;
        }

        if (plugin.isPlayerExempt(attacker)) {
            return false;
        }

        // 1. 死亡状态发包攻击拦截
        if (attacker.isDead() || attacker.getHealth() <= 0.0) {
            dropPacketAndMarkSuppression(attacker, targetEntityId, null, event);
            return true;
        }

        // 2. 攻击自身漏洞拦截 (Self-Interact Exploit)
        if (attacker.getEntityId() == targetEntityId) {
            dropPacketAndMarkSuppression(attacker, targetEntityId, attacker.getLocation(), event);
            if (plugin.getCheckManager() != null && plugin.getCheckManager().getKillAuraCheck() != null) {
                escalateHighConfidence(attacker, data,
                        CombatVerdict.highConfidence(plugin.getCheckManager().getKillAuraCheck(), 3, "Self damage packet exploit"));
            }
            return true;
        }

        // 3. 检查玩家是否已处于同 tick 攻击抑制窗口内
        if (isAttackSuppressed(attacker)) {
            dropPacketAndMarkSuppression(attacker, targetEntityId, null, event);
            return true;
        }

        CheckManager checkManager = plugin.getCheckManager();
        if (checkManager == null || data == null) {
            return false;
        }

        // 4. 连点频率 (AutoClicker) 初筛判定
        if (checkManager.getAutoClickerCheck() != null) {
            CombatVerdict clickVerdict = checkManager.getAutoClickerCheck().evaluateClick(attacker);
            if (clickVerdict.isBlocked()) {
                dropPacketAndMarkSuppression(attacker, targetEntityId, attacker.getLocation(), event);
                return true;
            }
        }

        // 5. 解析目标实体，执行 KillAura (Angle / Raytrace / Snap / Switch)、Mace 与 Reach 回溯初筛
        Entity victim = (plugin.getHistoryTracker() != null)
                ? plugin.getHistoryTracker().resolveEntity(attacker.getWorld(), targetEntityId)
                : null;

        if (victim != null) {
            Location victimLoc = victim.getLocation();

            // 5.1 KillAura 多维矩阵检测 (Angle, Blindspot, Raytrace, Aimbot Snap, Multi-Target Switch)
            if (checkManager.getKillAuraCheck() != null) {
                CombatVerdict kaVerdict = checkManager.getKillAuraCheck().evaluateAttack(attacker, data, victim);
                if (kaVerdict.isBlocked()) {
                    executePacketMitigation(attacker, data, targetEntityId, victimLoc, event, kaVerdict);
                    return true;
                }
            }

            // 5.2 Reach 历史碰撞盒回溯超距检测
            if (checkManager.getReachCheck() != null) {
                CombatVerdict reachVerdict = checkManager.getReachCheck().evaluateAttack(attacker, data, victim);
                if (reachVerdict.isBlocked()) {
                    executePacketMitigation(attacker, data, targetEntityId, victimLoc, event, reachVerdict);
                    return true;
                }
            }

            // 5.3 Mace 重锤虚假下落高度与运动学约束检测
            if (checkManager.getMaceCheck() != null) {
                CombatVerdict maceVerdict = checkManager.getMaceCheck().evaluatePacketAttack(attacker, data, victim);
                if (maceVerdict.isBlocked()) {
                    executePacketMitigation(attacker, data, targetEntityId, victimLoc, event, maceVerdict);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 执行数据包层分级处置 (Phase 1 Drop + Phase 2 Suppression Marking + Phase 3 Escalation)
     */
    private void executePacketMitigation(Player attacker, PlayerData data, int targetEntityId,
                                         Location victimLoc, PacketReceiveEvent event, CombatVerdict verdict) {
        // 1. 直接在 Netty 管线丢弃该攻击包，阻止流向主线程与 NMS 伤害管线
        dropPacketAndMarkSuppression(attacker, targetEntityId, victimLoc, event);

        // 2. 分级处置策略 (Mitigation Escalation)
        if (verdict.isLowConfidenceDrop()) {
            // 低确信/微量超出：仅在底层静默丢弃 (Drop) 攻击包，不增加严重 VL，仅写入调试追踪
            if (plugin.getCheckDebugLogger() != null && verdict.getCheck() != null) {
                plugin.getCheckDebugLogger().log(verdict.getCheck().getName(), attacker.getName(),
                        "[SILENT_DROP] " + verdict.getReason());
            }
        } else if (verdict.isHighConfidence()) {
            // 高确信/明显作弊：记录 VL 累加、若伴随移动异常则联动位置矫正 (Setback)、达到阈值执行惩罚
            escalateHighConfidence(attacker, data, verdict);
        }
    }

    /**
     * 高确信违规升级处置：主线程安全地累加 VL、检查移动异常触发 Setback、达到阈值自动 Kick/Ban
     */
    private void escalateHighConfidence(Player attacker, PlayerData data, CombatVerdict verdict) {
        if (attacker == null || verdict == null || verdict.getCheck() == null) {
            return;
        }
        Runnable task = () -> {
            if (!attacker.isOnline()) {
                return;
            }
            if (plugin.getViolationManager() != null) {
                plugin.getViolationManager().handleViolation(
                        attacker,
                        verdict.getCheck(),
                        verdict.getVlStep(),
                        verdict.getReason(),
                        null
                );
            }

            // 若同时伴随移动异常（移动缓冲区累积异常、物理预测器残差偏高或处于空中违规状态），按移动预测引擎触发标准位置矫正 (Setback)
            if (data != null && plugin.getSetbackManager() != null && isAccompaniedByMovementAnomaly(data)) {
                AbstractCheck moveCheck = (plugin.getCheckManager() != null && plugin.getCheckManager().getSpeedCheck() != null)
                        ? plugin.getCheckManager().getSpeedCheck()
                        : verdict.getCheck();
                int currentVl = data.getVl(verdict.getCheck().getName());
                plugin.getSetbackManager().mitigate(
                        attacker,
                        moveCheck,
                        Math.max(1, currentVl),
                        "Combat violation accompanied by movement anomaly: " + verdict.getReason(),
                        null
                );
            }
        };

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * 判断攻击者当前是否同时伴随移动异常（结合移动预测引擎残差缓冲与速度缓冲）
     */
    private boolean isAccompaniedByMovementAnomaly(PlayerData data) {
        if (data == null) {
            return false;
        }
        if (data.getSpeedBuffer() > 0.05) {
            return true;
        }
        PlayerPhysicsState ps = data.getPhysicsState();
        if (ps != null) {
            if (ps.getHorizontalBuffer() > 0.35 || ps.getVerticalBuffer() > 0.35) {
                return true;
            }
        }
        return data.getFlatHoverTicks() >= 2 || data.getAntiGravityTicks() >= 2;
    }

    /**
     * 丢弃进包并登记攻击者与受害实体的下游抑制窗口
     */
    private void dropPacketAndMarkSuppression(Player attacker, int targetEntityId, Location victimLoc, PacketReceiveEvent event) {
        event.setCancelled(true);
        long now = System.currentTimeMillis();
        recentCancelledAttacks.put(attacker.getUniqueId(), now);
        suppressedAttackerEntityIds.put(attacker.getEntityId(), now);
        if (targetEntityId >= 0) {
            suppressedVictimEntityIds.put(targetEntityId, now);
            if (victimLoc != null) {
                suppressedVictimLocations.put(targetEntityId, victimLoc);
            } else if (attacker.getLocation() != null) {
                suppressedVictimLocations.put(targetEntityId, attacker.getLocation());
            }
        }
    }

    // =========================================================================
    // 第二阶段：出包抑制与视觉状态清理 (Outbound Suppression & Visual Cleanup)
    // =========================================================================

    /**
     * 监听并过滤发往下游客户端的视觉状态包、动画广播包与受击音效包
     *
     * @param event PacketEvents 出包事件
     */
    public void handleOutgoingPacketSuppression(PacketSendEvent event) {
        if (event == null || event.isCancelled()) {
            return;
        }

        // 1. 下行实体状态包过滤 (PacketPlayOutEntityStatus / ClientboundEntityEventPacket)
        // 确保目标实体不向任何客户端发送 Status = 2 (受伤发红/抖动) 及 Status = 3 (死亡动画)
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_STATUS) {
            try {
                WrapperPlayServerEntityStatus statusWrapper = new WrapperPlayServerEntityStatus(event);
                int entityId = statusWrapper.getEntityId();
                int status = statusWrapper.getStatus();
                // Status 2: 通用受伤发红抖动; Status 3: 死亡动画; 33/36/37: 荆棘/溺水/焚烧受击状态兼容
                if ((status == 2 || status == 3 || status == 33 || status == 36 || status == 37)
                        && isVictimEntitySuppressed(entityId)) {
                    event.setCancelled(true);
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        // 2. 挥手与受击动画广播抑制 (PacketPlayOutAnimation / ClientboundAnimatePacket)
        // 拦截该次违规攻击派生的攻击者挥手广播 (0: SWING_MAIN_ARM, 3: SWING_OFF_HAND)
        // 以及目标实体派生的受击/暴击粒子动画 (1: HURT, 4: CRITICAL_EFFECT, 5: MAGIC_CRITICAL_EFFECT)
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_ANIMATION) {
            try {
                WrapperPlayServerEntityAnimation animWrapper = new WrapperPlayServerEntityAnimation(event);
                int entityId = animWrapper.getEntityId();
                WrapperPlayServerEntityAnimation.EntityAnimationType type = animWrapper.getType();
                if (type != null) {
                    String typeName = type.name();
                    if (typeName.contains("SWING")) {
                        if (isAttackerEntitySuppressed(entityId)) {
                            event.setCancelled(true);
                        }
                    } else if (typeName.contains("HURT") || typeName.contains("CRIT")) {
                        if (isVictimEntitySuppressed(entityId)) {
                            event.setCancelled(true);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            return;
        }

        // 3. 服务端因受击生成的命名音效包抑制 (PacketPlayOutNamedSoundEffect / ClientboundSoundPacket)
        // 不播放实体受击 (hurt)、攻击暴击/横扫/击退 (player.attack.*) 音效
        if (event.getPacketType() == PacketType.Play.Server.SOUND_EFFECT
                || event.getPacketType() == PacketType.Play.Server.ENTITY_SOUND_EFFECT) {
            if (suppressedVictimEntityIds.isEmpty() && suppressedAttackerEntityIds.isEmpty()) {
                return;
            }
            try {
                if (event.getPacketType() == PacketType.Play.Server.SOUND_EFFECT) {
                    WrapperPlayServerSoundEffect soundWrapper = new WrapperPlayServerSoundEffect(event);
                    String soundName = (soundWrapper.getSound() != null && soundWrapper.getSound().getSoundId() != null)
                            ? soundWrapper.getSound().getSoundId().getKey().toLowerCase(Locale.ROOT)
                            : "";
                    if (isCombatHurtOrAttackSound(soundName)) {
                        // 音效包坐标在 PacketEvents 中为定点坐标 (block * 8) 或世界坐标，验证是否靠近被抑制实体
                        double sx = soundWrapper.getEffectPosition().getX() / 8.0;
                        double sy = soundWrapper.getEffectPosition().getY() / 8.0;
                        double sz = soundWrapper.getEffectPosition().getZ() / 8.0;
                        if (isNearSuppressedCombatLocation(sx, sy, sz)) {
                            event.setCancelled(true);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isCombatHurtOrAttackSound(String soundKey) {
        if (soundKey == null || soundKey.isEmpty()) {
            return false;
        }
        return soundKey.contains("hurt")
                || soundKey.contains("player.attack")
                || soundKey.contains("entity.player.attack")
                || soundKey.contains("mace.smash");
    }

    private boolean isNearSuppressedCombatLocation(double x, double y, double z) {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Long> entry : suppressedVictimEntityIds.entrySet()) {
            if (now - entry.getValue() <= PACKET_SUPPRESSION_WINDOW_MS) {
                Location loc = suppressedVictimLocations.get(entry.getKey());
                if (loc != null) {
                    double dx = loc.getX() - x;
                    double dy = loc.getY() - y;
                    double dz = loc.getZ() - z;
                    if ((dx * dx + dy * dy + dz * dz) <= 36.0) { // 6 格半径内
                        return true;
                    }
                } else {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isAttackerEntitySuppressed(int attackerEntityId) {
        Long ts = suppressedAttackerEntityIds.get(attackerEntityId);
        if (ts == null) {
            return false;
        }
        if (System.currentTimeMillis() - ts <= PACKET_SUPPRESSION_WINDOW_MS) {
            return true;
        }
        suppressedAttackerEntityIds.remove(attackerEntityId);
        return false;
    }

    public boolean isVictimEntitySuppressed(int victimEntityId) {
        Long ts = suppressedVictimEntityIds.get(victimEntityId);
        if (ts == null) {
            return false;
        }
        if (System.currentTimeMillis() - ts <= PACKET_SUPPRESSION_WINDOW_MS) {
            return true;
        }
        suppressedVictimEntityIds.remove(victimEntityId);
        suppressedVictimLocations.remove(victimEntityId);
        return false;
    }

    // =========================================================================
    // 第三阶段：Bukkit 事件层兜底防御 (Fallback Defense)
    // =========================================================================

    /**
     * 应用 Bukkit 实体伤害事件兜底裁决结果（支持分级处置策略）
     */
    public void applyFallbackVerdict(EntityDamageByEntityEvent event, Player attacker, Entity victim, CombatVerdict verdict) {
        if (event == null || attacker == null || verdict == null || !verdict.isBlocked()) {
            return;
        }

        // 1. 取消 Bukkit 事件并归零伤害
        event.setCancelled(true);
        try {
            event.setDamage(0.0);
        } catch (Throwable ignored) {
        }

        // 2. 登记抑制状态，防止下游状态包泄漏
        long now = System.currentTimeMillis();
        recentCancelledAttacks.put(attacker.getUniqueId(), now);
        suppressedAttackerEntityIds.put(attacker.getEntityId(), now);
        if (victim != null) {
            suppressedVictimEntityIds.put(victim.getEntityId(), now);
            suppressedVictimLocations.put(victim.getEntityId(), victim.getLocation());
        }

        // 3. 分级处置：仅高确信作弊才增加严重 VL 并检查移动异常回退
        PlayerData data = plugin.getPlayerManager() != null ? plugin.getPlayerManager().getPlayerData(attacker) : null;
        if (verdict.isHighConfidence()) {
            escalateHighConfidence(attacker, data, verdict);
        } else if (plugin.getCheckDebugLogger() != null && verdict.getCheck() != null) {
            plugin.getCheckDebugLogger().log(verdict.getCheck().getName(), attacker.getName(),
                    "[FALLBACK_SILENT_DROP] " + verdict.getReason());
        }
    }

    /**
     * 兼容旧版直接取消伤害事件的调用入口（默认按高确信违规处理）
     */
    public void mitigateDamage(EntityDamageByEntityEvent event, Player attacker, Entity victim,
                               AbstractCheck check, int vl, String reason) {
        applyFallbackVerdict(event, attacker, victim, CombatVerdict.highConfidence(check, vl, reason));
    }

    /**
     * 判断玩家当前是否处于攻击抑制微窗口
     */
    public boolean isAttackSuppressed(Player attacker) {
        if (attacker == null) {
            return false;
        }
        Long lastCancel = recentCancelledAttacks.get(attacker.getUniqueId());
        if (lastCancel == null) {
            return false;
        }
        if (System.currentTimeMillis() - lastCancel <= PACKET_SUPPRESSION_WINDOW_MS) {
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
            long expiry = System.currentTimeMillis() + durationMs;
            recentCancelledAttacks.put(attacker.getUniqueId(), expiry);
            suppressedAttackerEntityIds.put(attacker.getEntityId(), expiry);
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
        suppressedAttackerEntityIds.clear();
        suppressedVictimEntityIds.clear();
        suppressedVictimLocations.clear();
    }
}
