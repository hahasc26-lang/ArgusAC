package com.example.argusac.setback.handler;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import com.example.argusac.manager.SetbackManager;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.setback.PendingCorrection;
import com.example.argusac.setback.PlayerSetbackContext;
import com.example.argusac.setback.SafeLocationFinder;
import com.example.argusac.setback.SetbackNotifier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.logging.Level;

/**
 * HARD 模式处理器 (Teleportation Setback)
 * <p>
 * 核心机制：
 * 1. 从已验证的合法历史快照滑动窗口 (LegitimateLocation Snapshots) 中查找有界安全坐标；
 * 2. 在 Bukkit 主线程执行强制瞬移传送 (player.teleport)，附带网络校正序列 (PendingCorrection)；
 * 3. 剥离载具、重置下落距离与异常动量，提供稳固保底降级搜索与越界安全防护。
 */
public class HardSetbackHandler implements SetbackHandler {

    private final ArgusACPlugin plugin;
    private final SetbackManager setbackManager;
    private final SafeLocationFinder safeLocationFinder;
    private final SetbackNotifier setbackNotifier;

    public HardSetbackHandler(ArgusACPlugin plugin,
                              SetbackManager setbackManager,
                              SafeLocationFinder safeLocationFinder,
                              SetbackNotifier setbackNotifier) {
        this.plugin = plugin;
        this.setbackManager = setbackManager;
        this.safeLocationFinder = safeLocationFinder;
        this.setbackNotifier = setbackNotifier;
    }

    @Override
    public boolean handle(Player player, Check check, int currentVl, String reason, PlayerMoveEvent moveEvent) {
        if (player == null || !player.isOnline() || check == null) {
            return false;
        }

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> handle(player, check, currentVl, reason, null));
            return true;
        }

        if (moveEvent != null) {
            moveEvent.setCancelled(true);
        }

        Location curLoc = player.getLocation();
        String fromLocStr = SafeLocationFinder.formatLoc(curLoc);
        long now = System.currentTimeMillis();
        PlayerSetbackContext context = setbackManager.getContext(player.getUniqueId());

        UUID curWorldUid = (curLoc.getWorld() != null) ? curLoc.getWorld().getUID() : null;
        LegitimateLocation target = context.findBoundedLegitimateLocation(
                curWorldUid,
                curLoc,
                setbackManager.getMaxSetbackDistance(),
                setbackManager.getMaxSnapshotAgeMs()
        );
        boolean usedFallback = false;

        if (target == null) {
            if (context.getLastGroundLocation() != null && context.getLastGroundLocation().isPassable()) {
                target = context.getLastGroundLocation();
                usedFallback = true;
            } else {
                Location safeGround = safeLocationFinder.findSafeGroundBelow(curLoc);
                if (safeGround != null) {
                    target = LegitimateLocation.from(safeGround, null, true, false, null);
                    usedFallback = true;
                }
            }

            if (target == null) {
                LegitimateLocation relaxedTarget = context.findNearestPassableLocation(curWorldUid, curLoc, setbackManager.getMaxSetbackDistance() * 2.0);
                if (relaxedTarget != null) {
                    target = relaxedTarget;
                    usedFallback = true;
                } else {
                    setbackNotifier.recordFailure(player, context, check.getName(), "UNBOUNDED_OR_OCCLUDED", fromLocStr);
                    if (setbackManager.isKickWhenBothBlocked()) {
                        setbackNotifier.dispatchKick(player, setbackManager.getKickMessage());
                    }
                    return false;
                }
            }
        }

        Location targetLocation = target.toLocation();
        if (targetLocation == null || targetLocation.getWorld() == null) {
            setbackNotifier.recordFailure(player, context, check.getName(), "WORLD_UNAVAILABLE", target.toString());
            return false;
        }

        targetLocation.setYaw(curLoc.getYaw());
        targetLocation.setPitch(curLoc.getPitch());

        String toLocStr = SafeLocationFinder.formatLoc(targetLocation);

        int ping = Math.max(0, VersionAdapterFactory.getAdapter().getPing(player));
        long correctionTimeout = Math.min(setbackManager.getCorrectionTimeoutMs(), 2L * ping + 150L);
        long seq = context.getNextSequenceId();
        context.setActiveSequenceId(seq);
        context.setPendingCorrection(new PendingCorrection(
                targetLocation.getX(), targetLocation.getY(), targetLocation.getZ(),
                targetLocation.getWorld().getUID(),
                now + correctionTimeout,
                seq
        ));
        context.setLastSetbackTimestamp(check.getName(), now);

        final LegitimateLocation finalTarget = target;
        final boolean finalUsedFallback = usedFallback;

        executeHardSetbackOnMainThread(player, context, targetLocation, finalTarget, check.getName(), currentVl, reason, fromLocStr, toLocStr, finalUsedFallback);
        return true;
    }

    private void executeHardSetbackOnMainThread(Player player, PlayerSetbackContext context,
                                                Location targetLocation, LegitimateLocation target,
                                                String checkName, int currentVl, String reason,
                                                String fromLocStr, String toLocStr, boolean usedFallback) {
        if (!player.isOnline()) {
            setbackNotifier.recordFailure(player, context, checkName, "PLAYER_OFFLINE", toLocStr);
            return;
        }

        UUID uid = player.getUniqueId();
        try {
            setbackManager.addSelfTeleporting(uid);

            if (player.isInsideVehicle() && player.getVehicle() != null) {
                org.bukkit.entity.Entity vehicle = player.getVehicle();
                vehicle.setVelocity(new Vector(0, 0, 0));
                // 严格对标 GrimAC：强制将违规玩家从载具弹出下马并单独拉回载具
                player.leaveVehicle();
                vehicle.teleport(targetLocation);
            }

            int teleportId = context.generateSecureTeleportId();
            context.markTeleportInFlight(teleportId, targetLocation);
            setbackManager.sendPositionSyncPacket(player, targetLocation, teleportId);

            boolean teleportSuccess = player.teleport(targetLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);

            if (teleportSuccess) {
                if (target != null && target.isOnGround()) {
                    player.setVelocity(new Vector(0, 0, 0));
                    player.setFallDistance(0.0f);
                } else {
                    player.setVelocity(new Vector(0, -0.08, 0));
                }

                PlayerData data = plugin.getPlayerManager().getPlayerData(player);
                if (data != null) {
                    data.clearExpectedVelocity();
                    data.setSpeedBuffer(0.0);
                    data.getTimerTracker().reset();
                    data.updatePacketPosition(targetLocation.getX(), targetLocation.getY(), targetLocation.getZ());
                    if (data.getPhysicsState() != null) {
                        data.getPhysicsState().reset(targetLocation);
                        data.getPhysicsState().clearPendingVelocities();
                    }
                }

                if (target != null) {
                    context.setLastLegitimateLocation(target);
                }

                setbackNotifier.postSetbackSuccess(player, context, checkName, currentVl, reason, "HARD", fromLocStr, toLocStr, usedFallback, target,
                        setbackManager.getNotifyPlayerChannel(), setbackManager.getNotifyPlayerCooldownMs(), setbackManager.isNotifyAdminEnabled());
            } else {
                context.confirmTeleport(teleportId);
                setbackNotifier.recordFailure(player, context, checkName, "TELEPORT_CANCELLED", toLocStr);
            }

        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Error executing HARD setback for " + player.getName(), ex);
            setbackNotifier.recordFailure(player, context, checkName, "EXCEPTION_" + ex.getMessage(), toLocStr);
        } finally {
            setbackManager.removeSelfTeleporting(uid);
        }
    }
}

