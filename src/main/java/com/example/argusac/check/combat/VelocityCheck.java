package com.example.argusac.check.combat;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
        this.verticalRatio = plugin.getConfig().getDouble(path + "vertical-ratio", 0.85);
        this.horizontalRatio = plugin.getConfig().getDouble(path + "horizontal-ratio", 0.80);
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

        if (data.isVelocityHandled()) {
            return;
        }

        long now = System.currentTimeMillis();
        // 如果距离收到速度已经超过 600ms，说明该次击退已过期
        if (now - data.getLastVelocityReceivedTime() > 600L) {
            data.setVelocityHandled(true);
            return;
        }

        Vector expected = data.getExpectedVelocity();
        if (expected == null) {
            data.setVelocityHandled(true);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        double deltaY = to.getY() - from.getY();

        // 校验是否头顶撞墙或被方块阻挡
        if (hasBlockAbove(from)) {
            data.setVelocityHandled(true);
            return;
        }

        // 垂直击退检测：若预期向上受到击退 (expected.getY() > 0.2)，但实际位移几乎为 0
        if (expected.getY() > 0.20) {
            double expectedY = expected.getY() * verticalRatio;
            if (deltaY < 0.01 && deltaY < expectedY) {
                flag(player, 2, String.format("Anti-KB deltaY=%.2f, expected=%.2f", deltaY, expected.getY()), event);
            }
        }

        // 水平击退检测：若预期有水平击退速度，但实际水平位移极小
        double expectedHorizSq = expected.getX() * expected.getX() + expected.getZ() * expected.getZ();
        if (expectedHorizSq > 0.04) {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double actualHoriz = Math.sqrt(dx * dx + dz * dz);
            double expectedHoriz = Math.sqrt(expectedHorizSq) * horizontalRatio;
            if (actualHoriz < 0.01 && actualHoriz < expectedHoriz) {
                flag(player, 1, String.format("Anti-KB(H) horiz=%.3f, expected=%.3f", actualHoriz, Math.sqrt(expectedHorizSq)), event);
            }
        }

        data.setVelocityHandled(true);
    }

    private boolean hasBlockAbove(Location loc) {
        Block head = loc.clone().add(0, 1.8, 0).getBlock();
        Block ceiling = loc.clone().add(0, 2.0, 0).getBlock();
        return head.getType().isSolid() || ceiling.getType().isSolid()
                || head.getType() == Material.COBWEB || ceiling.getType() == Material.COBWEB;
    }
}
