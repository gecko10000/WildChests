package xyz.wildseries.wildchests.listeners;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Hopper;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import xyz.wildseries.wildchests.Locale;
import xyz.wildseries.wildchests.WildChestsPlugin;
import xyz.wildseries.wildchests.api.objects.chests.Chest;
import xyz.wildseries.wildchests.api.objects.chests.LinkedChest;
import xyz.wildseries.wildchests.api.objects.data.ChestData;
import xyz.wildseries.wildchests.api.objects.data.InventoryData;
import xyz.wildseries.wildchests.objects.Materials;
import xyz.wildseries.wildchests.objects.WInventory;
import xyz.wildseries.wildchests.objects.WLocation;
import xyz.wildseries.wildchests.utils.ChestUtils;
import xyz.wildseries.wildchests.utils.ItemUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("unused")
public final class InventoryListener implements Listener {

    private final WildChestsPlugin plugin;

    private final Map<UUID, Location> playerChests = new HashMap<>();
    private final Map<UUID, InventoryData> buyNewPage = new HashMap<>();
    private final Set<UUID> openDifferentPage = new HashSet<>();
    private Inventory guiConfirm;

    public InventoryListener(WildChestsPlugin plugin){
        this.plugin = plugin;
        initGUIConfirm();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestOpen(PlayerInteractEvent e){
        if(e.getAction() != Action.RIGHT_CLICK_BLOCK || (e.getItem() != null && e.getItem().getType().isBlock() && e.getPlayer().isSneaking()))
            return;

        if(buyNewPage.containsKey(e.getPlayer().getUniqueId())){
            e.setCancelled(true);
            return;
        }

        Chest chest = plugin.getChestsManager().getChest(e.getClickedBlock().getLocation());

        if(chest == null)
            return;

        e.setCancelled(true);

        e.getPlayer().openInventory(chest.getPage(0));
        if(e.getPlayer().getGameMode() != GameMode.SPECTATOR)
            plugin.getNMSAdapter().playChestAction(e.getClickedBlock().getLocation(), true);

        if(chest instanceof LinkedChest && e.getPlayer().getGameMode() != GameMode.SPECTATOR){
            ((LinkedChest) chest).getAllLinkedChests()
                    .forEach(linkedChest -> plugin.getNMSAdapter().playChestAction(linkedChest.getLocation(), true));
        }

        playerChests.put(e.getPlayer().getUniqueId(), chest.getLocation());
    }

    @EventHandler
    public void onChestClose(InventoryCloseEvent e){
        if(!playerChests.containsKey(e.getPlayer().getUniqueId()))
            return;

        Chest chest = plugin.getChestsManager().getChest(playerChests.get(e.getPlayer().getUniqueId()));

        //Making sure it's still a valid chest
        if(chest == null) {
            playerChests.remove(e.getPlayer().getUniqueId());
            return;
        }

        if(buyNewPage.containsKey(e.getPlayer().getUniqueId()))
            return;

        if(openDifferentPage.contains(e.getPlayer().getUniqueId())) {
            if(e.getPlayer().getGameMode() != GameMode.SPECTATOR)
                Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getNMSAdapter().playChestAction(playerChests.get(e.getPlayer().getUniqueId()), true), 1L);
            openDifferentPage.remove(e.getPlayer().getUniqueId());
            return;
        }

        int size = e.getViewers().size();

        for(HumanEntity humanEntity : e.getViewers()){
            if(humanEntity.getGameMode() == GameMode.SPECTATOR){
                size--;
            }
        }

        if(size > 1)
            return;

        if(chest.getData().isAutoCrafter()){
            ChestUtils.tryCraftChest(chest);
        }

        if(chest.getData().isSellMode()){
            ChestUtils.trySellChest(chest);
        }

        if(chest instanceof LinkedChest){
            ((LinkedChest) chest).getAllLinkedChests()
                    .forEach(linkedChest -> plugin.getNMSAdapter().playChestAction(linkedChest.getLocation(), false));
        }

        if(e.getPlayer().getGameMode() != GameMode.SPECTATOR)
            plugin.getNMSAdapter().playChestAction(chest.getLocation(), false);

        playerChests.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPageMove(InventoryClickEvent e){
        if(e.getSlotType() != InventoryType.SlotType.OUTSIDE || !playerChests.containsKey(e.getWhoClicked().getUniqueId()))
            return;

        Chest chest = plugin.getChestsManager().getChest(playerChests.get(e.getWhoClicked().getUniqueId()));

        //Making sure it's still a valid chest
        if(chest == null){
            playerChests.remove(e.getWhoClicked().getUniqueId());
            return;
        }

        ChestData chestData = chest.getData();
        int index = chest.getPageIndex(e.getWhoClicked().getOpenInventory().getTopInventory());

        if(e.getClick() == ClickType.LEFT){
            if(index != 0){
                openDifferentPage.add(e.getWhoClicked().getUniqueId());
                e.getWhoClicked().openInventory(chest.getPage(index - 1));
            }
        }

        else if(e.getClick() == ClickType.RIGHT){
            if(index + 1 < chest.getPagesAmount()){
                openDifferentPage.add(e.getWhoClicked().getUniqueId());
                e.getWhoClicked().openInventory(chest.getPage(index + 1));
            }else if(chestData.getPagesData().containsKey(++index + 1)){
                InventoryData inventoryData = chestData.getPagesData().get(index + 1);
                buyNewPage.put(e.getWhoClicked().getUniqueId(), inventoryData);
                if(plugin.getSettings().confirmGUI){
                    e.getWhoClicked().openInventory(guiConfirm);
                }else {
                    Locale.EXPAND_CHEST.send(e.getWhoClicked(), inventoryData.getPrice());
                    e.getWhoClicked().closeInventory();
                }
            }
        }

    }

    @EventHandler
    public void onPlayerBuyConfirm(AsyncPlayerChatEvent e){
        if(!buyNewPage.containsKey(e.getPlayer().getUniqueId()))
            return;

        e.setCancelled(true);

        try {
            Chest chest = plugin.getChestsManager().getChest(playerChests.get(e.getPlayer().getUniqueId()));
            ChestData chestData = chest.getData();
            InventoryData inventoryData = buyNewPage.get(e.getPlayer().getUniqueId());
            int pageIndex = 0;

            while (chest.getPagesAmount() > pageIndex)
                pageIndex++;

            if (e.getMessage().equalsIgnoreCase("confirm")) {
                if (plugin.getProviders().transactionSuccess(e.getPlayer(), inventoryData.getPrice())) {
                    Locale.EXPAND_PURCHASED.send(e.getPlayer());
                    chest.setPage(pageIndex++, WInventory.of(chestData.getDefaultSize(), inventoryData.getTitle()).getInventory());
                } else {
                    Locale.EXPAND_FAILED.send(e.getPlayer());
                }
            } else {
                Locale.EXPAND_FAILED.send(e.getPlayer());
            }

            e.getPlayer().openInventory(chest.getPage(--pageIndex));
        }catch(Exception ex){
            Locale.EXPAND_FAILED_CHEST_BROKEN.send(e.getPlayer());
        }

        buyNewPage.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerBuyConfirm(InventoryClickEvent e){
        if(!buyNewPage.containsKey(e.getWhoClicked().getUniqueId()))
            return;

        e.setCancelled(true);
        Player player = (Player) e.getWhoClicked();

        try {
            Chest chest = plugin.getChestsManager().getChest(playerChests.get(player.getUniqueId()));
            ChestData chestData = chest.getData();
            InventoryData inventoryData = buyNewPage.get(player.getUniqueId());
            int pageIndex = 0;

            while (chest.getPagesAmount() > pageIndex)
                pageIndex++;

            if(e.getRawSlot() == 4){
                if (plugin.getProviders().transactionSuccess(player, inventoryData.getPrice())) {
                    Locale.EXPAND_PURCHASED.send(player);
                    chest.setPage(pageIndex++, WInventory.of(chestData.getDefaultSize(), inventoryData.getTitle()).getInventory());
                } else {
                    Locale.EXPAND_FAILED.send(player);
                }
            } else if(e.getRawSlot() == 0){
                Locale.EXPAND_FAILED.send(player);
            } else{
                return;
            }

            player.openInventory(chest.getPage(--pageIndex));
        }catch(Exception ex){
            Locale.EXPAND_FAILED_CHEST_BROKEN.send(player);
        }

        buyNewPage.remove(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerBuyConfirm(InventoryCloseEvent e) {
        if (e.getInventory().getTitle().equals(guiConfirm.getTitle())) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (buyNewPage.containsKey(e.getPlayer().getUniqueId()))
                    e.getPlayer().openInventory(e.getInventory());
            }, 1L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent e){
        if(e.getSource().getType() != InventoryType.HOPPER || e.getDestination().getType() != InventoryType.CHEST)
            return;

        if(!(e.getDestination().getHolder() instanceof org.bukkit.block.Chest))
            return;

        org.bukkit.block.Chest bukkitChest = (org.bukkit.block.Chest) e.getDestination().getHolder();
        Chest chest = plugin.getChestsManager().getChest(bukkitChest.getLocation());

        if(chest == null)
            return;

        e.setCancelled(true);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if(ItemUtils.addToChest(chest, e.getItem())) {
                e.getSource().removeItem(e.getItem());
                ((Hopper) e.getSource().getHolder()).update();

                WLocation location = WLocation.of(chest.getLocation());

                if (chest.getData().isAutoCrafter()) {
                    ChestUtils.tryCraftChest(chest);
                }

                if (chest.getData().isSellMode()) {
                    ChestUtils.trySellChest(chest);
                }

            }
        }, 1L);
    }

    private void initGUIConfirm(){
        guiConfirm = Bukkit.createInventory(null, InventoryType.HOPPER, ChatColor.BOLD + "    Expand Confirmation");

        ItemStack denyButton = Materials.RED_STAINED_GLASS_PANE.toBukkitItem();
        ItemMeta denyMeta = denyButton.getItemMeta();
        denyMeta.setDisplayName("" + ChatColor.RED + ChatColor.BOLD + "DENY");
        denyButton.setItemMeta(denyMeta);

        guiConfirm.setItem(0, denyButton);

        ItemStack confirmButton = Materials.GREEN_STAINED_GLASS_PANE.toBukkitItem();
        ItemMeta confirmMeta = confirmButton.getItemMeta();
        confirmMeta.setDisplayName("" + ChatColor.GREEN + ChatColor.BOLD + "CONFIRM");
        confirmButton.setItemMeta(confirmMeta);

        guiConfirm.setItem(4, confirmButton);

        ItemStack blankButton = Materials.BLACK_STAINED_GLASS_PANE.toBukkitItem();
        ItemMeta blankMeta = blankButton.getItemMeta();
        blankMeta.setDisplayName("" + ChatColor.WHITE);
        blankButton.setItemMeta(blankMeta);

        guiConfirm.setItem(1, blankButton);
        guiConfirm.setItem(2, blankButton);
        guiConfirm.setItem(3, blankButton);
    }

}