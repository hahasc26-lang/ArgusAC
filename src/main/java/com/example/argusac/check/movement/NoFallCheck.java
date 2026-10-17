package com.example.argusac.check.movement;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.api.CheckType;
import com.example.argusac.check.AbstractCheck;
import com.example.argusac.data.PlayerData;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 虚假接地与免摔检测 (NoFallCheck)
 * 检查客户端伪造 onGround 状态以避免摔落伤害作弊行为
 */
public class NoFallCheck extends AbstractCheck {

    public NoFallCheck(ArgusACPlugin plugin) {
        super(plugin, "NoFall", CheckType.MOVEMENT);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
    }

    public void handleMove(Player player, PlayerData data, PlayerMoveEvent event) {
        if (!enabled || player == null || data == null) {
            return;
        }

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR
                || player.isFlying() || player.isInsideVehicle()) {
            return;
        }

        // 鞘翅滑翔显式互斥：滑翔状态完全移交 ElytraFly 模块集中判定，NoFall 模块不判定
        if (plugin.getCheckManager().isGlidingExempt(player, data)) {
            return;
        }

        if (data.isSetbackImmune() || data.hasAnyLegalVelocity() || data.isInGracePeriod()) {
            return;
        }

        Location to = event.getTo();
        if (to == null) {
            return;
        }

        // 客户端声明处于地面（读取 PlayerData 中由物理检测同步的接地状态）
        boolean clientGround = data.isOnGround();

        if (clientGround) {
            // 服务端真实碰撞体与脚下方块物理检查
            boolean serverGround = checkPhysicalGround(to);

            // 客户端声称接地，但实际脚下及周围悬空且不在水中、蜘蛛网、梯子中
            if (!serverGround && !isInLiquidOrClimbable(to)) {
                // 如果还处于高坠落中，极高置信度 NoFall
                if (player.getFallDistance() > 1.5f || event.getFrom().getY() - to.getY() > 0.4) {
                    flag(player, 1, String.format("Spoofed Ground fallDist=%.1f", player.getFallDistance()), event);
                }
            }
        }
    }

    private boolean checkPhysicalGround(Location loc) {
        Location check = loc.clone().subtract(0, 0.15, 0);
        com.example.argusac.compat.VersionAdapter adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        // 采样脚下 3x3 范围
        for (double dx = -0.3; dx <= 0.3; dx += 0.3) {
            for (double dz = -0.3; dz <= 0.3; dz += 0.3) {
                Block b = check.clone().add(dx, 0, dz).getBlock();
                if (adapter.isStandable(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInLiquidOrClimbable(Location loc) {
        Block b = loc.getBlock();
        com.example.argusac.compat.VersionAdapter adapter = com.example.argusac.compat.VersionAdapterFactory.getAdapter();
        return adapter.isLiquid(b) || adapter.isClimbable(b);
    }
}
