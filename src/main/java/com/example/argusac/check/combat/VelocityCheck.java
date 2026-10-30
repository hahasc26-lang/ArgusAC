package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

/**
 * 击退速度检测 (VelocityCheck / Anti-KB)
 * 检测玩家受到外部击退或爆炸作用力时，非法消除垂直或水平击退位移的作弊行为
 */
public class VelocityCheck extends AbstractCheck {

    private double verticalRatio;
    private double horizontalRatio;

    public VelocityCheck(ArgusACPlugin plugin) {
        super(plugin, "Velocity", CheckType.COMBAT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        String path = "checks." + name + ".";
        this.verticalRatio = getChecksConfig().getDouble(path + "vertical-ratio", 0.85);
        this.horizontalRatio = getChecksConfig().getDouble(path + "horizontal-ratio", 0.80);
    }

    /**
     * 服务端向客户端派发物理击退速度
     */
    public void handleVelocity(Player player, PlayerVelocityEvent event) {
        if (!enabled || player == null) {
            return;
        }

        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data == null || data.isInGracePeriod()) {
            return;
        }

        Vector velocity = event.getVelocity();
        // 忽略微小的自然扰动速度
        if (velocity.lengthSquared() < 0.05) {
            return;
        }

        data.setExpectedVelocity(velocity);
    }

    /**
     * 在后续移动事件中校验玩家是否如期产生物理位移响应
     */
    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isInsideVehicle() || player.isFlying()) {
            data.setVelocityHandled(true);
            return;
        }

        long now = System.currentTimeMillis();
        // 刚重生或处于豁免期内，完全豁免击退检测
        if (data.isInGracePeriod() || (now - data.getLastRespawnTime() < 2500L)) {
            data.setVelocityHandled(true);
            return;
        }

        if (data.isVelocityHandled()) {
            return;
        }

        long timeSinceVelocity = now - data.getLastVelocityReceivedTime();

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        // 校验是否头顶撞墙、处于液体中或可攀爬物中
        com.example.argusac.compat.VersionAdapter adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        Block feet = from.getBlock();
        if (hasBlockAbove(from) || hasBlockAbove(to) || adapter.isLiquid(feet) || adapter.isClimbable(feet)) {
            data.setVelocityHandled(true);
            return;
        }

        Vector expected = data.getExpectedVelocity();
        if (expected == null) {
            data.setVelocityHandled(true);
            return;
        }

        double deltaY = to.getY() - from.getY();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double actualHoriz = Math.hypot(dx, dz);

        // 记录自收到击退以来在等待窗口内观察到的峰值响应位移，防止低延迟玩家在第 1 tick 已完成起跳、到窗口结束时已进入下落阶段被误判
        data.recordObservedVelocityResponse(deltaY, actualHoriz);
        double peakY = Math.max(deltaY, data.getMaxObservedVelocityY());
        double peakHoriz = Math.max(actualHoriz, data.getMaxObservedVelocityH());

        double expectedHorizSq = expected.getX() * expected.getX() + expected.getZ() * expected.getZ();
        double expectedHoriz = Math.sqrt(expectedHorizSq) * horizontalRatio;
        double expectedY = expected.getY() * verticalRatio;

        // 检查玩家水平方向是否贴靠墙壁、角落、栅栏或台阶 (撞墙会导致水平击退位移被物理阻挡为 0)
        boolean horizontallyObstructed = hasHorizontalObstruction(from, adapter) || hasHorizontalObstruction(to, adapter);

        // 若处于下坠重力主导状态 (deltaY < -0.05)，微弱垂直击退会被向下惯性抵消
        boolean falling = deltaY < -0.05;
        boolean satisfiedY = (expected.getY() <= 0.20) || (falling && expected.getY() <= 0.40) || (peakY >= Math.max(0.08, expectedY * 0.65));
        boolean satisfiedH = horizontallyObstructed || (expectedHorizSq <= 0.04) || (peakHoriz >= Math.max(0.08, expectedHoriz * 0.65));

        // 1. 玩家已产生响应位移，判定击退成功响应
        if (satisfiedY && satisfiedH) {
            data.setVelocityHandled(true);
            return;
        }

        // 2. 网络往返延迟等待保护 (Ping Latency Window)
        // 击退数据包下发到客户端并由客户端发回移动数据包需要至少 1 个 RTT 周期，期间必须静待响应，绝不能将尚未收到击退的位移误判为 Anti-KB
        int ping = adapter.getPing(player);
        long minWaitTime = Math.max(150L, ping + 60L);

        if (timeSinceVelocity < minWaitTime) {
            return;
        }

        // 3. 超过延迟等待窗口且在 600ms 内，如果依然完全没有击退位移响应，方才判定为非法消除击退 (Anti-KB)
        // 仅在期望垂直速度显著且未受下坠重力主导时检测
        if (!satisfiedY && expected.getY() > 0.35 && !falling && peakY < 0.01) {
            flag(player, 2, String.format("Anti-KB(V) deltaY=%.2f, expected=%.2f, ping=%d", peakY, expected.getY(), ping), event);
        }
        // 水平击退：若为微弱受击 (如远距离箭矢轻微擦中且玩家正在反向跑动冲刺对抗) 提高容差，避免正常走位误判
        if (!satisfiedH && expectedHorizSq > 0.09 && peakHoriz < 0.01) {
            flag(player, 2, String.format("Anti-KB(H) horiz=%.3f, expected=%.3f, ping=%d", peakHoriz, Math.sqrt(expectedHorizSq), ping), event);
        }

        data.setVelocityHandled(true);
    }

    private boolean hasHorizontalObstruction(Location loc, com.example.argusac.compat.VersionAdapter adapter) {
        if (loc == null || loc.getWorld() == null) {
            return false;
        }
        double[][] offsets = {
                {0.42, 0}, {-0.42, 0}, {0, 0.42}, {0, -0.42},
                {0.35, 0.35}, {-0.35, 0.35}, {0.35, -0.35}, {-0.35, -0.35}
        };
        for (double[] off : offsets) {
            Block low = loc.clone().add(off[0], 0.1, off[1]).getBlock();
            Block mid = loc.clone().add(off[0], 1.0, off[1]).getBlock();
            if (adapter.isSolidBlock(low) || adapter.isSolidBlock(mid)
                    || adapter.isStandable(low) || adapter.isStandable(mid)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBlockAbove(Location loc) {
        Block head = loc.clone().add(0, 1.8, 0).getBlock();
        Block ceiling = loc.clone().add(0, 2.0, 0).getBlock();
        com.example.argusac.compat.VersionAdapter adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        return adapter.isSolidBlock(head) || adapter.isSolidBlock(ceiling)
                || adapter.isClimbable(head) || adapter.isClimbable(ceiling);
    }
}
