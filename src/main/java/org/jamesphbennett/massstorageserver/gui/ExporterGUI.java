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
    
    // Brewing stand specific fields
    private boolean isBrewingStandTarget = false;
    private boolean blazePowderEnabled = false;
    private ItemStack ingredientFilter = null;
    private ItemStack[] bottleFilters = new ItemStack[3]; // For slots 0, 1, 2

    public ExporterGUI(MassStorageServer plugin, Location exporterLocation, String exporterId, String networkId) {
        this.plugin = plugin;
        this.exporterLocation = exporterLocation;
        this.exporterId = exporterId;
        this.networkId = networkId;

        this.inventory = Bukkit.createInventory(null, 45, plugin.getMessageManager().getMessageComponent(null, "gui.exporter.title"));

        detectTargetType();
        loadCurrentFilters();
        
        if (isBrewingStandTarget) {
            setupBrewingStandGUI();
        } else {
            setupGUI();
        }
    }

    /**
     * Detect what type of block this exporter is targeting
     */
    private void detectTargetType() {
        try {
            Block exporterBlock = exporterLocation.getBlock();
            Block attachedBlock = null;

            // Use same logic as ExporterManager to find target block
            if (exporterBlock.getType() == Material.PLAYER_HEAD) {
                // Floor mounted head - check block below
                attachedBlock = exporterBlock.getRelative(BlockFace.DOWN);
            } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
                // Wall mounted head - check block it's attached to
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) exporterBlock.getBlockData();
                BlockFace facing = directional.getFacing();
                attachedBlock = exporterBlock.getRelative(facing.getOppositeFace());
            }

            if (attachedBlock != null && attachedBlock.getType() == Material.BREWING_STAND) {
                isBrewingStandTarget = true;
                plugin.debugLog("Detected brewing stand target for exporter at " + exporterLocation);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error detecting exporter target type: " + e.getMessage());
        }
    }

    private void loadCurrentFilters() {
        currentFilterItems.clear();
        currentFilterItems.addAll(plugin.getExporterManager().getExporterFilterItems(exporterId));
        
        if (isBrewingStandTarget) {
            loadBrewingStandSettings();
        }
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
                    lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.item-description"));
                    lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.remove-instruction"));
                    meta.lore(lore);
                    displayItem.setItemMeta(meta);
                }

                inventory.setItem(i, displayItem);
                slotToFilterItem.put(i, filterItem);
            } else {
                ItemStack placeholder = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
                ItemMeta meta = placeholder.getItemMeta();
                meta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.empty-slot"));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.add-instruction"));
                lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.shift-instruction"));
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
        statusMeta.displayName(plugin.getMessageManager().getMessageComponent(null, isEnabled ? "gui.exporter.status.enabled" : "gui.exporter.status.disabled"));

        List<Component> statusLore = new ArrayList<>();
        if (!isEnabled && currentFilterItems.isEmpty()) {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.no-filter"));
        } else {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, isEnabled ? "gui.exporter.status.description-enabled" : "gui.exporter.status.description-disabled"));
        }

        Container target = getTargetContainer();
        statusLore.add(Component.empty());
        if (target != null) {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.target.connected", "container", target.getBlock().getType().name()));
        } else {
            statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.target.none"));
        }

        statusLore.add(Component.empty());
        statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.status.toggle"));
        statusMeta.lore(statusLore);
        status.setItemMeta(statusMeta);
        inventory.setItem(29, status);

        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.controls.clear"));
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.empty());
        clearLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.controls.clear-description"));
        clearMeta.lore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(31, clearButton);

        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.controls.info"));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.info.network", "network", networkId.substring(0, Math.min(16, networkId.length()))));
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.info.slots", "filled", currentFilterItems.size(), "total", 18));
        infoLore.add(Component.empty());
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.shift-instruction"));
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.add-instruction"));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(35, info);
    }

    /**
     * Setup specialized GUI for brewing stand targeting
     * Layout: [][F][][M][][][][][]
     *         [][][][][][][][][]
     *         [][][][B][B][B][][][]
     *         [][][][][][][][][]
     *         [][][O][][C][][I][][]
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

        // F = Fuel toggle (slot 2)
        setupFuelToggle();
        
        // M = Magenta slot (slot 4) - ingredient filter
        setupIngredientSlot();
        
        // B = Blue slots (slots 21, 22, 23) - bottle filters
        setupBottleSlots();
        
        // O = On/off toggle (slot 38)
        setupStatusToggle();
        
        // C = Clear filter (slot 40)
        setupClearButton();
        
        // I = Info (slot 42) - book
        setupInfoPanel();
    }
    
    private void setupFuelToggle() {
        ItemStack fuelToggle;
        if (blazePowderEnabled) {
            fuelToggle = new ItemStack(Material.BLAZE_POWDER);
            fuelToggle.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        } else {
            fuelToggle = new ItemStack(Material.BARRIER);
        }
        
        ItemMeta meta = fuelToggle.getItemMeta();
        meta.displayName(plugin.getMessageManager().getMessageComponent(null, blazePowderEnabled ? "gui.exporter.furnace.fuel-enabled" : "gui.exporter.furnace.fuel-disabled"));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.brewing-fuel-description"));
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.status.toggle"));
        meta.lore(lore);
        fuelToggle.setItemMeta(meta);
        inventory.setItem(2, fuelToggle);
    }
    
    private void setupIngredientSlot() {
        ItemStack ingredientSlot;
        if (ingredientFilter != null) {
            ingredientSlot = ingredientFilter.clone();
            ingredientSlot.setAmount(1);
            ItemMeta meta = ingredientSlot.getItemMeta();
            List<Component> lore = (meta.hasLore() && meta.lore() != null) ? 
                new ArrayList<>(Objects.requireNonNull(meta.lore())) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.ingredient-filter"));
            lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.remove-instruction"));
            meta.lore(lore);
            ingredientSlot.setItemMeta(meta);
        } else {
            ingredientSlot = new ItemStack(Material.MAGENTA_STAINED_GLASS_PANE);
            ItemMeta meta = ingredientSlot.getItemMeta();
            meta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.ingredient-slot"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.ingredient-description"));
            lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.add-instruction"));
            meta.lore(lore);
            ingredientSlot.setItemMeta(meta);
        }
        inventory.setItem(4, ingredientSlot);
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
                lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.bottle-filter", "slot", bottleNames[i]));
                lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.remove-instruction"));
                meta.lore(lore);
                bottleSlot.setItemMeta(meta);
            } else {
                bottleSlot = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
                ItemMeta meta = bottleSlot.getItemMeta();
                String slotKey = "gui.exporter.furnace.bottle-" + bottleNames[i].toLowerCase();
                meta.displayName(plugin.getMessageManager().getMessageComponent(null, slotKey));
                List<Component> lore = new ArrayList<>();
                lore.add(Component.empty());
                lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.bottle-description"));
                lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.filter.add-instruction"));
                meta.lore(lore);
                bottleSlot.setItemMeta(meta);
            }
            inventory.setItem(bottleSlots[i], bottleSlot);
        }
    }
    
    private void setupStatusToggle() {
        ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
        boolean isEnabled = data != null && data.enabled;

        ItemStack status = new ItemStack(isEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = status.getItemMeta();
        statusMeta.displayName(plugin.getMessageManager().getMessageComponent(null, isEnabled ? "gui.exporter.status.enabled" : "gui.exporter.status.disabled"));

        List<Component> statusLore = new ArrayList<>();
        statusLore.add(plugin.getMessageManager().getMessageComponent(null, isEnabled ? "gui.exporter.status.description-enabled" : "gui.exporter.status.description-disabled"));
        statusLore.add(Component.empty());
        statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.target.connected", "container", "Brewing Stand"));
        statusLore.add(Component.empty());
        statusLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.status.toggle"));
        statusMeta.lore(statusLore);
        status.setItemMeta(statusMeta);
        inventory.setItem(38, status);
    }
    
    private void setupClearButton() {
        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.controls.clear"));
        List<Component> clearLore = new ArrayList<>();
        clearLore.add(Component.empty());
        clearLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.controls.clear-description"));
        clearMeta.lore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(40, clearButton);
    }
    
    private void setupInfoPanel() {
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.title"));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.info.network", "network", networkId.substring(0, Math.min(16, networkId.length()))));
        infoLore.add(Component.empty());
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.info-fuel"));
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.info-ingredient"));
        infoLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.exporter.furnace.info-bottles"));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(42, info);
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

        if (isBrewingStandTarget) {
            handleBrewingStandClick(player, slot, event);
        } else {
            handleRegularExporterClick(player, slot, event);
        }
    }

    private void handleRegularExporterClick(Player player, int slot, InventoryClickEvent event) {
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
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.drag-instruction"));
            }
            return;
        }

        if (slot < inventory.getSize()) {
            event.setCancelled(true);
            
            ItemStack cursorItem = event.getCursor();

            // Handle fuel toggle (slot 2)
            if (slot == 2) {
                blazePowderEnabled = !blazePowderEnabled;
                saveBrewingStandSettings();
                setupBrewingStandGUI();
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, blazePowderEnabled ? "gui.exporter.furnace.fuel-enabled-msg" : "gui.exporter.furnace.fuel-disabled-msg"));
                return;
            }

            // Handle ingredient slot (slot 4)
            if (slot == 4) {
                if (!cursorItem.getType().isAir()) {
                    // Set ingredient filter
                    if (!plugin.getItemManager().isItemBlacklisted(cursorItem)) {
                        ingredientFilter = cursorItem.clone();
                        ingredientFilter.setAmount(1);
                        saveBrewingStandSettings();
                        setupBrewingStandGUI();
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.ingredient-set", "item", cursorItem.getType()));
                    } else {
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.item-blacklisted"));
                    }
                } else if (ingredientFilter != null) {
                    // Remove ingredient filter
                    ingredientFilter = null;
                    saveBrewingStandSettings();
                    setupBrewingStandGUI();
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.ingredient-removed"));
                }
                return;
            }

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
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.bottle-set", "slot", (i + 1), "item", cursorItem.getType()));
                        } else {
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.item-blacklisted"));
                        }
                    } else if (bottleFilters[i] != null) {
                        // Remove bottle filter
                        bottleFilters[i] = null;
                        saveBrewingStandSettings();
                        setupBrewingStandGUI();
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.bottle-removed", "slot", (i + 1)));
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
            ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
            if (data != null) {
                try {
                    boolean newState = !data.enabled;
                    plugin.getExporterManager().toggleExporter(exporterId, newState);
                    setupBrewingStandGUI();
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, newState ? "gui.device.enabled" : "gui.device.disabled", "device", "Exporter"));
                } catch (Exception e) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.device.toggle-error", "device", "exporter", "error", e.getMessage()));
                    plugin.getLogger().severe("Error toggling exporter: " + e.getMessage());
                }
            }
        } else if (slot == 40) {
            // Clear all filters (C slot)
            blazePowderEnabled = false;
            ingredientFilter = null;
            bottleFilters = new ItemStack[3];
            saveBrewingStandSettings();
            setupBrewingStandGUI();
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.filters-cleared"));
        }
        // Info panel (slot 42) doesn't need click handling
    }

    /**
     * Handle drag operations for brewing stand GUI
     */
    private void handleBrewingStandDrag(Player player, ItemStack draggedItem, Set<Integer> slots) {
        // Check if item is blacklisted
        if (plugin.getItemManager().isItemBlacklisted(draggedItem)) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.item-blacklisted"));
            return;
        }
        
        // Determine target slot based on where the drag occurred
        for (int slot : slots) {
            if (slot == 4) {
                // Ingredient slot
                ingredientFilter = draggedItem.clone();
                ingredientFilter.setAmount(1);
                saveBrewingStandSettings();
                setupBrewingStandGUI();
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.ingredient-set", "item", draggedItem.getType()));
                return;
            } else if (slot == 21 || slot == 22 || slot == 23) {
                // Bottle slots
                int bottleIndex = slot - 21; // Convert to 0, 1, 2
                bottleFilters[bottleIndex] = draggedItem.clone();
                bottleFilters[bottleIndex].setAmount(1);
                saveBrewingStandSettings();
                setupBrewingStandGUI();
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.bottle-set", "slot", (bottleIndex + 1), "item", draggedItem.getType()));
                return;
            }
        }
        
        // If no valid slot found, show message
        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.furnace.drag-instruction"));
    }

    /**
     * Save brewing stand specific settings to database
     */
    private void saveBrewingStandSettings() {
        // For now, we'll store brewing stand settings in the regular filter system
        // This is a simplified approach - in production you might want a separate table
        currentFilterItems.clear();
        
        // Store blaze powder setting as a special marker item if enabled
        if (blazePowderEnabled) {
            ItemStack blazeMarker = new ItemStack(Material.BLAZE_POWDER);
            ItemMeta meta = blazeMarker.getItemMeta();
            meta.displayName(Component.text("BREWING_FUEL_ENABLED"));
            blazeMarker.setItemMeta(meta);
            currentFilterItems.add(blazeMarker);
        }
        
        // Store ingredient filter
        if (ingredientFilter != null) {
            ItemStack ingredientMarker = ingredientFilter.clone();
            ItemMeta meta = ingredientMarker.getItemMeta();
            meta.displayName(Component.text("BREWING_INGREDIENT"));
            ingredientMarker.setItemMeta(meta);
            currentFilterItems.add(ingredientMarker);
        }
        
        // Store bottle filters
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
     * Load brewing stand specific settings from database
     */
    private void loadBrewingStandSettings() {
        if (!isBrewingStandTarget) return;
        
        blazePowderEnabled = false;
        ingredientFilter = null;
        bottleFilters = new ItemStack[3];
        
        for (ItemStack item : currentFilterItems) {
            if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                Component displayName = item.getItemMeta().displayName();
                if (displayName == null) continue;
                
                String nameText = ((net.kyori.adventure.text.TextComponent) displayName).content();
                
                if ("BREWING_FUEL_ENABLED".equals(nameText)) {
                    blazePowderEnabled = true;
                } else if ("BREWING_INGREDIENT".equals(nameText)) {
                    ingredientFilter = item.clone();
                    // Remove marker by getting meta, modifying it, and setting it back
                    ItemMeta ingredientMeta = ingredientFilter.getItemMeta();
                    ingredientMeta.displayName(null);
                    ingredientFilter.setItemMeta(ingredientMeta);
                } else if (nameText.startsWith("BREWING_BOTTLE_")) {
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
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.item-blacklisted"));
                return;
            }
            
            String newItemHash = plugin.getItemManager().generateItemHash(cursorItem);
            for (ItemStack existingItem : currentFilterItems) {
                String existingHash = plugin.getItemManager().generateItemHash(existingItem);
                if (newItemHash.equals(existingHash)) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.already-exists"));
                    return;
                }
            }

            if (currentFilterItems.size() >= 18) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.full"));
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
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.removed", "type", "export"));
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
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.item-blacklisted"));
            return;
        }
        
        String newItemHash = plugin.getItemManager().generateItemHash(itemToAdd);
        for (ItemStack existingItem : currentFilterItems) {
            String existingHash = plugin.getItemManager().generateItemHash(existingItem);
            if (newItemHash.equals(existingHash)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.already-exists"));
                return;
            }
        }

        if (currentFilterItems.size() >= 18) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.full"));
            return;
        }

        ItemStack filterTemplate = itemToAdd.clone();
        filterTemplate.setAmount(1);

        currentFilterItems.add(filterTemplate);
        saveFilters();
        setupGUI();
        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.added", "item", itemToAdd.getType(), "type", "export"));
    }

    private void handleToggleClick(Player player) {
        try {
            ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
            if (data == null) return;

            if (currentFilterItems.isEmpty() && !data.enabled) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.filter.no-filter"));
                return;
            }

            boolean newState = !data.enabled;
            plugin.getExporterManager().toggleExporter(exporterId, newState);
            setupGUI();

            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, newState ? "gui.device.enabled" : "gui.device.disabled", "device", "Exporter"));
        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.device.toggle-error", "device", "exporter", "error", e.getMessage()));
        }
    }

    private void handleClearFilters(Player player) {
        if (currentFilterItems.isEmpty()) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.controls.no-filters"));
            return;
        }

        currentFilterItems.clear();
        saveFilters();
        setupGUI();
        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.exporter.controls.cleared"));
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
                if (isBrewingStandTarget) {
                    handleBrewingStandDrag(player, draggedItem, event.getRawSlots());
                } else {
                    // Regular exporter drag handling
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
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.item-blacklisted"));
                            return;
                        }
                    
                    String newItemHash = plugin.getItemManager().generateItemHash(draggedItem);
                    for (ItemStack existingItem : currentFilterItems) {
                        String existingHash = plugin.getItemManager().generateItemHash(existingItem);
                        if (newItemHash.equals(existingHash)) {
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.already-exists"));
                            return;
                        }
                    }

                    if (currentFilterItems.size() >= 18) {
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.filter.full"));
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