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
import org.bukkit.enchantments.Enchantment;
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
import org.jamesphbennett.massstorageserver.managers.ImporterManager;

import java.util.*;

public class ImporterGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location importerLocation;
    private final String importerId;
    private final String networkId;
    private final Inventory inventory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<Integer, ItemStack> slotToFilterItem = new HashMap<>();
    private final List<ItemStack> currentFilterItems = new ArrayList<>();
    
    // Brewing stand specific fields
    private boolean isBrewingStandTarget = false;
    private boolean blazePowderEnabled = false;
    private ItemStack ingredientFilter = null;
    private ItemStack[] bottleFilters = new ItemStack[3]; // For slots 0, 1, 2

    public ImporterGUI(MassStorageServer plugin, Location importerLocation, String importerId, String networkId) {
        this.plugin = plugin;
        this.importerLocation = importerLocation;
        this.importerId = importerId;
        this.networkId = networkId;

        this.inventory = Bukkit.createInventory(null, 45, miniMessage.deserialize("<blue>Importer Configuration"));

        detectTargetType();
        loadCurrentFilters();
        
        if (isBrewingStandTarget) {
            setupBrewingStandGUI();
        } else {
            setupGUI();
        }
    }

    /**
     * Detect what type of block this importer is targeting
     */
    private void detectTargetType() {
        try {
            Block importerBlock = importerLocation.getBlock();
            Block attachedBlock = null;

            // Use same logic as ImporterManager to find target block
            if (importerBlock.getType() == Material.PLAYER_HEAD) {
                // Floor mounted head - check block below
                attachedBlock = importerBlock.getRelative(BlockFace.DOWN);
            } else if (importerBlock.getType() == Material.PLAYER_WALL_HEAD) {
                // Wall mounted head - check block it's attached to
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) importerBlock.getBlockData();
                BlockFace facing = directional.getFacing();
                attachedBlock = importerBlock.getRelative(facing.getOppositeFace());
            }

            if (attachedBlock != null && attachedBlock.getType() == Material.BREWING_STAND) {
                isBrewingStandTarget = true;
                plugin.getLogger().info("Detected brewing stand target for importer at " + importerLocation);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error detecting importer target type: " + e.getMessage());
        }
    }

    private void loadCurrentFilters() {
        currentFilterItems.clear();
        currentFilterItems.addAll(plugin.getImporterManager().getImporterFilterItems(importerId));
        
        if (isBrewingStandTarget) {
            loadBrewingStandSettings();
        }
    }

    public void setupGUI() {
        if (isBrewingStandTarget) {
            setupBrewingStandGUI();
            return;
        }
        
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
                lore.add(Component.empty());
                lore.add(miniMessage.deserialize("<gray>Leave empty to import everything"));
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

        ImporterManager.ImporterData data = plugin.getImporterManager().getImporterAtLocation(importerLocation);
        boolean isEnabled = data != null && data.enabled;

        ItemStack status = new ItemStack(isEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = status.getItemMeta();
        statusMeta.displayName(miniMessage.deserialize(isEnabled ? "<green>Status: Enabled" : "<gray>Status: Disabled"));

        List<Component> statusLore = new ArrayList<>();
        statusLore.add(miniMessage.deserialize("<gray>Importer is " + (isEnabled ? "actively importing" : "inactive")));

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
        clearLore.add(miniMessage.deserialize("<gray>Clears filters to import everything"));
        clearMeta.lore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(31, clearButton);

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(miniMessage.deserialize("<aqua>Importer Information"));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(miniMessage.deserialize("<gray>Network: " + networkId.substring(0, Math.min(16, networkId.length()))));
        infoLore.add(miniMessage.deserialize("<gray>Filters: " + currentFilterItems.size() + "/18"));
        infoLore.add(Component.empty());
        infoLore.add(miniMessage.deserialize("<yellow>Shift+Click items from inventory to add filters"));
        infoLore.add(miniMessage.deserialize("<yellow>Drag & drop items into filter area"));
        infoLore.add(miniMessage.deserialize("<gray>No filters = import everything"));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(35, info);
    }

    /**
     * Setup specialized GUI for brewing stand targeting (imports from bottom potion slots only)
     */
    public void setupBrewingStandGUI() {
        inventory.clear();
        slotToFilterItem.clear();

        // Fill with background filler first
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.displayName(Component.text(" "));
        filler.setItemMeta(fillerMeta);
        
        for (int i = 0; i < 45; i++) {
            inventory.setItem(i, filler);
        }

        // B = Blue slots (slots 21, 22, 23) - bottle filters (only targeting these slots)
        setupBottleSlots();
        
        // O = On/off toggle (slot 38)
        setupStatusToggle();
        
        // C = Clear filter (slot 40)
        setupClearButton();
        
        // I = Info (slot 42) - book
        setupInfoPanel();
    }
    
    private void setupBottleSlots() {
        int[] bottleSlots = {21, 22, 23}; // B B B
        String[] bottleNames = {"Left", "Middle", "Right"};
        
        for (int i = 0; i < 3; i++) {
            ItemStack bottleSlot;
            if (bottleFilters[i] != null) {
                bottleSlot = bottleFilters[i].clone();
                bottleSlot.setAmount(1);
                ItemMeta meta = bottleSlot.getItemMeta();
                List<Component> lore = (meta.hasLore() && meta.lore() != null) ? 
                    new ArrayList<>(Objects.requireNonNull(meta.lore())) : new ArrayList<>();
                lore.add(Component.empty());
                lore.add(Component.text("Import Filter (" + bottleNames[i] + ")", NamedTextColor.BLUE));
                lore.add(Component.text("Click to remove", NamedTextColor.YELLOW));
                meta.lore(lore);
                bottleSlot.setItemMeta(meta);
            } else {
                bottleSlot = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
                ItemMeta meta = bottleSlot.getItemMeta();
                meta.displayName(Component.text("Import Slot (" + bottleNames[i] + ")", NamedTextColor.BLUE));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(Component.text("Imports from brewing stand potion slot", NamedTextColor.GRAY));
                lore.add(Component.text("Drag item here to set filter", NamedTextColor.YELLOW));
                lore.add(Component.text("No filter = import anything", NamedTextColor.GRAY));
                meta.lore(lore);
                bottleSlot.setItemMeta(meta);
            }
            inventory.setItem(bottleSlots[i], bottleSlot);
        }
    }
    
    private void setupStatusToggle() {
        ImporterManager.ImporterData data = plugin.getImporterManager().getImporterAtLocation(importerLocation);
        boolean isEnabled = data != null && data.enabled;

        ItemStack status = new ItemStack(isEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = status.getItemMeta();
        statusMeta.displayName(Component.text(isEnabled ? "Status: Enabled" : "Status: Disabled", 
            isEnabled ? NamedTextColor.GREEN : NamedTextColor.GRAY));

        List<Component> statusLore = new ArrayList<>();
        statusLore.add(Component.text("Importer is " + (isEnabled ? "actively importing" : "inactive"), NamedTextColor.GRAY));
        statusLore.add(Component.empty());
        statusLore.add(Component.text("Connected to: Brewing Stand", NamedTextColor.AQUA));
        statusLore.add(Component.text("Imports from bottom potion slots only", NamedTextColor.GRAY));
        statusLore.add(Component.empty());
        statusLore.add(Component.text("Click to toggle", NamedTextColor.YELLOW));
        statusMeta.lore(statusLore);
        status.setItemMeta(statusMeta);
        inventory.setItem(38, status);
    }
    
    private void setupClearButton() {
        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.displayName(Component.text("Clear All Filters", NamedTextColor.RED));
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.empty());
        clearLore.add(Component.text("Click to remove all filter items", NamedTextColor.YELLOW));
        clearLore.add(Component.text("Will import all items from potion slots", NamedTextColor.GRAY));
        clearMeta.lore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(40, clearButton);
    }
    
    private void setupInfoPanel() {
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text("Brewing Stand Importer", NamedTextColor.AQUA));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(Component.text("Network: " + networkId.substring(0, Math.min(16, networkId.length())), NamedTextColor.GRAY));
        infoLore.add(Component.empty());
        infoLore.add(Component.text("Blue: Import filters for potion slots", NamedTextColor.BLUE));
        infoLore.add(Component.text("Only imports from bottom 3 slots", NamedTextColor.GRAY));
        infoLore.add(Component.text("Ingredient/fuel slots are ignored", NamedTextColor.GRAY));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(42, info);
    }

    private Container getTargetContainer() {
        try {
            Block importerBlock = importerLocation.getBlock();
            Block attachedBlock = null;

            if (importerBlock.getType() == Material.PLAYER_HEAD) {
                attachedBlock = importerBlock.getRelative(BlockFace.DOWN);
            } else if (importerBlock.getType() == Material.PLAYER_WALL_HEAD) {
                Directional directional = (Directional) importerBlock.getBlockData();
                BlockFace facing = directional.getFacing();
                // The block the wall head is attached to is in the opposite direction
                attachedBlock = importerBlock.getRelative(facing.getOppositeFace());
            }
            if (attachedBlock != null && attachedBlock.getState() instanceof Container container) {
                return container;
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking attached block for importer: " + e.getMessage());
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

        if (isBrewingStandTarget) {
            handleBrewingStandClick(player, slot, event);
        } else {
            handleRegularImporterClick(player, slot, event);
        }
    }

    private void handleRegularImporterClick(Player player, int slot, InventoryClickEvent event) {
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

    private void handleBrewingStandClick(Player player, int slot, InventoryClickEvent event) {
        // Handle shift-click from player inventory
        if ((event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) &&
                slot >= inventory.getSize()) {
            ItemStack itemToAdd = event.getCurrentItem();
            if (itemToAdd != null && !itemToAdd.getType().isAir()) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Drag items to filter slots (blue for potions)!", NamedTextColor.YELLOW));
            }
            return;
        }

        if (slot < inventory.getSize()) {
            event.setCancelled(true);
            
            ItemStack cursorItem = event.getCursor();

            // Handle bottle slots (slots 21, 22, 23)
            int[] bottleSlots = {21, 22, 23};
            for (int i = 0; i < 3; i++) {
                if (slot == bottleSlots[i]) {
                    if (!cursorItem.getType().isAir()) {
                        // Set bottle filter
                        if (!plugin.getItemManager().isItemBlacklisted(cursorItem)) {
                            bottleFilters[i] = cursorItem.clone();
                            bottleFilters[i].setAmount(1);
                            saveBrewingStandSettings();
                            setupBrewingStandGUI();
                            player.sendMessage(Component.text("Set import filter " + (i + 1) + " to " + cursorItem.getType(), NamedTextColor.GREEN));
                        } else {
                            player.sendMessage(Component.text("Cannot filter blacklisted items!", NamedTextColor.RED));
                        }
                    } else if (bottleFilters[i] != null) {
                        // Remove bottle filter
                        bottleFilters[i] = null;
                        saveBrewingStandSettings();
                        setupBrewingStandGUI();
                        player.sendMessage(Component.text("Removed import filter " + (i + 1), NamedTextColor.YELLOW));
                    }
                    return;
                }
            }

            // Handle control buttons
            handleBrewingStandControlClick(player, slot);
        }
    }

    private void handleBrewingStandControlClick(Player player, int slot) {
        if (slot == 38) {
            // Status toggle (O slot)
            ImporterManager.ImporterData data = plugin.getImporterManager().getImporterAtLocation(importerLocation);
            if (data != null) {
                try {
                    boolean newState = !data.enabled;
                    plugin.getImporterManager().toggleImporter(importerId, newState);
                    setupBrewingStandGUI();
                    player.sendMessage(Component.text("Importer " + (newState ? "enabled" : "disabled"),
                            newState ? NamedTextColor.GREEN : NamedTextColor.RED));
                } catch (Exception e) {
                    player.sendMessage(Component.text("Error toggling importer: " + e.getMessage(), NamedTextColor.RED));
                    plugin.getLogger().severe("Error toggling importer: " + e.getMessage());
                }
            }
        } else if (slot == 40) {
            // Clear all filters (C slot)
            bottleFilters = new ItemStack[3];
            saveBrewingStandSettings();
            setupBrewingStandGUI();
            player.sendMessage(Component.text("Cleared all brewing stand filters", NamedTextColor.YELLOW));
        }
        // Info panel (slot 42) doesn't need click handling
    }

    /**
     * Handle drag operations for brewing stand GUI
     */
    private void handleBrewingStandDrag(Player player, ItemStack draggedItem, Set<Integer> slots) {
        // Check if item is blacklisted
        if (plugin.getItemManager().isItemBlacklisted(draggedItem)) {
            player.sendMessage(Component.text("You cannot add occupied containers or disks to the network!", NamedTextColor.RED));
            return;
        }
        
        // Determine target slot based on where the drag occurred
        for (int slot : slots) {
            if (slot == 21 || slot == 22 || slot == 23) {
                // Bottle slots
                int bottleIndex = slot - 21; // Convert to 0, 1, 2
                bottleFilters[bottleIndex] = draggedItem.clone();
                bottleFilters[bottleIndex].setAmount(1);
                saveBrewingStandSettings();
                setupBrewingStandGUI();
                player.sendMessage(Component.text("Set import filter " + (bottleIndex + 1) + " to " + draggedItem.getType(), NamedTextColor.GREEN));
                return;
            }
        }
        
        // If no valid slot found, show message
        player.sendMessage(Component.text("Drag items to blue (potion) slots!", NamedTextColor.YELLOW));
    }

    /**
     * Save brewing stand specific settings to database (only for potion slots)
     */
    private void saveBrewingStandSettings() {
        currentFilterItems.clear();
        
        // Store bottle filters only (no fuel or ingredient for importers)
        for (int i = 0; i < 3; i++) {
            if (bottleFilters[i] != null) {
                ItemStack bottleMarker = bottleFilters[i].clone();
                ItemMeta meta = bottleMarker.getItemMeta();
                meta.displayName(Component.text("BREWING_BOTTLE_" + i));
                bottleMarker.setItemMeta(meta);
                currentFilterItems.add(bottleMarker);
            }
        }
        
        saveFilters();
    }

    /**
     * Load brewing stand specific settings from database (only for potion slots)
     */
    private void loadBrewingStandSettings() {
        if (!isBrewingStandTarget) return;
        
        bottleFilters = new ItemStack[3];
        
        for (ItemStack item : currentFilterItems) {
            if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                Component displayName = item.getItemMeta().displayName();
                if (displayName == null) continue;
                
                String nameText = ((net.kyori.adventure.text.TextComponent) displayName).content();
                
                if (nameText.startsWith("BREWING_BOTTLE_")) {
                    try {
                        int bottleIndex = Integer.parseInt(nameText.substring("BREWING_BOTTLE_".length()));
                        if (bottleIndex >= 0 && bottleIndex < 3) {
                            bottleFilters[bottleIndex] = item.clone();
                            // Remove marker by getting meta, modifying it, and setting it back
                            ItemMeta bottleMeta = bottleFilters[bottleIndex].getItemMeta();
                            bottleMeta.displayName(null);
                            bottleFilters[bottleIndex].setItemMeta(bottleMeta);
                        }
                    } catch (NumberFormatException e) {
                        // Ignore invalid bottle indices
                    }
                }
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
                player.sendMessage(Component.text("Added " + cursorItem.getType() + " to import filter", NamedTextColor.GREEN));
            }
            return;
        }

        ItemStack filterItem = slotToFilterItem.get(slot);
        if (filterItem != null) {
            currentFilterItems.remove(filterItem);
            saveFilters();
            setupGUI();
            player.sendMessage(Component.text("Removed item from import filter", NamedTextColor.YELLOW));
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
        player.sendMessage(Component.text("Added " + itemToAdd.getType() + " to import filter", NamedTextColor.GREEN));
    }

    private void handleToggleClick(Player player) {
        try {
            ImporterManager.ImporterData data = plugin.getImporterManager().getImporterAtLocation(importerLocation);
            if (data == null) return;

            boolean newState = !data.enabled;
            plugin.getImporterManager().toggleImporter(importerId, newState);
            setupGUI();

            player.sendMessage(Component.text("Importer " + (newState ? "enabled" : "disabled"),
                    newState ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        } catch (Exception e) {
            player.sendMessage(Component.text("Error toggling importer: " + e.getMessage(), NamedTextColor.RED));
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
        player.sendMessage(Component.text("Cleared all filters - will now import everything", NamedTextColor.YELLOW));
    }

    private void saveFilters() {
        try {
            plugin.getImporterManager().updateImporterFilter(importerId, currentFilterItems);
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
                if (isBrewingStandTarget) {
                    handleBrewingStandDrag(player, draggedItem, event.getRawSlots());
                } else {
                    // Regular importer drag handling
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
                        player.sendMessage(Component.text("Added " + draggedItem.getType() + " to import filter", NamedTextColor.GREEN));

                        event.getView().setCursor(draggedItem);
                    }
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

    public String getImporterId() {
        return importerId;
    }

    public String getNetworkId() {
        return networkId;
    }
}