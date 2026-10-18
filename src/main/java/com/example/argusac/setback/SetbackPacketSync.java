package com.example.argusac.setback;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Set;
import java.util.UUID;

/**
 * 协议级网络位置同步分发器 (SetbackPacketSync)
 * 通过 PacketEvents 管道向作弊玩家通道立即下发位置同步重置数据包，
 * 强行截断作弊客户端本地物理预测并锚定原位。
 */
public class SetbackPacketSync {

    /**
     * 底层数据包协议级即时发包同步 (向客户端强制推送当前位置重置报文，打断客户端本地物理预测)
     */
    public static void sendPositionSyncPacket(Player player, Location target, Set<UUID> selfTeleportingPlayers) {
        sendPositionSyncPacket(player, target, 0, selfTeleportingPlayers);
    }

    /**
     * 带流水号 (Teleport ID / Transaction) 的底层位置同步重置报文
     * 对齐 GrimAC 事务机制：下发带有唯一 Teleport ID 的位置同步包，引导客户端发送 TELEPORT_CONFIRM 报文
     */
    public static void sendPositionSyncPacket(Player player, Location target, int teleportId, Set<UUID> selfTeleportingPlayers) {
        if (player == null || !player.isOnline() || target == null || target.getWorld() == null) {
            return;
        }

        boolean packetSent = false;
        try {
            if (PacketEvents.getAPI() != null && PacketEvents.getAPI().getPlayerManager() != null) {
                User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
                if (user != null) {
                    try {
                        // PacketEvents 2.x 标准构造: (x, y, z, yaw, pitch, relativeFlags, teleportId, dismountVehicle)
                        WrapperPlayServerPlayerPositionAndLook packet =
                                new WrapperPlayServerPlayerPositionAndLook(
                                        target.getX(), target.getY(), target.getZ(),
                                        target.getYaw(), target.getPitch(),
                                        (byte) 0, teleportId, false
                                );
                        user.sendPacket(packet);
                        packetSent = true;
                    } catch (Throwable t) {
                        // 备用兼容反射构造器
                        try {
                            java.lang.reflect.Constructor<?> ctor = WrapperPlayServerPlayerPositionAndLook.class.getConstructor(
                                    double.class, double.class, double.class, float.class, float.class, byte.class, int.class, boolean.class
                            );
                            Object packet = ctor.newInstance(
                                    target.getX(), target.getY(), target.getZ(),
                                    target.getYaw(), target.getPitch(),
                                    (byte) 0, teleportId, false
                            );
                            user.sendPacket((com.github.retrooper.packetevents.wrapper.PacketWrapper<?>) packet);
                            packetSent = true;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}

        if (!packetSent && selfTeleportingPlayers != null) {
            // 底层包发送未命中时的服务端硬重置备选保证：通过自传送向客户端推送官方同步报文
            try {
                selfTeleportingPlayers.add(player.getUniqueId());
                player.teleport(target, PlayerTeleportEvent.TeleportCause.UNKNOWN);
            } catch (Throwable ignored) {
            } finally {
                selfTeleportingPlayers.remove(player.getUniqueId());
            }
        }
    }
}
