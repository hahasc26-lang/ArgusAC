package com.example.argusac.packet;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.data.PlayerData;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
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
            plugin.getLogger().info("[PacketPipeline] PacketEvents 跨版本数据包引擎已挂载就绪 (PacketListener registered)");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[PacketPipeline] 挂载 PacketEvents 监听器失败", e);
        }
    }

    public void shutdown() {
        if (active) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(this);
            } catch (Exception ignored) {}
            this.active = false;
        }
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null) return;

        Object playerObj = event.getPlayer();
        if (!(playerObj instanceof Player player)) return;

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null) return;

        // 1. 拦截客户端移动数据包族 (Flying, Position, Rotation, PositionLook)
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            handleIncomingFlyingPacket(player, data, user, flying, event);
        }

        // 2. 拦截客户端手臂摆动与左键挖掘包 (Animation)
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            handleIncomingAnimationPacket(player, data);
        }

        // 3. 拦截客户端实体交互与攻击包 (Interact Entity)
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            handleIncomingInteractEntityPacket(player, data, event);
        }

        // 4. 拦截客户端状态动作包 (Entity Action - Sneak / Sprint)
        if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            handleIncomingEntityActionPacket(player, data, event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // 1. 服务端发出的实体击退与速度同步包追踪
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            Object playerObj = event.getPlayer();
            if (playerObj instanceof Player player) {
                PlayerData data = plugin.getPlayerManager().getPlayerData(player);
                if (data != null) {
                    try {
                        com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity velocityWrapper =
                                new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity(event);
                        if (velocityWrapper.getEntityId() == player.getEntityId()) {
                            com.github.retrooper.packetevents.util.Vector3d vel = velocityWrapper.getVelocity();
                            if (vel != null) {
                                data.getPhysicsState().addPendingVelocity(vel.getX(), vel.getY(), vel.getZ());
                                data.setExpectedVelocity(new org.bukkit.util.Vector(vel.getX(), vel.getY(), vel.getZ()));
                            }
                        }
                    } catch (Throwable ignored) {}
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

        // 3. 协议层 Timer 变速时钟平衡检测 (Clock Drift Engine)
        if (!plugin.isPlayerExempt(player) && !data.isSetbackImmune() && !data.isInGracePeriod()) {
            if (data.getTimerTracker().onFlyingPacket()) {
                plugin.getViolationManager().flag(player, plugin.getCheckManager().getBadPacketsCheck(),
                        2, String.format("Timer balance=%.1fms, buf=%.2f",
                                data.getTimerTracker().getBalance(), data.getTimerTracker().getViolationBuffer()));
            }
        }

        // 4. 虚假地面状态检测 (NoFall Ground Spoof)
        if (flying.isOnGround() && data.getAirTicks() > 4 && !data.isOnGround()
                && !data.isSetbackImmune() && !data.hasAnyLegalVelocity()) {
            plugin.getViolationManager().flag(player, plugin.getCheckManager().getNoFallCheck(),
                    1, String.format("Packet GroundSpoof airTicks=%d", data.getAirTicks()));
        }
    }

    /**
     * 客户端手臂挥舞包 (Animation) 捕获，纳秒级打点消除主线程 Tick 合并造成的 CPS 失真
     */
    private void handleIncomingAnimationPacket(Player player, PlayerData data) {
        if (!plugin.isPlayerExempt(player)) {
            plugin.getCheckManager().getAutoClickerCheck().handleClick(player);
        }
    }

    /**
     * 客户端实体交互包 (Interact Entity) 捕获
     */
    private void handleIncomingInteractEntityPacket(Player player, PlayerData data, PacketReceiveEvent event) {
        if (plugin.isPlayerExempt(player)) return;
        try {
            WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
            if (interact.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                plugin.getCheckManager().getAutoClickerCheck().handleClick(player);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 客户端状态动作包 (Entity Action - 潜行/疾跑)
     */
    private void handleIncomingEntityActionPacket(Player player, PlayerData data, PacketReceiveEvent event) {
        if (plugin.isPlayerExempt(player)) return;
        try {
            WrapperPlayClientEntityAction action = new WrapperPlayClientEntityAction(event);
            if (action.getAction() == WrapperPlayClientEntityAction.Action.START_SPRINTING && player.isSneaking()) {
                // 协议层发现冲突潜行疾跑状态
                if (player.getEyeHeight() >= 1.4) {
                    plugin.getViolationManager().flag(player, plugin.getCheckManager().getBadPacketsCheck(),
                            1, "Conflicting Sneak-Sprint Packet Action");
                }
            }
        } catch (Throwable ignored) {}
    }
}
