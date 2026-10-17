package com.example.aegisac.check;

import com.example.aegisac.AegisACPlugin;
import com.example.aegisac.api.Check;
import com.example.aegisac.check.combat.AutoClickerCheck;
import com.example.aegisac.check.combat.KillAuraCheck;
import com.example.aegisac.check.combat.ReachCheck;
import com.example.aegisac.check.combat.VelocityCheck;
import com.example.aegisac.check.movement.ElytraFlyCheck;
import com.example.aegisac.check.movement.FlyCheck;
import com.example.aegisac.check.movement.InventoryMoveCheck;
import com.example.aegisac.check.movement.NoFallCheck;
import com.example.aegisac.check.movement.SpeedCheck;
import com.example.aegisac.check.packet.BadPacketsCheck;
import com.example.aegisac.check.world.FastBreakCheck;
import com.example.aegisac.check.world.NukerCheck;
import com.example.aegisac.check.world.ScaffoldCheck;
import com.example.aegisac.data.PlayerData;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * 检测模块集中管理器 (CheckManager)
 * 负责各检测类的实例化注册、生命周期管理与批量热重载
 */
public class CheckManager {

    private final AegisACPlugin plugin;
    private final Map<String, Check> checkMap = new LinkedHashMap<>();

    private SpeedCheck speedCheck;
    private FlyCheck flyCheck;
    private NoFallCheck noFallCheck;
    private InventoryMoveCheck inventoryMoveCheck;
    private ElytraFlyCheck elytraFlyCheck;

    private ReachCheck reachCheck;
    private AutoClickerCheck autoClickerCheck;
    private KillAuraCheck killAuraCheck;
    private VelocityCheck velocityCheck;

    private FastBreakCheck fastBreakCheck;
    private NukerCheck nukerCheck;
    private ScaffoldCheck scaffoldCheck;

    private BadPacketsCheck badPacketsCheck;

    public CheckManager(AegisACPlugin plugin) {
        this.plugin = plugin;
        this.registerAll();
    }

    private void registerAll() {
        // 移动类
        register(speedCheck = new SpeedCheck(plugin));
        register(flyCheck = new FlyCheck(plugin));
        register(noFallCheck = new NoFallCheck(plugin));
        register(inventoryMoveCheck = new InventoryMoveCheck(plugin));
        register(elytraFlyCheck = new ElytraFlyCheck(plugin));

        // 战斗类
        register(reachCheck = new ReachCheck(plugin));
        register(autoClickerCheck = new AutoClickerCheck(plugin));
        register(killAuraCheck = new KillAuraCheck(plugin));
        register(velocityCheck = new VelocityCheck(plugin));

        // 世界交互类
        register(fastBreakCheck = new FastBreakCheck(plugin));
        register(nukerCheck = new NukerCheck(plugin));
        register(scaffoldCheck = new ScaffoldCheck(plugin));

        // 数据包类
        register(badPacketsCheck = new BadPacketsCheck(plugin));
    }

    private void register(Check check) {
        checkMap.put(check.getName().toLowerCase(), check);
    }

    public Check getCheck(String name) {
        if (name == null) {
            return null;
        }
        return checkMap.get(name.toLowerCase());
    }

    public Collection<Check> getAllChecks() {
        return checkMap.values();
    }

    public void reloadAll() {
        for (Check check : checkMap.values()) {
            check.reloadConfig();
        }
    }

    // Typed Getters
    public SpeedCheck getSpeedCheck() { return speedCheck; }
    public FlyCheck getFlyCheck() { return flyCheck; }
    public NoFallCheck getNoFallCheck() { return noFallCheck; }
    public InventoryMoveCheck getInventoryMoveCheck() { return inventoryMoveCheck; }
    public ElytraFlyCheck getElytraFlyCheck() { return elytraFlyCheck; }

    public ReachCheck getReachCheck() { return reachCheck; }
    public AutoClickerCheck getAutoClickerCheck() { return autoClickerCheck; }
    public KillAuraCheck getKillAuraCheck() { return killAuraCheck; }
    public VelocityCheck getVelocityCheck() { return velocityCheck; }

    public FastBreakCheck getFastBreakCheck() { return fastBreakCheck; }
    public NukerCheck getNukerCheck() { return nukerCheck; }
    public ScaffoldCheck getScaffoldCheck() { return scaffoldCheck; }

    public BadPacketsCheck getBadPacketsCheck() { return badPacketsCheck; }

    /**
     * 集中判断玩家是否处于鞘翅滑翔状态，用于 Fly, Speed, NoFall 等移动检测模块的显式互斥
     * 当为 true 时，FlyCheck、SpeedCheck、NoFallCheck 必须直接跳过判定，统一移交 ElytraFly 模块处理
     *
     * @param player 目标玩家
     * @param data 玩家数据
     * @return 是否处于滑翔互斥状态
     */
    public boolean isGlidingExempt(Player player, PlayerData data) {
        if (player == null || data == null) {
            return false;
        }
        return com.example.aegisac.compat.VersionAdapterFactory.getAdapter().isGliding(player) || data.isElytraGliding();
    }
}
