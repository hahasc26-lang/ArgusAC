package com.example.argusac.listener;

import com.example.argusac.ArgusACPlugin;
import com.example.argusac.data.PlayerData;
import com.example.argusac.manager.CheckManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家交互与物品监听器 (PlayerInteractListener)
 * 统一调度方块交互 (Break, Place, Damage)、物品使用 (FastEat, FastBow)、背包操作与滑翔装备防护。
 */
public class PlayerInteractListener implements Listener {

    private final ArgusACPlugin plugin;
    private final CheckManager checkManager;

    public PlayerInteractListener(ArgusACPlugin plugin) {
        this.plugin = plugin;
        this.checkManager = plugin.getCheckManager();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.isPlayerExempt(event.getPlayer())) {
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            checkManager.getElytraFlyCheck().handleInteract(event.getPlayer(), data, event);
        }
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            boolean mainHandConsumable = false;
            boolean mainHandBow = false;
            if (event.getItem() != null) {
                String mainType = event.getItem().getType().name();
                mainHandBow = mainType.equals("BOW") || mainType.equals("CROSSBOW");
                mainHandConsumable = checkManager.getFastEatCheck().isConsumable(event.getItem());
                if (mainHandBow) {
                    checkManager.getFastBowCheck().handleBowStart(event.getPlayer(), event.getItem());
                }
                if (mainHandConsumable) {
                    checkManager.getFastEatCheck().handleItemStartUse(event.getPlayer(), event.getItem());
                }
            }
            Player p = event.getPlayer();
            if (p.getInventory() != null && p.getInventory().getItemInOffHand() != null) {
                ItemStack offItem = p.getInventory().getItemInOffHand();
                if (!mainHandBow) {
                    checkManager.getFastBowCheck().handleBowStart(p, offItem);
                }
                // 即使主手持有无箭矢的弓/弩或其它非食用物品，玩家依然可以使用副手食物进食，必须独立打点防止漏记导致 Instant Consume 误报
                if (!mainHandConsumable) {
                    checkManager.getFastEatCheck().handleItemStartUse(p, offItem);
                }
            }
        } else if (action == Action.LEFT_CLICK_BLOCK) {
            if (data != null) {
                data.startDigging(System.currentTimeMillis());
            }
        } else if (action == Action.LEFT_CLICK_AIR) {
            recordClickIfFallback(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.startDigging(System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.stopDigging(System.currentTimeMillis());
        }
        checkManager.getFastBreakCheck().handleBreak(player, event);
        if (event.isCancelled()) {
            return;
        }
        checkManager.getNukerCheck().handleBreak(player, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.isPlayerExempt(player)) {
            return;
        }
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null) {
            data.setLastPlaceTime(System.currentTimeMillis());
        }
        checkManager.getScaffoldCheck().handlePlace(player, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.setInventoryOpen(true, player.getLocation());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null) {
                data.setInventoryOpen(false, null);
                data.markContainerClosed(System.currentTimeMillis());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        PlayerData data = plugin.getPlayerManager().getPlayerData(event.getPlayer());
        if (data != null) {
            data.recordSlotSwitch(event.getNewSlot());
        }
        checkManager.getFastEatCheck().handleItemStopUse(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            PlayerData data = plugin.getPlayerManager().getPlayerData(player);
            if (data != null && (player.isGliding() || data.isElytraGliding())) {
                boolean isChestplateSlot = (event.getSlotType() == InventoryType.SlotType.ARMOR
                        && event.getSlot() == 38)
                        || event.getRawSlot() == 6;
                boolean isArmorRelated = isChestplateSlot
                        || event.getSlotType() == InventoryType.SlotType.ARMOR
                        || event.getClick() == ClickType.SWAP_OFFHAND
                        || event.getClick() == ClickType.NUMBER_KEY;

                if (isArmorRelated) {
                    long now = System.currentTimeMillis();
                    data.getElytraData().recordArmorSwap(now);

                    boolean armorSwapCancel = plugin.getChecksConfig()
                            .getBoolean("checks.ElytraFly.glide-state.armor-swap-cancel", true);
                    if (isChestplateSlot && armorSwapCancel) {
                        // 正在空中滑翔时强行换脱胸甲：当帧直接阻断该交互包，剥离滑翔姿态并下发协议同步包锚定
                        event.setCancelled(true);
                        player.setGliding(false);
                        data.getElytraData().resetGlideState();
                        data.resetMaceFallState(player.getLocation().getY());
                        plugin.getSetbackManager().sendPositionSyncPacket(player, player.getLocation());
                        return;
                    }

                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline() && (player.isGliding() || data.isElytraGliding())) {
                            ItemStack chest = player.getInventory().getChestplate();
                            if (chest == null || !"ELYTRA".equals(chest.getType().name())) {
                                player.setGliding(false);
                                data.getElytraData().resetGlideState();
                                data.resetMaceFallState(player.getLocation().getY());
                                plugin.getSetbackManager().sendPositionSyncPacket(player, player.getLocation());
                            }
                        }
                    });
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        checkManager.getFastEatCheck().handleItemConsume(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        checkManager.getFastEatCheck().handleItemStopUse(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.getPlayerManager().getPlayerData(player);
        if (data != null && (player.isGliding() || data.isElytraGliding())) {
            data.getElytraData().recordArmorSwap(System.currentTimeMillis());
        }
        checkManager.getFastEatCheck().handleItemStopUse(player);
    }

    /**
     * 仅在 PacketEvents 底层数据包协议管线未就绪时，降级采用 Bukkit 事件统计 CPS
     */
    private void recordClickIfFallback(Player player) {
        if (plugin.getPacketPipelineManager() == null || !plugin.getPacketPipelineManager().isActive()) {
            checkManager.getAutoClickerCheck().handleClick(player);
        }
    }
}

