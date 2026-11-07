package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.compat.VersionAdapter;
import com.example.argusac.compat.VersionAdapterFactory;
import com.example.argusac.data.PlayerData;
import com.example.argusac.manager.CheckManager;
import com.example.argusac.model.VelocitySourceType;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

/**
 * 玩家移动与物理监听器 (PlayerMoveListener)
 * 统一调度移动、传送校验、滞空、飞行、滑翔、载具与物理回退处理。
 */
public class PlayerMoveListener implements Listener {

    private final ArgusACPlugin plugin;
    private final CheckManager checkManager;

    public PlayerMoveListener(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.checkManager = plugin.getCheckManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTeleportCheck(PlayerTeleportEvent event) {
        // 浮点数异常检测拦截 (NaN / Infinite)
        Location to = event.getTo();
        if (to != null && (Double.isNaN(to.getX()) || Double.isNaN(to.getY()) || Double.isNaN(to.getZ())
                || Double.isInfinite(to.getX()) || Double.isInfinite(to.getY()) || Double.isInfinite(to.getZ()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (plugin.getSetbackManager().isSelfTeleport(player.getUniqueId())) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.setLastTeleportTime(System.currentTimeMillis());
            data.setAirTicks(0);
            data.setOnGround(false);
            data.resetSpeedTracking();
            data.getElytraData().resetAll();
            if (event.getTo() != null) {
                data.getPhysicsState().reset(event.getTo());
                data.getPhysicsState().clearPendingVelocities();
                data.resetMaceFallState(event.getTo().getY());
            }

            // 外部传送赋予合法速度豁免
            if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
                data.grantLegalVelocity(VelocitySourceType.ENDER_PEARL, 1000L);
            } else {
                data.grantLegalVelocity(VelocitySourceType.EXTERNAL_TELEPORT, 1500L);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityToggleGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (plugin.isPlayerExempt(player)) {
                return;
            }
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                checkManager.getElytraFlyCheck().handleToggleGlide(player, data, event);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.grantLegalVelocity(VelocitySourceType.VEHICLE, 1000L);
                data.getElytraData().resetGlideState();
                data.resetMaceFallState(player.getLocation().getY());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.grantLegalVelocity(VelocitySourceType.VEHICLE, 1000L);
                data.getElytraData().resetGlideState();
                data.resetMaceFallState(player.getLocation().getY());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleFlight(PlayerToggleFlightEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.setLastFlightToggleTime(System.currentTimeMillis());
            data.getElytraData().resetGlideState();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            checkManager.getVelocityCheck().handleVelocity(player, event);
            checkManager.getElytraFlyCheck().handleVelocity(player, data, event);
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null) {
            return;
        }

        // PlayerTeleportEvent extends PlayerMoveEvent. Teleports have their own validation
        // and lifecycle handling; never compare their destination against movement limits.
        if (event instanceof PlayerTeleportEvent) {
            data.resetSpeedTracking();
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        boolean posChanged = (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ());
        boolean rotChanged = (from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch());

        // 既无位置位移也无旋转变化时直接跳过
        if (!posChanged && !rotChanged) {
            return;
        }

        // 对齐 GrimAC 传送传输中 (Teleport In-Flight) 机制：
        // 服务端发出的位置重置包在客户端收到并回复 ACK 之前存在 1 个 RTT 延迟。
        // 期间来自客户端的旧移动包必须坚决阻断取消，严禁推动玩家位置或触发二次误判
        com.example.argusac.setback.PlayerSetbackContext setbackCtx = plugin.getSetbackManager()
                .getContext(player.getUniqueId());
        if (setbackCtx != null && setbackCtx.isTeleportInFlight()) {
            if (setbackCtx.matchesCorrection(to)) {
                setbackCtx.confirmTeleport(setbackCtx.getInFlightTeleportId());
            } else {
                Location inFlightTarget = setbackCtx.getTeleportInFlightTarget();
                if (inFlightTarget != null) {
                    event.setTo(inFlightTarget);
                }
                event.setCancelled(true);
                return;
            }
        }

        data.setFromLocation(from);
        data.setToLocation(to);
        if (!data.hasPacketLocation()) {
            data.setLastLookYaw(from.getYaw());
            data.setLastLookPitch(from.getPitch());
        }

        // 鲁棒多点与方块材质物理碰撞接地判定 (消除地毯、雪片、台阶、方块边缘悬空误判)
        boolean onGround = isGrounded(player, to);
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();

        // 双向接地校验：若多点探针处于微小接缝或台阶提升 (Step-Up) 处，只要客户端声明在地面且脚下 0.35 格内有物理承托面，均认定为接地
        if (!onGround && player.isOnGround()) {
            if (hasSupportingBlockBelow(to, adapter, 0.35)) {
                onGround = true;
            }
        }

        // 如果客户端声明离地且正在垂直向上起跳/升空，仅在脚下没有紧贴实体方块时认定为离地
        if (!player.isOnGround() && (to.getY() - from.getY()) > 0.05 && !hasSupportingBlockBelow(to, adapter, 0.15)) {
            onGround = false;
        }
        data.setLastOnGround(data.isOnGround());
        data.setOnGround(onGround);

        // 接地与流体/滞空状态维护 (水、岩浆等流体拥有原版浮力与阻力，攀爬物具备攀爬滞留能力，不计为真空空气滞空)
        boolean inLiquid = adapter.isInWater(player)
                || adapter.isSwimming(player)
                || adapter.isLiquid(to.getBlock())
                || adapter.isLiquid(from.getBlock())
                || adapter.isLiquid(to.clone().subtract(0, 0.4, 0).getBlock());
        boolean inClimbable = adapter.isClimbableAround(player, to) || adapter.isClimbableAround(player, from);
        Block under = to.clone().subtract(0, 0.5, 0).getBlock();
        if (onGround || inLiquid || inClimbable) {
            data.setAirTicks(0);
            data.incrementGroundTicks();
            data.resetMaceFallState(to.getY());
            // 落地且已脱离冰面与滑行记忆期，重置空中冰面起跳惯性
            if (!adapter.isIce(under) && !adapter.isSlime(under) && data.getIceTicks() <= 0) {
                data.setAirborneFromIce(false);
            }
        } else {
            data.setGroundTicks(0);
            data.updateMaceApex(to.getY());
            // 离地起跳帧：若脚下为冰/粘液或处于冰面记忆期，标记本次滞空继承冰面起跳大动量
            Block underFrom = from.clone().subtract(0, 0.5, 0).getBlock();
            if (data.getIceTicks() > 0 || adapter.isIce(underFrom) || adapter.isSlime(underFrom)) {
                data.setAirborneFromIce(true);
            }
        }

        // 冰面与粘液块/床滑行及弹射惯性追踪 (离开冰面后维持 4 tick 原版真实摩擦衰减)
        Block underFromBlock = from.clone().subtract(0, 0.5, 0).getBlock();
        if (adapter.isIce(under) || adapter.isSlime(under)) {
            data.setIceTicks(4);
        } else {
            data.decrementIceTicks();
        }
        if (adapter.isSlime(under) || isBounceBed(under) || isBounceBed(to.getBlock())
                || isBounceBed(from.getBlock())) {
            data.setSlimeTicks(25);
        } else {
            data.decrementSlimeTicks();
        }
        if (adapter.isSoulSand(under) || adapter.isSoulSand(underFromBlock)) {
            data.setSoulSpeedTicks(8);
        } else {
            data.decrementSoulSpeedTicks();
        }

        // 更新特殊环境标记供 Netty 线程无锁读取，并在主线程完成位移帧的 NoFall GroundSpoof 核验
        boolean specialEnv = inLiquid || onGround || inClimbable
                || adapter.isClimbable(to.getBlock()) || adapter.isClimbable(from.getBlock())
                || adapter.isStandable(to.getBlock()) || adapter.isStandable(under);
        data.setInSpecialEnvironment(specialEnv);
        if (posChanged && !checkManager.isGlidingExempt(player, data)
                && player.getGameMode() != org.bukkit.GameMode.CREATIVE
                && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                && !player.isFlying() && !player.getAllowFlight() && !player.isInsideVehicle()) {
            boolean groundSpoof = data.isClientOnGround() && data.getAirTicks() > 4 && !onGround
                    && !data.isSetbackImmune() && !data.hasAnyLegalVelocity() && !specialEnv
                    && data.getSlimeTicks() <= 0;
            if (data.recordNoFallSpoofPacket(groundSpoof)) {
                plugin.getViolationManager().handleViolation(player, checkManager.getNoFallCheck(),
                        1, String.format("Packet GroundSpoof airTicks=%d", data.getAirTicks()), event);
            }
        }

        // 推进确定性物理预测器状态机，维护实时残差缓冲区供移动与战斗联动裁决使用
        if (posChanged && plugin.getMovePredictor() != null && plugin.getMovePredictor().isEnabled()) {
            plugin.getMovePredictor().predict(
                    player, data,
                    to.getX(), to.getY(), to.getZ(),
                    to.getYaw(), to.getPitch(),
                    data.isClientOnGround());
        }

        Location originalTo = to.clone();

        // 1. 数据包完整性与异常角度检测 (即使仅原地旋转视角也必须拦截非法 Pitch/NaN)
        checkManager.getBadPacketsCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
            return;
        }

        // 核心防空中悬停：若无空间位置位移 (仅为视角转动或客户端发包锁定原点)：
        // 1. 若玩家处于地面上，安全结束，无需执行后续空间物理位移检测；
        // 2. 若玩家处于空中 (!onGround)，空间位置保持不动正是最典型的空中定点悬浮 (Hover)！
        // 绝对不能跳过，必须强制送入 FlyCheck 严格判定滞空与重力下坠状态！
        if (!posChanged) {
            if (!onGround && !checkManager.isGlidingExempt(player, data)) {
                checkManager.getFlyCheck().handleMove(player, data, event);
                if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                    handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                }
            }
            return;
        }

        // 鞘翅滑翔检测 (ElytraFly) 与传统陆地/悬空移动检测 (Speed, Fly, NoFall) 显式互斥
        if (checkManager.isGlidingExempt(player, data)) {
            // 滑翔状态由 ElytraFly 集中全面接管 (包含 ElytraSpeed, ElytraAscend, ElytraHover, ElytraTimer 等)
            checkManager.getElytraFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }
        } else {
            // 2. 移动速度检测
            checkManager.getSpeedCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }

            // 3. 飞行与非法滞空检测
            checkManager.getFlyCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }

            // 4. 水上行走与水面虚假悬浮检测 (JesusCheck)
            checkManager.getJesusCheck().handleMove(player, data, event);
            if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
                handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
                return;
            }
        }

