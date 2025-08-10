package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.managers.ExporterManager;
import org.jamesphbennett.massstorageserver.storage.StoredItem;

import java.util.*;

public class ExporterGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location exporterLocation;
    private final String exporterId;
    private final String networkId;
    private final Inventory inventory;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Filter slots (18 slots for filters)
    private final Map<Integer, String> slotToItemHash = new HashMap<>();
    private final List<String> currentFilters = new ArrayList<>();

    public ExporterGUI(MassStorageServer plugin, Location exporterLocation, String exporterId, String networkId) {
        this.plugin = plugin;
        this.exporterLocation = exporterLocation;
        this.exporterId = exporterId;
        this.networkId = networkId;

        // Create inventory - 5 rows (45 slots)
        this.inventory = Bukkit.createInventory(null, 45, legacySerialize("<dark_purple>Exporter Configuration"));

        loadCurrentFilters();
        setupGUI();
    }

    private void loadCurrentFilters() {
        currentFilters.clear();
        currentFilters.addAll(plugin.getExporterManager().getExporterFilters(exporterId));
    }

    private void setupGUI() {
        // Clear inventory
        inventory.clear();
        slotToItemHash.clear();

        // Top section (rows 0-1): Filter slots (18 slots)
        displayFilterSlots();

        // Divider row (row 2): Glass panes
        ItemStack divider = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta dividerMeta = divider.getItemMeta();
        dividerMeta.setDisplayName(" ");
        divider.setItemMeta(dividerMeta);
        for (int i = 18; i < 27; i++) {
            inventory.setItem(i, divider);
        }

        // Bottom section (rows 3-4): Control buttons
        setupControlButtons();
    }

    private void displayFilterSlots() {
        // Display current filters
        for (int i = 0; i < Math.min(18, currentFilters.size()); i++) {
            String itemHash = currentFilters.get(i);
            ItemStack filterItem = getItemFromHash(itemHash);
            if (filterItem != null) {
                ItemStack displayItem = filterItem.clone();
                ItemMeta meta = displayItem.getItemMeta();
                
                List<String> lore = new ArrayList<>();
                lore.add(legacySerialize("<gray>Filter Item"));
                lore.add("");
                lore.add(legacySerialize("<yellow>Click to remove from filter"));
                meta.setLore(lore);
                
                // Add glowing effect
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                
                displayItem.setItemMeta(meta);
                inventory.setItem(i, displayItem);
                slotToItemHash.put(i, itemHash);
            }
        }

        // Fill empty filter slots with placeholder
        for (int i = currentFilters.size(); i < 18; i++) {
            ItemStack emptySlot = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            ItemMeta emptyMeta = emptySlot.getItemMeta();
            emptyMeta.setDisplayName(legacySerialize("<gray>Empty Filter Slot"));
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add(legacySerialize("<yellow>Place an item here to add to filter"));
            emptyMeta.setLore(lore);
            emptySlot.setItemMeta(emptyMeta);
            inventory.setItem(i, emptySlot);
        }
    }

    private void setupControlButtons() {
        ExporterManager.ExporterData exporterData = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
        boolean isEnabled = exporterData != null && exporterData.enabled;

        // Status indicator (slot 29)
        ItemStack status = new ItemStack(isEnabled ? Material.LIME_DYE : Material.GRAY_DYE);
        ItemMeta statusMeta = status.getItemMeta();
        statusMeta.setDisplayName(legacySerialize(isEnabled ? "<green>Status: Enabled" : "<gray>Status: Disabled"));
        
        List<String> statusLore = new ArrayList<>();
        if (!isEnabled && currentFilters.isEmpty()) {
            statusLore.add(legacySerialize("<red>Add items to filter to enable"));
        } else {
            statusLore.add(legacySerialize("<gray>Exporter is " + (isEnabled ? "actively exporting" : "inactive")));
        }
        
        // Add connection status
        Container target = getTargetContainer();
        if (target != null) {
            statusLore.add("");
            statusLore.add(legacySerialize("<aqua>Connected to: " + target.getBlock().getType().name()));
        } else {
            statusLore.add("");
            statusLore.add(legacySerialize("<red>No valid container connected"));
        }
        
        statusLore.add("");
        statusLore.add(legacySerialize("<yellow>Click to toggle"));
        statusMeta.setLore(statusLore);
        status.setItemMeta(statusMeta);
        inventory.setItem(29, status);

        // Clear filters button (slot 31)
        ItemStack clearButton = new ItemStack(Material.BARRIER);
        ItemMeta clearMeta = clearButton.getItemMeta();
        clearMeta.setDisplayName(legacySerialize("<red>Clear All Filters"));
        List<String> clearLore = new ArrayList<>();
        clearLore.add("");
        clearLore.add(legacySerialize("<yellow>Click to remove all filter items"));
        clearMeta.setLore(clearLore);
        clearButton.setItemMeta(clearMeta);
        inventory.setItem(31, clearButton);

        // Info panel (slot 33)
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName(legacySerialize("<aqua>Exporter Information"));
        List<String> infoLore = new ArrayList<>();
        infoLore.add(legacySerialize("<gray>Network: " + networkId.substring(0, Math.min(16, networkId.length()))));
        infoLore.add(legacySerialize("<gray>Filters: " + currentFilters.size() + "/18"));
        
        if (exporterData != null && exporterData.lastExport > 0) {
            long timeSinceExport = System.currentTimeMillis() - exporterData.lastExport;
            infoLore.add(legacySerialize("<gray>Last Export: " + formatTime(timeSinceExport) + " ago"));
        }
        
        infoLore.add("");
        infoLore.add(legacySerialize("<yellow>Exports 1 stack per tick"));
        infoLore.add(legacySerialize("<yellow>Cycles through filter items"));
        infoMeta.setLore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(33, info);

        // Add items from network button (slot 40)
        ItemStack addButton = new ItemStack(Material.HOPPER);
        ItemMeta addMeta = addButton.getItemMeta();
        addMeta.setDisplayName(legacySerialize("<green>Add Items from Network"));
        List<String> addLore = new ArrayList<>();
        addLore.add("");
        addLore.add(legacySerialize("<yellow>Click to browse network items"));
        addLore.add(legacySerialize("<yellow>and add them to the filter"));
        addMeta.setLore(addLore);
        addButton.setItemMeta(addMeta);
        inventory.setItem(40, addButton);
    }

    private Container getTargetContainer() {
        Block exporterBlock = exporterLocation.getBlock();
        if (exporterBlock.getType() != Material.PLAYER_HEAD && exporterBlock.getType() != Material.PLAYER_WALL_HEAD) {
            return null;
        }

        org.bukkit.block.BlockFace[] validFaces = {
            org.bukkit.block.BlockFace.NORTH,
            org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST,
            org.bukkit.block.BlockFace.WEST,
            org.bukkit.block.BlockFace.DOWN
        };

        for (org.bukkit.block.BlockFace face : validFaces) {
            Block targetBlock = exporterBlock.getRelative(face);
            if (targetBlock.getState() instanceof Container container) {
                return container;
            }
        }

        return null;
    }

    private ItemStack getItemFromHash(String itemHash) {
        try {
            // Get network items and find matching hash
            List<StoredItem> networkItems = plugin.getStorageManager().getNetworkItems(networkId);
            for (StoredItem storedItem : networkItems) {
                if (storedItem.getItemHash().equals(itemHash)) {
                    return storedItem.getItemStack();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting item from hash: " + e.getMessage());
        }
        return null;
    }

    private String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        if (seconds < 60) {
            return seconds + "s";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m " + (seconds % 60) + "s";
        } else {
            return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        }
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

        // Handle clicks in filter area (0-17)
        if (slot >= 0 && slot < 18) {
            handleFilterSlotClick(event, player, slot);
            return;
        }

        // Handle control button clicks
        switch (slot) {
            case 29 -> { // Toggle enable/disable
                event.setCancelled(true);
                handleToggleClick(player);
            }
            case 31 -> { // Clear filters
                event.setCancelled(true);
                handleClearFilters(player);
            }
            case 40 -> { // Add from network
                event.setCancelled(true);
                openNetworkItemSelector(player);
            }
            default -> {
                // Cancel clicks in GUI area
                if (slot < 45) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private void handleFilterSlotClick(InventoryClickEvent event, Player player, int slot) {
        event.setCancelled(true);

        ItemStack cursorItem = event.getCursor();
        ItemStack slotItem = event.getCurrentItem();

        // If player has item on cursor, try to add it to filter
        if (cursorItem != null && !cursorItem.getType().isAir()) {
            String itemHash = plugin.getItemManager().generateItemHash(cursorItem);
            
            // Check if already in filter
            if (currentFilters.contains(itemHash)) {
                player.sendMessage(Component.text("This item is already in the filter!", NamedTextColor.RED));
                return;
            }

            // Check if filter is full
            if (currentFilters.size() >= 18) {
                player.sendMessage(Component.text("Filter is full! Remove items first.", NamedTextColor.RED));
                return;
            }

            // Add to filter
            currentFilters.add(itemHash);
            saveFilters();
            setupGUI();
            player.sendMessage(Component.text("Added " + cursorItem.getType() + " to filter", NamedTextColor.GREEN));
            
        } else if (slotItem != null && !slotItem.getType().isAir() && !slotItem.getType().name().contains("GLASS")) {
            // Remove from filter
            String hashToRemove = slotToItemHash.get(slot);
            if (hashToRemove != null) {
                currentFilters.remove(hashToRemove);
                slotToItemHash.remove(slot);
                saveFilters();
                setupGUI();
                player.sendMessage(Component.text("Removed item from filter", NamedTextColor.YELLOW));
            }
        }
    }

    private void handleToggleClick(Player player) {
        try {
            ExporterManager.ExporterData data = plugin.getExporterManager().getExporterAtLocation(exporterLocation);
            if (data == null) return;

            // Can't enable without filters
            if (currentFilters.isEmpty() && !data.enabled) {
                player.sendMessage(Component.text("Cannot enable exporter without filter items!", NamedTextColor.RED));
                return;
            }

            boolean newState = !data.enabled;
            plugin.getExporterManager().toggleExporter(exporterId, newState);
            setupGUI(); // Refresh GUI
            
            player.sendMessage(Component.text("Exporter " + (newState ? "enabled" : "disabled"), 
                    newState ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
        } catch (Exception e) {
            player.sendMessage(Component.text("Error toggling exporter: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleClearFilters(Player player) {
        if (currentFilters.isEmpty()) {
            player.sendMessage(Component.text("No filters to clear", NamedTextColor.YELLOW));
            return;
        }

        currentFilters.clear();
        slotToItemHash.clear();
        saveFilters();
        setupGUI();
        player.sendMessage(Component.text("Cleared all filters", NamedTextColor.YELLOW));
    }

    private void saveFilters() {
        try {
            plugin.getExporterManager().updateExporterFilter(exporterId, currentFilters);
        } catch (Exception e) {
            plugin.getLogger().severe("Error saving filters: " + e.getMessage());
        }
    }

    private void openNetworkItemSelector(Player player) {
        // Create a new inventory to show network items
        Inventory selector = Bukkit.createInventory(null, 54, legacySerialize("<dark_purple>Select Items to Export"));
        
        try {
            List<StoredItem> networkItems = plugin.getStorageManager().getNetworkItems(networkId);
            
            // Show up to 45 items (leaving bottom row for navigation)
            for (int i = 0; i < Math.min(45, networkItems.size()); i++) {
                StoredItem storedItem = networkItems.get(i);
                ItemStack displayItem = storedItem.getItemStack().clone();
                ItemMeta meta = displayItem.getItemMeta();
                
                List<String> lore = new ArrayList<>();
                if (meta.hasLore()) {
                    lore.addAll(meta.getLore());
                }
                lore.add("");
                lore.add(legacySerialize("<gray>Available: " + storedItem.getQuantity()));
                
                if (currentFilters.contains(storedItem.getItemHash())) {
                    lore.add(legacySerialize("<red>Already in filter"));
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                } else {
                    lore.add(legacySerialize("<yellow>Click to add to filter"));
                }
                
                meta.setLore(lore);
                displayItem.setItemMeta(meta);
                selector.setItem(i, displayItem);
            }
            
            // Add back button
            ItemStack backButton = new ItemStack(Material.BARRIER);
            ItemMeta backMeta = backButton.getItemMeta();
            backMeta.setDisplayName(legacySerialize("<red>Back to Exporter"));
            backButton.setItemMeta(backMeta);
            selector.setItem(49, backButton);
            
            // Open selector with its own click handler
            player.openInventory(selector);
            
            // Register temporary listener for selector
            Listener selectorListener = new Listener() {
                @EventHandler
                public void onSelectorClick(InventoryClickEvent e) {
                    if (!e.getInventory().equals(selector)) return;
                    e.setCancelled(true);
                    
                    if (e.getRawSlot() == 49) { // Back button
                        InventoryClickEvent.getHandlerList().unregister(this);
                        open(player);
                        return;
                    }
                    
                    if (e.getRawSlot() < 45 && e.getCurrentItem() != null && !e.getCurrentItem().getType().isAir()) {
                        // Add this item to filter
                        ItemStack clickedItem = e.getCurrentItem();
                        String itemHash = plugin.getItemManager().generateItemHash(clickedItem);
                        
                        if (!currentFilters.contains(itemHash) && currentFilters.size() < 18) {
                            currentFilters.add(itemHash);
                            saveFilters();
                            player.sendMessage(Component.text("Added " + clickedItem.getType() + " to filter", NamedTextColor.GREEN));
                            
                            // Go back to main GUI
                            InventoryClickEvent.getHandlerList().unregister(this);
                            open(player);
                        } else if (currentFilters.contains(itemHash)) {
                            player.sendMessage(Component.text("Item already in filter!", NamedTextColor.RED));
                        } else {
                            player.sendMessage(Component.text("Filter is full!", NamedTextColor.RED));
                        }
                    }
                }
                
                @EventHandler
                public void onSelectorClose(InventoryCloseEvent e) {
                    if (!e.getInventory().equals(selector)) return;
                    InventoryClickEvent.getHandlerList().unregister(this);
                    InventoryCloseEvent.getHandlerList().unregister(this);
                }
            };
            
            plugin.getServer().getPluginManager().registerEvents(selectorListener, plugin);
            
        } catch (Exception e) {
            player.sendMessage(Component.text("Error loading network items: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        
        // Allow dragging only in filter slots
        for (int slot : event.getRawSlots()) {
            if (slot >= 18 || slot < 0) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        // Unregister this listener
        InventoryClickEvent.getHandlerList().unregister(this);
        InventoryDragEvent.getHandlerList().unregister(this);
        InventoryCloseEvent.getHandlerList().unregister(this);

        // Remove from GUI manager
        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().closeGUI(player);
        }
    }

    private String legacySerialize(String miniMessage) {
        Component component = this.miniMessage.deserialize(miniMessage);
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    public Location getExporterLocation() {
        return exporterLocation;
    }

    public String getNetworkId() {
        return networkId;
    }
}
