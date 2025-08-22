package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
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

public class FurnaceExporterGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location exporterLocation;
    private final String exporterId;
    private final String networkId;
    private final Inventory inventory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<Integer, ItemStack> slotToFilterItem = new HashMap<>();
    private final List<ItemStack> currentFuelFilterItems = new ArrayList<>();
    private final List<ItemStack> currentMaterialFilterItems = new ArrayList<>();

    private final int[] FUEL_FILTER_SLOTS = {0, 1, 2, 9, 10, 11, 18, 19, 20, 27, 28, 29, 36, 37, 38, 45, 46, 47};
    private final int[] MATERIAL_FILTER_SLOTS = {6, 7, 8, 15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44, 51, 52, 53};
    private final int[] FILLER_SLOTS = {3, 4, 5, 12, 13, 14, 21, 22, 23, 30, 32, 39, 41, 48, 50};
    private final int[] CONTROL_SLOTS = {31, 40, 49};

    public FurnaceExporterGUI(MassStorageServer plugin, Location exporterLocation, String exporterId, String networkId) {
        this.plugin = plugin;
        this.exporterLocation = exporterLocation;
        this.exporterId = exporterId;
        this.networkId = networkId;

        this.inventory = Bukkit.createInventory(null, 54, plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.title"));

        loadCurrentFilters();
        setupGUI();
    }

    private void loadCurrentFilters() {
        currentFuelFilterItems.clear();
        currentMaterialFilterItems.clear();
        
        Map<String, List<ItemStack>> filters = plugin.getExporterManager().getFurnaceExporterFilterItems(exporterId);
        currentFuelFilterItems.addAll(filters.get("fuel"));
        currentMaterialFilterItems.addAll(filters.get("material"));
    }

    public void setupGUI() {
        inventory.clear();
        slotToFilterItem.clear();

        setupFilterSlots(FUEL_FILTER_SLOTS, currentFuelFilterItems, Material.RED_STAINED_GLASS_PANE, 
                        "gui.exporter.furnace.fuel-slot", "gui.exporter.furnace.fuel-instruction", 
                        "gui.exporter.furnace.furnace-fuel-description");

        setupFilterSlots(MATERIAL_FILTER_SLOTS, currentMaterialFilterItems, Material.BLUE_STAINED_GLASS_PANE,
                        "gui.exporter.furnace.input-slot", "gui.exporter.furnace.input-instruction", 
                        "gui.exporter.furnace.input-description");

        setupFillerSlots();
        setupControlArea();
    }

    private void setupFilterSlots(int[] slots, List<ItemStack> filterItems, Material glassType, String title, String instruction1, String instruction2) {
        for (int i = 0; i < slots.length; i++) {
            int slot = slots[i];
            
            if (i < filterItems.size()) {
                ItemStack filterItem = filterItems.get(i);
                ItemStack displayItem = filterItem.clone();
                displayItem.setAmount(1);

                ItemMeta meta = displayItem.getItemMeta();
                if (meta != null) {
                    List<Component> lore = (meta.hasLore() && meta.lore() != null) ? new ArrayList<>(Objects.requireNonNull(meta.lore())) : new ArrayList<>();
                    lore.add(Component.empty());
                    lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.item-description"));
                    lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.remove-instruction"));
                    meta.lore(lore);
                    displayItem.setItemMeta(meta);
                }

                inventory.setItem(slot, displayItem);
                slotToFilterItem.put(slot, filterItem);
            } else {
                ItemStack placeholder = new ItemStack(glassType);
                ItemMeta meta = placeholder.getItemMeta();
                meta.displayName(plugin.getMessageManager().getMessageComponent(null, title));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(plugin.getMessageManager().getMessageComponent(null, instruction1));
                lore.add(plugin.getMessageManager().getMessageComponent(null, instruction2));
                lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.shift-instruction"));
                meta.lore(lore);
                placeholder.setItemMeta(meta);
                inventory.setItem(slot, placeholder);
            }
        }
    }

    private void setupFillerSlots() {
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);
        
        for (int slot : FILLER_SLOTS) {
            inventory.setItem(slot, filler);
        }
    }

    private void setupControlArea() {
        ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
        boolean isEnabled = data != null && data.enabled;

        ItemStack status = new ItemStack(isEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = status.getItemMeta();
        statusMeta.displayName(plugin.getMessageManager().getMessageComponent(null, isEnabled ? "gui.exporter.status.enabled" : "gui.exporter.status.disabled"));

        List<Component> statusLore = new ArrayList<>();
        if (!isEnabled && currentFuelFilterItems.isEmpty() && currentMaterialFilterItems.isEmpty()) {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.no-filter"));
        } else {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, isEnabled ? "gui.exporter.status.description-enabled" : "gui.exporter.status.description-disabled"));
        }

        Container target = getTargetContainer();
        statusLore.add(Component.empty());
        if (target != null) {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.target.connected", "container", target.getBlock().getType().name()));
        } else {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.no-furnace"));
        }

        statusLore.add(Component.empty());
        statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.status.toggle"));
        statusMeta.lore(statusLore);
        status.setItemMeta(statusMeta);
        inventory.setItem(31, status);

        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.controls.clear"));
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.empty());
        clearLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.controls.clear-description"));
        clearLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.clear-both"));
        clearMeta.lore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(40, clearButton);

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.title"));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.info.network", "network", networkId.substring(0, Math.min(16, networkId.length()))));
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.fuel-count", "count", currentFuelFilterItems.size(), "max", FUEL_FILTER_SLOTS.length));
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.material-count", "count", currentMaterialFilterItems.size(), "max", MATERIAL_FILTER_SLOTS.length));
        infoLore.add(Component.empty());
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.red-slots"));
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.blue-slots"));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);
    }

    private Container getTargetContainer() {
        try {
            Block exporterBlock = exporterLocation.getBlock();
            Block attachedBlock = null;

            if (exporterBlock.getType() == Material.PLAYER_HEAD) {
                attachedBlock = exporterBlock.getRelative(org.bukkit.block.BlockFace.DOWN);
            } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) exporterBlock.getBlockData();
                org.bukkit.block.BlockFace facing = directional.getFacing();
                // The block the wall head is attached to is in the opposite direction
                attachedBlock = exporterBlock.getRelative(facing.getOppositeFace());
            }
            if (attachedBlock != null && attachedBlock.getState() instanceof Container container) {
                Material type = attachedBlock.getType();
                if (type == Material.FURNACE || type == Material.BLAST_FURNACE || type == Material.SMOKER) {
                    return container;
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking attached block for furnace exporter: " + e.getMessage());
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
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.shift-instruction"));
            }
            return;
        }

        if (slot < inventory.getSize()) {
            event.setCancelled(true);

            if (isFilterSlot(slot)) {
                handleFilterAreaClick(player, slot, event);
            } else if (isControlSlot(slot)) {
                handleControlAreaClick(player, slot);
            }
        }
    }

    private boolean isFilterSlot(int slot) {
        return Arrays.stream(FUEL_FILTER_SLOTS).anyMatch(s -> s == slot) ||
               Arrays.stream(MATERIAL_FILTER_SLOTS).anyMatch(s -> s == slot);
    }

    private boolean isControlSlot(int slot) {
        return Arrays.stream(CONTROL_SLOTS).anyMatch(s -> s == slot);
    }

    private void handleFilterAreaClick(Player player, int slot, InventoryClickEvent event) {
        ItemStack cursorItem = event.getCursor();

        if (cursorItem.getType() != Material.AIR) {
            handleAddItemToFilter(player, cursorItem, slot);
            return;
        }

        ItemStack filterItem = slotToFilterItem.get(slot);
        if (filterItem != null) {
            removeFilterItem(filterItem, slot);
            setupGUI();
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.removed", "type", "export"));
        }
    }

    private void handleAddItemToFilter(Player player, ItemStack itemToAdd, int slot) {
        // Check if item is blacklisted
        if (plugin.getItemManager().isItemBlacklisted(itemToAdd)) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.item-blacklisted"));
            return;
        }
        
        boolean isFuelSlot = Arrays.stream(FUEL_FILTER_SLOTS).anyMatch(s -> s == slot);
        List<ItemStack> targetList = isFuelSlot ? currentFuelFilterItems : currentMaterialFilterItems;
        int maxSlots = isFuelSlot ? FUEL_FILTER_SLOTS.length : MATERIAL_FILTER_SLOTS.length;
        String filterType = isFuelSlot ? "fuel" : "material";

        String newItemHash = plugin.getItemManager().generateItemHash(itemToAdd);
        for (ItemStack existingItem : currentFuelFilterItems) {
            String existingHash = plugin.getItemManager().generateItemHash(existingItem);
            if (newItemHash.equals(existingHash)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.already-in-fuel"));
                return;
            }
        }
        for (ItemStack existingItem : currentMaterialFilterItems) {
            String existingHash = plugin.getItemManager().generateItemHash(existingItem);
            if (newItemHash.equals(existingHash)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.already-in-material"));
                return;
            }
        }

        if (targetList.size() >= maxSlots) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.full"));
            return;
        }

        ItemStack filterTemplate = itemToAdd.clone();
        filterTemplate.setAmount(1);
        targetList.add(filterTemplate);
        saveFilters();
        setupGUI();
        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.added-to-filter", "item", itemToAdd.getType(), "type", filterType));
    }

    private void removeFilterItem(ItemStack filterItem, int slot) {
        boolean isFuelSlot = Arrays.stream(FUEL_FILTER_SLOTS).anyMatch(s -> s == slot);
        List<ItemStack> targetList = isFuelSlot ? currentFuelFilterItems : currentMaterialFilterItems;
        targetList.remove(filterItem);
        saveFilters();
    }

    private void handleControlAreaClick(Player player, int slot) {
        switch (slot) {
            case 31:
                handleToggleClick(player);
                break;
            case 40:
                handleClearFilters(player);
                break;
            case 49:
                break;
        }
    }

    private void handleToggleClick(Player player) {
        try {
            ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
            if (data == null) return;

            if (currentFuelFilterItems.isEmpty() && currentMaterialFilterItems.isEmpty() && !data.enabled) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.filter.no-filter"));
                return;
            }

            boolean newState = !data.enabled;
            plugin.getExporterManager().toggleExporter(exporterId, newState);
            setupGUI();

            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, newState ? "gui.device.enabled" : "gui.device.disabled", "device", "Furnace exporter"));
        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.device.toggle-error", "device", "exporter", "error", e.getMessage()));
        }
    }

    private void handleClearFilters(Player player) {
        if (currentFuelFilterItems.isEmpty() && currentMaterialFilterItems.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.controls.no-filters"));
            return;
        }

        currentFuelFilterItems.clear();
        currentMaterialFilterItems.clear();
        saveFilters();
        setupGUI();
        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.controls.cleared"));
    }

    private void saveFilters() {
        try {
            plugin.getExporterManager().updateFurnaceExporterFilters(exporterId, currentFuelFilterItems, currentMaterialFilterItems);
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
                for (int slot : event.getRawSlots()) {
                    if (slot < inventory.getSize() && isFilterSlot(slot)) {
                        handleAddItemToFilter(player, draggedItem, slot);
                        return;
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

    public String getNetworkId() {
        return networkId;
    }
}