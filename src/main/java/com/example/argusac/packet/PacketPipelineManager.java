package com.example.argusac.packet;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.data.PlayerData;
import com.example.argusac.i18n.I18n;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import org.bukkit.entity.Player;

import java.util.logging.Level;

/**
 * 核心数据包协议管线管理器 (PacketPipelineManager)
 * 基于 PacketEvents 建立统一底层数据包监听与事件分发调度
 */
public class PacketPipelineManager extends PacketListenerAbstract {

    private final ArgusACPlugin plugin;
    private boolean active = false;

    public PacketPipelineManager(ArgusACPlugin plugin) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
    }

    public void init() {
        try {
            PacketEvents.getAPI().getEventManager().registerListener(this);
            this.active = true;
            plugin.getLogger().info(I18n.getServer("system.packet-pipeline-ready"));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, I18n.getServer("system.packet-pipeline-failed"), e);
        }
    }

    public void shutdown() {
        if (active) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(this);
            } catch (Exception ignored) {
            }
            this.active = false;
        }
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null)
            return;

        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player))
            return;

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null)
            return;

        // 1. 拦截客户端移动数据包族 (Flying, Position, Rotation, PositionLook)
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            handleIncomingFlyingPacket(player, data, user, flying, event);
        }

        // 2. 拦截客户端手臂摆动与左键挖掘包 (Animation)
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            handleIncomingAnimationPacket(player, data, event);
        }

        // 3. 拦截客户端实体交互与攻击包 (Interact Entity / PacketPlayInUseEntity / ServerboundInteractPacket)
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            handleIncomingInteractEntityPacket(player, data, event);
        }

        // 4. 拦截客户端挖掘方块动作包 (Player Digging)
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            handleIncomingPlayerDiggingPacket(player, data, event);
        }

        // 5. 拦截客户端传送确认包 (Teleport Confirm / ServerboundAcceptTeleportationPacket)
        // 对齐 GrimAC 事务闭环：收到客户端 ACK 确认，解除 In-Flight 状态，彻底杜绝回退震颤与连环误拉
        if (event.getPacketType() == PacketType.Play.Client.TELEPORT_CONFIRM) {
            handleIncomingTeleportConfirm(player, data, event);
        }

        // 6. 拦截客户端使用物品数据包 (Use Item - 弓箭/弩蓄力协议级打点)
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            handleIncomingUseItemPacket(player, data);
        }
    }

    private void handleIncomingUseItemPacket(Player player, PlayerData data) {
        if (player == null || data == null || plugin.getCheckManager() == null)
            return;
        try {
            org.bukkit.inventory.ItemStack main = player.getInventory().getItemInMainHand();
            org.bukkit.inventory.ItemStack off = player.getInventory().getItemInOffHand();
            if (main != null && (main.getType().name().equals("BOW") || main.getType().name().equals("CROSSBOW"))) {
                plugin.getCheckManager().getFastBowCheck().handleBowStart(player, main);
            } else if (off != null && (off.getType().name().equals("BOW") || off.getType().name().equals("CROSSBOW"))) {
                plugin.getCheckManager().getFastBowCheck().handleBowStart(player, off);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleIncomingTeleportConfirm(Player player, PlayerData data, PacketReceiveEvent event) {
        int teleportId = -1;
        try {
            com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm confirm = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm(
                    event);
            teleportId = confirm.getTeleportId();
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method m = event.getClass().getMethod("getTeleportId");
                teleportId = (int) m.invoke(event);
            } catch (Throwable ignored) {
            }
        }

        if (plugin.getSetbackManager() != null) {
            com.example.argusac.setback.PlayerSetbackContext context = plugin.getSetbackManager()
                    .getContext(player.getUniqueId());
            if (context != null) {
                context.confirmTeleport(teleportId);
            }
        }

        if (data != null && data.getPhysicsState() != null) {
            data.getPhysicsState().reset(player.getLocation());
            data.getPhysicsState().clearPendingVelocities();
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // 0. 第二阶段：下游实体状态包、挥手广播包与受击音效包抑制 (Entity Status / Animation / Sound Suppression)
        if (plugin.getCombatMitigationHandler() != null) {
            plugin.getCombatMitigationHandler().handleOutgoingPacketSuppression(event);
            if (event.isCancelled()) {
                return;
            }
        }

        // 1. 服务端发出的实体击退与速度同步包追踪
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            Object playerObj = event.getPlayer();
            if (playerObj instanceof Player player) {
                PlayerData data = plugin.getPlayerManager().getPlayerData(player);
                if (data != null) {
                    try {
                        com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity velocityWrapper = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity(
                                event);
                        if (velocityWrapper.getEntityId() == player.getEntityId()) {
                            com.github.retrooper.packetevents.util.Vector3d vel = velocityWrapper.getVelocity();
                            if (vel != null) {
                                double lenSq = vel.getX() * vel.getX() + vel.getY() * vel.getY() + vel.getZ() * vel.getZ();
                                if (lenSq < 0.0001) {
                                    // 回退/定身时下发的零速度包：对齐 GrimAC，重置速度状态，彻底清除动量与击退标记
                                    data.clearExpectedVelocity();
                                } else {
                                    data.getPhysicsState().addPendingVelocity(vel.getX(), vel.getY(), vel.getZ());
                                    data.setExpectedVelocity(
                                            new org.bukkit.util.Vector(vel.getX(), vel.getY(), vel.getZ()));
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // 2. 服务端发出的确认包或位置同步包追踪 (用于回退与延迟补偿状态同步)
        if (event.getPacketType() == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            Object playerObj = event.getPlayer();
            if (playerObj instanceof Player player) {
                PlayerData data = plugin.getPlayerManager().getPlayerData(player);
                if (data != null) {
                    // 记录服务端发出的强制传送/回退时间戳与标记
                    data.setLastTeleportTime(System.currentTimeMillis());
                    // 消除传送引起的动量畸变
                    data.getPhysicsState().reset(player.getLocation());
                }
            }
        }
    }

    /**
     * 针对底层原生移动包进行协议级异常检测与状态追踪
     */
    private void handleIncomingFlyingPacket(Player player, PlayerData data, User user,
            WrapperPlayClientPlayerFlying flying, PacketReceiveEvent event) {
        // 0. 对标 GrimAC 协议级 In-Flight 硬阻断与装聋作哑 (Ignore Teleport) 逃避补刀反制：
        // 服务端发出了位置重置包，在客户端回传 TELEPORT_CONFIRM 前，客户端发来的所有位移数据包属于在途作废位移。
        // 必须在协议管线最底层直接取消拦截，同时将数据包内部坐标硬重写为锚点坐标，从源头扼死作弊客户端的蹭步蠕动！
        if (plugin.getSetbackManager() != null) {
            com.example.argusac.setback.PlayerSetbackContext setbackCtx = plugin.getSetbackManager()
                    .getContext(player.getUniqueId());
            if (setbackCtx != null && setbackCtx.isTeleportInFlight()) {
                org.bukkit.Location target = setbackCtx.getTeleportInFlightTarget();
                if (target != null) {
                    double px = flying.getLocation().getX();
                    double py = flying.getLocation().getY();
                    double pz = flying.getLocation().getZ();
                    double dx = px - target.getX();
                    double dy = py - target.getY();
                    double dz = pz - target.getZ();

                    // 若客户端发包坐标尚未到达纠正目标点，执行双重硬核镇压：
                    if ((dx * dx + dy * dy + dz * dz) > 0.0025) {
                        // 1. 数据包直接在底层取消
                        event.setCancelled(true);

                        // 2. 坐标硬重写覆盖 (Coordinate Hard-Clamp Invariant)：防止任何穿透逻辑读取到违规微位移
                        try {
                            com.github.retrooper.packetevents.protocol.world.Location cur = flying.getLocation();
                            float yaw = (cur != null) ? cur.getYaw() : target.getYaw();
                            float pitch = (cur != null) ? cur.getPitch() : target.getPitch();
                            flying.setLocation(new com.github.retrooper.packetevents.protocol.world.Location(
                                    target.getX(), target.getY(), target.getZ(), yaw, pitch
                            ));
                        } catch (Throwable ignored) {
                        }

                        // 3. 对标 GrimAC BadPacketsN：检测作弊客户端是否在故意装聋作哑丢弃回退包
                        if (setbackCtx.recordUnackedMovePacket()) {
                            // 当场原地补刀重发回退数据包，并彻底清空残余动量！
                            plugin.getSetbackManager().sendPositionSyncPacket(player, target,
                                    setbackCtx.getInFlightTeleportId());
                            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                            if (data != null && data.getPhysicsState() != null) {
                                data.getPhysicsState().reset(target);
                                data.getPhysicsState().clearPendingVelocities();
                            }
                        }
                        return;
                    }
                }
            }
        }

        // 1. 浮点越界校验 (NaN / Infinite)
        if (flying.hasPositionChanged()) {
            double x = flying.getLocation().getX();
            double y = flying.getLocation().getY();
            double z = flying.getLocation().getZ();

            if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)
                    || Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z)
                    || Math.abs(x) > 3.0E7 || Math.abs(z) > 3.0E7) {
                event.setCancelled(true);
                plugin.getViolationManager().flag(player, plugin.getCheckManager().getBadPacketsCheck(),
                        5, "Illegal raw packet coordinates (NaN/Infinite/WorldBorder)");
                return;
            }
        }

        // 2. 纯视角/组合视角非法俯仰角协议拦截 (Pitch [-90.0, 90.0])
        if (flying.hasRotationChanged()) {
            float pitch = flying.getLocation().getPitch();
            float yaw = flying.getLocation().getYaw();

            if (Float.isNaN(pitch) || Float.isNaN(yaw) || Float.isInfinite(pitch) || Float.isInfinite(yaw)) {
                event.setCancelled(true);
                plugin.getViolationManager().flag(player, plugin.getCheckManager().getBadPacketsCheck(),
                        5, "Illegal raw packet rotation (NaN/Infinite)");
                return;
            }

            if (pitch > 90.001f || pitch < -90.001f) {
                event.setCancelled(true);
                plugin.getViolationManager().flag(player, plugin.getCheckManager().getBadPacketsCheck(),
                        3, String.format("Illegal Raw Packet Pitch: %.2f", pitch));
                return;
            }
        }

        data.setClientOnGround(flying.isOnGround());

        // 实时同步 Netty 数据包级最新坐标与视角，确保后续战斗进包裁决 100% 对齐客户端本地屏幕
        if (flying.hasPositionChanged() && flying.hasRotationChanged()) {
            data.updatePacketLocation(
                    flying.getLocation().getX(),
                    flying.getLocation().getY(),
                    flying.getLocation().getZ(),
                    flying.getLocation().getYaw(),
                    flying.getLocation().getPitch()
            );
        } else if (flying.hasPositionChanged()) {
            data.updatePacketPosition(
                    flying.getLocation().getX(),
                    flying.getLocation().getY(),
                    flying.getLocation().getZ()
            );
        } else if (flying.hasRotationChanged()) {
            data.updatePacketRotation(
                    flying.getLocation().getYaw(),
                    flying.getLocation().getPitch()
            );
        }

        // 创造/旁观、允许飞行、坐船/矿车等载具、或鞘翅滑翔完全豁免协议层 Timer 与 NoFall
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight() || player.isInsideVehicle()
                || plugin.getCheckManager().isGlidingExempt(player, data)) {
            return;
        }

        // 3. 协议层 Timer 变速时钟平衡检测 (Clock Drift Engine)
        if (!plugin.isPlayerExempt(player) && !data.isSetbackImmune() && !data.isInGracePeriod()) {
            if (data.getTimerTracker().onFlyingPacket()) {
                event.setCancelled(true);
                plugin.getViolationManager().flag(player, plugin.getCheckManager().getBadPacketsCheck(),
                        2, String.format("Timer balance=%.1fms, buf=%.2f",
                                data.getTimerTracker().getBalance(), data.getTimerTracker().getViolationBuffer()));
                if (plugin.getSetbackManager() != null) {
                    plugin.getSetbackManager().mitigate(player, plugin.getCheckManager().getBadPacketsCheck(),
                            2, "Timer packet spam balance exceeded", null);
                }
            }
        }

        // 4. 虚假地面状态检测 (NoFall Ground Spoof)
        // 排除梯子/藤蔓等可攀爬物以及液体环境 (攀爬和涉水在原版协议中存在特殊的接地过渡)
        org.bukkit.block.Block feet = player.getLocation().getBlock();
        com.example.argusac.compat.VersionAdapter adapter = com.example.argusac.compat.VersionAdapterFactory
                .getAdapter();
        boolean specialEnvironment = adapter.isLiquid(feet) || adapter.isClimbable(feet)
                || adapter.isStandable(feet) || adapter.isStandable(feet.getRelative(0, -1, 0));

        boolean groundSpoof = flying.isOnGround() && data.getAirTicks() > 4 && !data.isOnGround()
                && !data.isSetbackImmune() && !data.hasAnyLegalVelocity() && !specialEnvironment;
        if (data.recordNoFallSpoofPacket(groundSpoof)) {
            plugin.getViolationManager().flag(player, plugin.getCheckManager().getNoFallCheck(),
                    1, String.format("Packet GroundSpoof airTicks=%d", data.getAirTicks()));
        }
    }

    /**
     * 客户端手臂挥舞包 (Animation) 捕获：
     * 1. 若该玩家正处于违规攻击抑制期，当场拦截挥手包，防止服务端向周围玩家广播异常挥臂动作；
     * 2. 纳秒级打点统计 CPS，若超过阈值同样拦截该包。
     */
    private void handleIncomingAnimationPacket(Player player, PlayerData data, PacketReceiveEvent event) {
        if (plugin.getCombatMitigationHandler() != null && plugin.getCombatMitigationHandler().isAttackSuppressed(player)) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.isPlayerExempt(player) && !data.isDigging()) {
            com.example.argusac.combat.CombatVerdict verdict = plugin.getCheckManager().getAutoClickerCheck().evaluateClick(player);
            if (verdict.isBlocked()) {
                event.setCancelled(true);
                if (plugin.getCombatMitigationHandler() != null) {
                    plugin.getCombatMitigationHandler().suppressAttack(player, 75L);
                }
            }
        }
    }

    /**
     * 客户端实体交互包 (Interact Entity / PacketPlayInUseEntity / ServerboundInteractPacket) 捕获
     * 过滤条件：只拦截交互类型为 ATTACK 的动作，并在 Netty 进包瞬间完成合规初筛与丢弃
     */
    private void handleIncomingInteractEntityPacket(Player player, PlayerData data, PacketReceiveEvent event) {
        if (plugin.getSetbackManager() != null) {
            com.example.argusac.setback.PlayerSetbackContext setbackCtx = plugin.getSetbackManager()
                    .getContext(player.getUniqueId());
            if (setbackCtx != null && (setbackCtx.isTeleportInFlight() || setbackCtx.isUnderViolationLock())) {
                event.setCancelled(true);
                return;
            }
        }

        try {
            com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity interactWrapper = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity(
                    event);
            if (interactWrapper
                    .getAction() == com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                if (plugin.getCombatMitigationHandler() != null) {
                    plugin.getCombatMitigationHandler().handlePacketAttack(player, data, interactWrapper.getEntityId(),
                            event);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 客户端方块挖掘状态追踪 (区分左键挥臂攻击与连续方块破坏，彻底根除挖矿时的 AutoClicker 误判)
     */
    private void handleIncomingPlayerDiggingPacket(Player player, PlayerData data, PacketReceiveEvent event) {
        if (plugin.getSetbackManager() != null) {
            com.example.argusac.setback.PlayerSetbackContext setbackCtx = plugin.getSetbackManager()
                    .getContext(player.getUniqueId());
            if (setbackCtx != null && setbackCtx.isTeleportInFlight()) {
                event.setCancelled(true);
                return;
            }
        }
        try {
            com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging digWrapper = new com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging(
                    event);
            Object action = digWrapper.getAction();
            if (action != null) {
                String actionName = action.toString();
                if (actionName.contains("START")) {
                    data.setDigging(true);
                    data.setLastDiggingTime(System.currentTimeMillis());
                    data.getClickTimestamps().clear();
                } else if (actionName.contains("CANCEL") || actionName.contains("FINISH")
                        || actionName.contains("ABORT") || actionName.contains("STOP")) {
                    data.setDigging(false);
                    data.setLastDiggingTime(System.currentTimeMillis());
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
