package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.managers.ExporterManager;

import java.util.*;

public class ExporterGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location exporterLocation;
    private final String exporterId;
    private final String networkId;
    private final Inventory inventory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<Integer, ItemStack> slotToFilterItem = new HashMap<>();
    private final List<ItemStack> currentFilterItems = new ArrayList<>();

    public ExporterGUI(MassStorageServer plugin, Location exporterLocation, String exporterId, String networkId) {
        this.plugin = plugin;
        this.exporterLocation = exporterLocation;
        this.exporterId = exporterId;
        this.networkId = networkId;

        this.inventory = Bukkit.createInventory(null, 45, miniMessage.deserialize("<dark_purple>Exporter Configuration"));

        loadCurrentFilters();
        setupGUI();
    }

    private void loadCurrentFilters() {
        currentFilterItems.clear();
        currentFilterItems.addAll(plugin.getExporterManager().getExporterFilterItems(exporterId));
    }

    public void setupGUI() {
        inventory.clear();
        slotToFilterItem.clear();

        for (int i = 0; i < 18; i++) {
            if (i < currentFilterItems.size()) {
                ItemStack filterItem = currentFilterItems.get(i);
                ItemStack displayItem = filterItem.clone();
                displayItem.setAmount(1);

                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<Component> lore = (meta.hasLore() && meta.lore() != null) ? new ArrayList<>(Objects.requireNonNull(meta.lore())) : new ArrayList<>();
                    lore.add(Component.empty());
                    lore.add(miniMessage.deserialize("<gray>Filter Item"));
                    lore.add(miniMessage.deserialize("<yellow>Click to remove from filter"));
                    meta.lore(lore);
                    displayItem.setItemMeta(meta);
                }

                inventory.setItem(i, displayItem);
                slotToFilterItem.put(i, filterItem);
            } else {
                ItemStack placeholder = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
                ItemMeta meta = placeholder.getItemMeta();
                meta.displayName(miniMessage.deserialize("<gray>Empty Filter Slot"));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(miniMessage.deserialize("<yellow>Drag items here to add them to the filter"));
                lore.add(miniMessage.deserialize("<yellow>Or shift-click items from your inventory"));
                meta.lore(lore);
                placeholder.setItemMeta(meta);
                inventory.setItem(i, placeholder);
            }
        }

        ItemStack divider = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta dividerMeta = divider.getItemMeta();
        dividerMeta.displayName(Component.text(" "));
        divider.setItemMeta(dividerMeta);
        for (int i = 18; i < 27; i++) {
            inventory.setItem(i, divider);
        }

        setupControlArea();
    }

    private void setupControlArea() {
        ItemStack filler = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);
        for (int i = 27; i < 45; i++) {
            inventory.setItem(i, filler);
        }

        ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
        boolean isEnabled = data != null && data.enabled;

        ItemStack status = new ItemStack(isEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = status.getItemMeta();
        statusMeta.displayName(miniMessage.deserialize(isEnabled ? "<green>Status: Enabled" : "<gray>Status: Disabled"));

        List<Component> statusLore = new ArrayList<>();
        if (!isEnabled && currentFilterItems.isEmpty()) {
            statusLore.add(miniMessage.deserialize("<red>Add items to filter to enable"));
        } else {
            statusLore.add(miniMessage.deserialize("<gray>Exporter is " + (isEnabled ? "actively exporting" : "inactive")));
        }

        Container target = getTargetContainer();
        statusLore.add(Component.empty());
        if (target != null) {
            statusLore.add(miniMessage.deserialize("<aqua>Connected to: " + target.getBlock().getType().name()));
        } else {
            statusLore.add(miniMessage.deserialize("<red>No valid container connected"));
        }

        statusLore.add(Component.empty());
        statusLore.add(miniMessage.deserialize("<yellow>Click to toggle"));
        statusMeta.lore(statusLore);
        status.setItemMeta(statusMeta);
        inventory.setItem(29, status);

        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.displayName(miniMessage.deserialize("<red>Clear All Filters"));
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.empty());
        clearLore.add(miniMessage.deserialize("<yellow>Click to remove all filter items"));
        clearMeta.lore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(31, clearButton);

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(miniMessage.deserialize("<aqua>Exporter Information"));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(miniMessage.deserialize("<gray>Network: " + networkId.substring(0, Math.min(16, networkId.length()))));
        infoLore.add(miniMessage.deserialize("<gray>Filters: " + currentFilterItems.size() + "/18"));
        infoLore.add(Component.empty());
        infoLore.add(miniMessage.deserialize("<yellow>Shift+Click items from inventory to add filters"));
        infoLore.add(miniMessage.deserialize("<yellow>Drag & drop items into filter area"));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(35, info);
    }

    private Container getTargetContainer() {
        try {
            Block exporterBlock = exporterLocation.getBlock();
            Block attachedBlock = null;

            if (exporterBlock.getType() == Material.PLAYER_HEAD) {
                attachedBlock = exporterBlock.getRelative(BlockFace.DOWN);
            } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
                Directional directional = (Directional) exporterBlock.getBlockData();
                BlockFace facing = directional.getFacing();
                // The block the wall head is attached to is in the opposite direction
                attachedBlock = exporterBlock.getRelative(facing.getOppositeFace());
            }
            if (attachedBlock != null && attachedBlock.getState() instanceof Container container) {
                return container;
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking attached block for exporter: " + e.getMessage());
        }
        return null;
    }

    public void open(Player player) {
        player.openInventory(inventory);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();

        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) &&
                slot >= inventory.getSize()) {

            ItemStack itemToAdd = event.getCurrentItem();

            if (itemToAdd != null && !itemToAdd.getType().isAir()) {
                event.setCancelled(true);
                handleAddItemToFilter(player, itemToAdd);
            }
            return;
        }

        if (slot < inventory.getSize()) {
            event.setCancelled(true);

            if (slot < 18) {
                handleFilterAreaClick(player, slot, event);
            } else if (slot >= 27 && slot < 45) {
                handleControlAreaClick(player, slot);
            }
        }
    }

    private void handleFilterAreaClick(Player player, int slot, InventoryClickEvent event) {
        ItemStack cursorItem = event.getCursor();

        if (cursorItem.getType() != Material.AIR) {
            // Check if item is blacklisted
            if (plugin.getItemManager().isItemBlacklisted(cursorItem)) {
                player.sendMessage(Component.text("You cannot add occupied containers or disks to the network!", NamedTextColor.RED));
                return;
            }
            
            String newItemHash = plugin.getItemManager().generateItemHash(cursorItem);
            for (ItemStack existingItem : currentFilterItems) {
                String existingHash = plugin.getItemManager().generateItemHash(existingItem);
                if (newItemHash.equals(existingHash)) {
                    player.sendMessage(Component.text("This item is already in the filter!", NamedTextColor.RED));
                    return;
                }
            }

            if (currentFilterItems.size() >= 18) {
                player.sendMessage(Component.text("Filter is full! Remove items first.", NamedTextColor.RED));
            } else {
                ItemStack filterTemplate = cursorItem.clone();
                filterTemplate.setAmount(1);

                currentFilterItems.add(filterTemplate);
                saveFilters();
                setupGUI();
                player.sendMessage(Component.text("Added " + cursorItem.getType() + " to filter", NamedTextColor.GREEN));
            }
            return;
        }

        ItemStack filterItem = slotToFilterItem.get(slot);
        if (filterItem != null) {
            currentFilterItems.remove(filterItem);
            saveFilters();
            setupGUI();
            player.sendMessage(Component.text("Removed item from filter", NamedTextColor.YELLOW));
        }

    }

    private void handleControlAreaClick(Player player, int slot) {
        switch (slot) {
            case 29:
                handleToggleClick(player);
                break;
            case 31:
                handleClearFilters(player);
                break;
            default:
                break;
        }
    }

    private void handleAddItemToFilter(Player player, ItemStack itemToAdd) {
        // Check if item is blacklisted
        if (plugin.getItemManager().isItemBlacklisted(itemToAdd)) {
            player.sendMessage(Component.text("You cannot add occupied containers or disks to the network!", NamedTextColor.RED));
            return;
        }
        
        String newItemHash = plugin.getItemManager().generateItemHash(itemToAdd);
        for (ItemStack existingItem : currentFilterItems) {
            String existingHash = plugin.getItemManager().generateItemHash(existingItem);
            if (newItemHash.equals(existingHash)) {
                player.sendMessage(Component.text("This item is already in the filter!", NamedTextColor.RED));
                return;
            }
        }

        if (currentFilterItems.size() >= 18) {
            player.sendMessage(Component.text("Filter is full! Remove items first.", NamedTextColor.RED));
            return;
        }

        ItemStack filterTemplate = itemToAdd.clone();
        filterTemplate.setAmount(1);

        currentFilterItems.add(filterTemplate);
        saveFilters();
        setupGUI();
        player.sendMessage(Component.text("Added " + itemToAdd.getType() + " to filter", NamedTextColor.GREEN));
    }

    private void handleToggleClick(Player player) {
        try {
            ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
            if (data == null) return;

            if (currentFilterItems.isEmpty() && !data.enabled) {
                player.sendMessage(Component.text("Cannot enable exporter without filter items!", NamedTextColor.RED));
                return;
            }

            boolean newState = !data.enabled;
            plugin.getExporterManager().toggleExporter(exporterId, newState);
            setupGUI();

            player.sendMessage(Component.text("Exporter " + (newState ? "enabled" : "disabled"),
                    newState ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        } catch (Exception e) {
            player.sendMessage(Component.text("Error toggling exporter: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleClearFilters(Player player) {
        if (currentFilterItems.isEmpty()) {
            player.sendMessage(Component.text("No filters to clear", NamedTextColor.YELLOW));
            return;
        }

        currentFilterItems.clear();
        saveFilters();
        setupGUI();
        player.sendMessage(Component.text("Cleared all filters", NamedTextColor.YELLOW));
    }

    private void saveFilters() {
        try {
            plugin.getExporterManager().updateExporterFilter(exporterId, currentFilterItems);
        } catch (Exception e) {
            plugin.getLogger().severe("Error saving filters: " + e.getMessage());
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean dragIntoGUI = false;
        for (int slot : event.getRawSlots()) {
            if (slot < inventory.getSize()) {
                dragIntoGUI = true;
                break;
            }
        }

        if (dragIntoGUI) {
            event.setCancelled(true);

            ItemStack draggedItem = event.getOldCursor();
            if (draggedItem.getType() != Material.AIR) {
                boolean dragIntoFilterArea = false;
                for (int slot : event.getRawSlots()) {
                    if (slot < 18) {
                        dragIntoFilterArea = true;
                        break;
                    }
                }

                if (dragIntoFilterArea) {
                    // Check if item is blacklisted
                    if (plugin.getItemManager().isItemBlacklisted(draggedItem)) {
                        player.sendMessage(Component.text("You cannot add occupied containers or disks to the network!", NamedTextColor.RED));
                        return;
                    }
                    
                    String newItemHash = plugin.getItemManager().generateItemHash(draggedItem);
                    for (ItemStack existingItem : currentFilterItems) {
                        String existingHash = plugin.getItemManager().generateItemHash(existingItem);
                        if (newItemHash.equals(existingHash)) {
                            player.sendMessage(Component.text("This item is already in the filter!", NamedTextColor.RED));
                            return;
                        }
                    }

                    if (currentFilterItems.size() >= 18) {
                        player.sendMessage(Component.text("Filter is full! Remove items first.", NamedTextColor.RED));
                    } else {
                        ItemStack filterTemplate = draggedItem.clone();
                        filterTemplate.setAmount(1);

                        currentFilterItems.add(filterTemplate);
                        saveFilters();
                        setupGUI();
                        player.sendMessage(Component.text("Added " + draggedItem.getType() + " to filter", NamedTextColor.GREEN));

                        event.getView().setCursor(draggedItem);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        InventoryClickEvent.getHandlerList().unregister(this);
        InventoryDragEvent.getHandlerList().unregister(this);
        InventoryCloseEvent.getHandlerList().unregister(this);

        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().closeGUI(player);
        }
    }

    public String getExporterId() {
        return exporterId;
    }

    public String getNetworkId() {
        return networkId;
    }
}