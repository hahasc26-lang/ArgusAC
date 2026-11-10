package com.example.argusac.setback.handler;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.Check;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import com.example.argusac.manager.SetbackManager;
import com.example.argusac.model.LegitimateLocation;
import com.example.argusac.setback.PlayerSetbackContext;
import com.example.argusac.setback.SafeLocationFinder;
import com.example.argusac.setback.SetbackNotifier;
import com.example.argusac.setback.SetbackPhysics;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

/**
 * CANCEL 模式处理器 (Direct Cancellation)
 * <p>
 * 核心机制对标 GrimAC：
 * 1. 当帧直接取消违规移动事件，阻断非法位移；
 * 2. 严格锁定回退锚点 (Locked Anchor)，杜绝蠕变去同步 (Anti-Creep)；
 * 3. 负数 Teleport ID 二进制隔离与网络协议级位置同步包下发；
 * 4. 强制弹出载具并重置速度动量，结合防穿模重力与地形贴合对齐。
 */
public class CancelSetbackHandler implements SetbackHandler {

    private final ArgusACPlugin plugin;
    private final SetbackManager setbackManager;
    private final SafeLocationFinder safeLocationFinder;
    private final SetbackNotifier setbackNotifier;

    public CancelSetbackHandler(ArgusACPlugin plugin,
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
        if (player == null || check == null || moveEvent == null) {
            return false;
        }

        PlayerSetbackContext context = setbackManager.getContext(player.getUniqueId());
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        Location from = moveEvent.getFrom();
        Location to = moveEvent.getTo();
        if (from == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long previousCancelTime = context.getLastCancellationViolationTime();
        context.incrementConsecutiveCancellationViolations();
        context.setLastSetbackTimestamp(check.getName(), now);

        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        boolean inClimbable = adapter.isClimbableAround(player, from) || (to != null && adapter.isClimbableAround(player, to));
        boolean physicallyGrounded = setbackManager.isGrounded(player, from) || (data != null && data.isOnGround()) || inClimbable;
        boolean isAirborne = !physicallyGrounded;
        String checkLower = check.getName().toLowerCase();

        Location lockedAnchor = context.getLockedAnchorLocation();
        Location cancelTarget;

        if (lockedAnchor != null && from.getWorld() != null && from.getWorld().equals(lockedAnchor.getWorld())
                && (now - previousCancelTime < 4000L)) {
            cancelTarget = lockedAnchor.clone();
            // 校验历史锚点有效性：若锚点低于脚下方块顶面，立即对齐坚实表面，杜绝历史穿模死锁
            Location anchorGround = safeLocationFinder.findSafeGroundNear(cancelTarget, 0.6);
            if (anchorGround != null && cancelTarget.getY() < anchorGround.getY()) {
                cancelTarget.setY(anchorGround.getY());
            }
        } else {
            if (!isAirborne) {
                // 地面移动或方块边缘：优先吸附对齐台面坚实表面 (Surface Y 与水平安全内侧，杜绝悬崖边缘下坠)
                Location nearGround = safeLocationFinder.findSafeGroundNear(from, 0.6);
                if (nearGround != null && Math.abs(nearGround.getY() - from.getY()) <= 0.35) {
                    cancelTarget = nearGround.clone();
                } else {
                    cancelTarget = from.clone();
                }
            } else {
                // 真实空中悬浮滞空：向下搜索最近落脚点
                Location nearGround = safeLocationFinder.findSafeGroundNear(from, 1.5);
                if (nearGround != null) {
                    cancelTarget = nearGround.clone();
                } else {
                    cancelTarget = from.clone();
                }
            }
            context.setLockedAnchorLocation(cancelTarget);
        }

        if (to != null) {
            cancelTarget.setYaw(to.getYaw());
            cancelTarget.setPitch(to.getPitch());
        }

        boolean inLiquid = adapter.isInWater(player)
                || adapter.isLiquid(cancelTarget.getBlock())
                || adapter.isLiquid(from.getBlock())
                || adapter.isLiquid(cancelTarget.clone().subtract(0, 0.4, 0).getBlock());

        // 仅在纯真空空中滞空且非流体非攀爬环境下才模拟原版重力加速度下坠下压；
        // 流体环境 (水、岩浆) 自带浮力，攀爬环境 (梯子、藤蔓) 支持滞留，绝不执行真空自由落体下坠
        if (inLiquid && to != null && to.getY() < cancelTarget.getY() && (cancelTarget.getY() - to.getY()) <= 0.5) {
            // 在水中发生回退锚定时，允许玩家自然下沉入水，防止将玩家死锁在水面表层 (如 Y=62.94) 引发连环 Jesus 误报
            cancelTarget.setY(to.getY());
            if (context.getLockedAnchorLocation() != null) {
                context.getLockedAnchorLocation().setY(to.getY());
            }
        }
        if (isAirborne && !inLiquid && !inClimbable) {
            // 获取脚下最近的稳固地面支撑高度作为物理硬底线 (Ground Floor)
            Location groundBelow = safeLocationFinder.findSafeGroundNear(from, 1.5);
            if (groundBelow == null) {
                groundBelow = safeLocationFinder.findSafeGroundBelow(from);
            }
            double minAllowedY = (groundBelow != null) ? groundBelow.getY() : (from.getWorld() != null ? from.getWorld().getMinHeight() : -64.0);

            if (to != null && to.getY() > cancelTarget.getY()) {
                cancelTarget.setY(Math.min(cancelTarget.getY(), from.getY()));
            }
            int airTicks = (data != null) ? data.getAirTicks() : 1;
            int consec = context.getConsecutiveCancellationViolations();
            double expectedFall = SetbackPhysics.computeVanillaGravityFall(Math.max(airTicks, consec));
            double currentDeltaY = (to != null) ? (to.getY() - from.getY()) : 0.0;
            if (currentDeltaY > expectedFall) {
                double targetY = Math.min(cancelTarget.getY(), from.getY() + expectedFall);
                // 严禁重力加速度将玩家压穿坚实方块表面 (Surface Y)！最低只降至 minAllowedY！
                double newY = Math.max(minAllowedY, targetY);
                cancelTarget.setY(newY);
                if (context.getLockedAnchorLocation() != null) {
                    context.getLockedAnchorLocation().setY(newY);
                }
            }
        }

        if (player.isInsideVehicle() && player.getVehicle() != null) {
            org.bukkit.entity.Entity vehicle = player.getVehicle();
            vehicle.setVelocity(new Vector(0, 0, 0));
            // 严格对标 GrimAC：强制将违规玩家从载具弹出下马，拉回载具，彻底封死载具插值微位移
            player.leaveVehicle();
            if (!isAirborne) {
                vehicle.teleport(cancelTarget);
            }
        }

        moveEvent.setTo(cancelTarget);
        moveEvent.setCancelled(true);

        if (checkLower.contains("elytra") || checkLower.contains("fly") || player.isGliding()) {
            player.setGliding(false);
            if (data != null && data.getElytraData() != null) {
                data.getElytraData().resetGlideState();
            }
        }

        // 生成绝对隔离的负数 Teleport ID 并下发位置同步包 (GrimAC 源码: random.nextInt() | Integer.MIN_VALUE)
        int teleportId = context.generateSecureTeleportId();
        context.markTeleportInFlight(teleportId, cancelTarget);
        setbackManager.sendPositionSyncPacket(player, cancelTarget, teleportId);

        // 速度与运动状态彻底清零，消除作弊客户端本地残留 Motion 再次触发异常位移
        player.setVelocity(new Vector(0, 0, 0));
        if (data != null) {
            data.clearExpectedVelocity();
            data.setSpeedBuffer(0.0);
            data.getTimerTracker().reset();
            data.updatePacketPosition(cancelTarget.getX(), cancelTarget.getY(), cancelTarget.getZ());
            if (data.getPhysicsState() != null) {
                data.getPhysicsState().reset(cancelTarget);
                data.getPhysicsState().clearPendingVelocities();
            }
        }

        setbackNotifier.postSetbackSuccess(player, context, check.getName(), currentVl, reason, "DIRECT_CANCEL",
                SafeLocationFinder.formatLoc(from), SafeLocationFinder.formatLoc(cancelTarget), isAirborne,
                LegitimateLocation.from(cancelTarget, null, setbackManager.isGrounded(player, cancelTarget), false, null),
                setbackManager.getNotifyPlayerChannel(), setbackManager.getNotifyPlayerCooldownMs(), setbackManager.isNotifyAdminEnabled());
        return true;
    }
}