        // 5. 背包移动检测
        checkManager.getInventoryMoveCheck().handleMove(player, data, event);
        if (event.isCancelled() || hasSetbackOccurred(originalTo, event.getTo())) {
            handleSetbackOrCancellation(player, data, event.getTo() != null ? event.getTo() : from);
            return;
        }

        // 6. 击退位移追踪
        checkManager.getVelocityCheck().handleMove(player, data, event);

        // 7. 合法移动状态更新与锁定锚点解除：
        // 若本 tick 移动完全合法且未触发任何取消/回退，且玩家安全处于稳固地面上，
        // 在距最后一次取消违规一定安全窗口 (400ms) 之后，平稳解除原位锁定锚点
        if (!event.isCancelled() && !hasSetbackOccurred(originalTo, event.getTo())) {
            if (onGround || inLiquid) {
                com.example.argusac.setback.PlayerSetbackContext setbackContext = plugin.getSetbackManager()
                        .getContext(player.getUniqueId());
                if (setbackContext != null && setbackContext.getLockedAnchorLocation() != null) {
                    // 对标 GrimAC 锚点守恒法则：必须完整度过 2000ms 违规锁死周期，且在地面稳定合法移动，才允许解除锁定锚点！
                    if (!setbackContext.isUnderViolationLock()) {
                        setbackContext.clearLockedAnchor();
                    }
                }
            }
        }
    }

    private void handleSetbackOrCancellation(Player player, PlayerData data, Location finalLoc) {
        if (finalLoc != null && isGrounded(player, finalLoc)) {
            data.setAirTicks(0);
            data.setOnGround(true);
        } else {
            data.setOnGround(false);
        }
        if (finalLoc != null) {
            data.setToLocation(finalLoc);
            if (data.getPhysicsState() != null) {
                data.getPhysicsState().reset(finalLoc);
            }
        }
    }

    private boolean hasSetbackOccurred(Location origTo, Location currentTo) {
        if (currentTo == null) {
            return true;
        }
        return origTo.getX() != currentTo.getX()
                || origTo.getY() != currentTo.getY()
                || origTo.getZ() != currentTo.getZ();
    }

    /**
     * 鲁棒多点与环境方块物理碰撞接地判定
     */
    private boolean isGrounded(Player player, Location loc) {
        if (player.isInsideVehicle()) {
            return true;
        }
        VersionAdapter adapter = VersionAdapterFactory.getAdapter();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        org.bukkit.World world = loc.getWorld();
        if (world == null)
            return false;

        // 探测中心点、四个底角及四边中点 (潜行搭桥边缘防跌落悬挑可达 0.42，普通站立 AABB 半宽 0.30)
        double r = player.isSneaking() ? 0.42 : 0.30;
        double[][] points = {
                { 0, 0 },
                { -r, -r },
                { r, -r },
                { -r, r },
                { r, r },
                { -r, 0 },
                { r, 0 },
                { 0, -r },
                { 0, r }
        };

        for (double[] p : points) {
            Location checkLoc = new Location(world, x + p[0], y - 0.05, z + p[1]);
            Block b = checkLoc.getBlock();
            if (adapter.isStandable(b) || adapter.isClimbable(b)) {
                return true;
            }
            // 针对栅栏 (Fence) 与石墙 (Wall)：碰撞箱高 1.5 格，脚下方块在 y - 0.6 处
            Block fenceBlock = checkLoc.clone().subtract(0, 0.55, 0).getBlock();
            String name = fenceBlock.getType().name();
            if (name.contains("FENCE") || name.contains("WALL") || name.contains("BAMBOO")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSupportingBlockBelow(Location loc, VersionAdapter adapter, double maxDepth) {
        if (loc == null || loc.getWorld() == null)
            return false;
        for (double d = 0.05; d <= maxDepth; d += 0.10) {
            Block b = loc.clone().subtract(0, d, 0).getBlock();
            if (adapter.isStandable(b)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBounceBed(Block block) {
        return block != null && block.getType().name().endsWith("_BED");
    }
}

