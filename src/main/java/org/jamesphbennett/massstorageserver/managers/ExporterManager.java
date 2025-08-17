package org.jamesphbennett.massstorageserver.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.storage.StoredItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ExporterManager {

    private final MassStorageServer plugin;
    private final Map<String, ExporterData> activeExporters = new ConcurrentHashMap<>();
    private final Map<String, Integer> exporterCycleIndex = new ConcurrentHashMap<>();

    public ExporterManager(MassStorageServer plugin) {
        this.plugin = plugin;
        loadExporters();
        startExportTask();
    }

    /**
     * Data class for exporter information
     */
    public static class ExporterData {
        public final String exporterId;
        public final String networkId;
        public final Location location;
        public boolean enabled;
        public final List<String> filterItems = new ArrayList<>();
        public long lastExport;

        public ExporterData(String exporterId, String networkId, Location location, boolean enabled) {
            this.exporterId = exporterId;
            this.networkId = networkId;
            this.location = location;
            this.enabled = enabled;
            this.lastExport = System.currentTimeMillis();
        }
    }

    /**
     * Load all exporters from database
     */
    private void loadExporters() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT e.exporter_id, e.network_id, e.world_name, e.x, e.y, e.z, e.enabled, e.last_export " +
                             "FROM exporters e")) {

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String exporterId = rs.getString("exporter_id");
                    String networkId = rs.getString("network_id");
                    String worldName = rs.getString("world_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    boolean enabled = rs.getBoolean("enabled");

                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world != null) {
                        Location location = new Location(world, x, y, z);
                        ExporterData data = new ExporterData(exporterId, networkId, location, enabled);

                        // Load filters for this exporter
                        loadExporterFilters(conn, data);

                        activeExporters.put(exporterId, data);
                        exporterCycleIndex.put(exporterId, 0);
                    }
                }
            }

            plugin.getLogger().info("Loaded " + activeExporters.size() + " exporters");

        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading exporters: " + e.getMessage());
        }
    }

    /**
     * Load filters for a specific exporter - loads hashes for compatibility with export logic
     */
    private void loadExporterFilters(Connection conn, ExporterData data) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT item_hash FROM exporter_filters WHERE exporter_id = ? AND filter_type = 'whitelist'")) {
            stmt.setString(1, data.exporterId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    data.filterItems.add(rs.getString("item_hash"));
                }
            }
        }
    }

    /**
     * Start the periodic export task
     */
    private void startExportTask() {
        int tickInterval = plugin.getConfigManager().getExportTickInterval();

        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (ExporterData exporter : activeExporters.values()) {
                if (exporter.enabled && !exporter.filterItems.isEmpty()) {
                    processExport(exporter);
                }
            }
        }, tickInterval, tickInterval);

        // ADDED: Periodic validation of exporter network assignments (every 30 seconds)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateExporterNetworkAssignments, 600L, 600L); // 600 ticks = 30 seconds

        // REMOVED: Particle effects disabled for performance

        plugin.getLogger().info("Export task started with " + tickInterval + " tick interval");
        plugin.getLogger().info("Exporter network validation task started (30 second interval)");
    }
    /**
     * ENHANCED: Check if exporter is physically connected to its assigned network
     * This checks adjacent blocks directly instead of relying on network detection at exporter location
     */
    private boolean isExporterConnectedToNetwork(ExporterData exporter) {
        // First check if network exists at all
        if (!plugin.getNetworkManager().isNetworkValid(exporter.networkId)) {
            return true;
        }

        // AGGRESSIVE CHECK: Look for any adjacent network blocks or cables
        Location exporterLoc = exporter.location;

        // Check all 6 adjacent faces for network connectivity
        Location[] adjacentLocs = {
                exporterLoc.clone().add(1, 0, 0),   // East
                exporterLoc.clone().add(-1, 0, 0),  // West
                exporterLoc.clone().add(0, 1, 0),   // Up
                exporterLoc.clone().add(0, -1, 0),  // Down
                exporterLoc.clone().add(0, 0, 1),   // South
                exporterLoc.clone().add(0, 0, -1)   // North
        };

        for (Location loc : adjacentLocs) {
            String networkId = plugin.getNetworkManager().getNetworkId(loc);

            if (exporter.networkId.equals(networkId)) {
                return false; // Found matching network at adjacent location
            }
        }

        return true; // No adjacent network blocks found
    }
    /**
     * Refresh any open exporter GUIs for a specific exporter
     */
    private void refreshExporterGUIs(String exporterId) {
        try {
            // Notify GUI manager to refresh this specific exporter's GUIs
            plugin.getGUIManager().refreshExporterGUIs(exporterId);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to refresh exporter GUIs for " + exporterId + ": " + e.getMessage());
        }
    }
    /**
     * Process a single export operation
     */
    private void processExport(ExporterData exporter) {
        try {
            // Check if exporter is physically connected to its network
            if (isExporterConnectedToNetwork(exporter)) {
                plugin.getLogger().info("Exporter " + exporter.exporterId + " is no longer connected to network " + exporter.networkId + " - auto-disabling");

                // AUTO-DISABLE: Set exporter as disabled when disconnected
                try {
                    toggleExporter(exporter.exporterId, false);
                    plugin.getLogger().info("Auto-disabled exporter " + exporter.exporterId + " due to network disconnection");

                    // Refresh any open exporter GUIs to show disabled status
                    refreshExporterGUIs(exporter.exporterId);

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to auto-disable exporter " + exporter.exporterId + ": " + e.getMessage());
                }

                return;
            }

            // Find the connected inventory
            Block exporterBlock = exporter.location.getBlock();
            if (exporterBlock.getType() != Material.PLAYER_HEAD && exporterBlock.getType() != Material.PLAYER_WALL_HEAD) {
                plugin.getLogger().warning("Exporter at " + exporter.location + " is not a player head!");
                return;
            }

            // Get the target inventory
            Container targetContainer = getTargetContainer(exporterBlock);
            if (targetContainer == null) {
                return; // No valid target
            }

            Inventory targetInventory = targetContainer.getInventory();

            // Check if inventory has space (including partial stacks)
            if (!hasInventorySpace(targetInventory)) {
                return; // Inventory is completely full
            }

            // Check if this is a brewing stand and handle specially
            Material containerType = targetContainer.getBlock().getType();
            boolean isBrewingStand = containerType == Material.BREWING_STAND;
            
            if (isBrewingStand) {
                // Use brewing stand specific logic that handles slot selection internally
                exportBrewingStandWithSlotSelection(exporter, targetContainer);
            } else {
                // Use regular round-robin for other containers
                String itemHashToExport = getNextItemToExport(exporter);
                plugin.getLogger().info("DEBUG: getNextItemToExport returned: " + itemHashToExport + " for exporter " + exporter.exporterId);
                
                if (itemHashToExport == null) {
                    plugin.getLogger().info("DEBUG: No items to export for exporter " + exporter.exporterId + ", filter items: " + exporter.filterItems.size());
                    return; // No items to export
                }

                // Try to export the item with slot-specific routing
                exportItemWithSlotRouting(exporter, itemHashToExport, targetContainer);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error processing export for " + exporter.exporterId + ": " + e.getMessage());
        }
    }

    /**
     * FIXED: Check if inventory has any available space (empty slots or partial stacks)
     */
    private boolean hasInventorySpace(Inventory inventory) {
        // First check for any empty slots
        if (inventory.firstEmpty() != -1) {
            return true;
        }

        // Then check for any partial stacks that can be filled
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getAmount() < item.getMaxStackSize()) {
                return true; // Found a partial stack
            }
        }

        return false; // No space available
    }

    /**
     * FIXED: Add items to inventory with stack optimization (fills partial stacks first)
     * @param targetInventory The inventory to add items to
     * @param itemToAdd The item to add
     * @return The amount of items that couldn't be placed
     */
    private int addItemWithStackOptimization(Inventory targetInventory, ItemStack itemToAdd) {
        int remainingAmount = itemToAdd.getAmount();
        int maxStackSize = itemToAdd.getMaxStackSize();

        for (int slot = 0; slot < targetInventory.getSize(); slot++) {
            ItemStack existing = targetInventory.getItem(slot);
            if (existing != null && existing.isSimilar(itemToAdd)) {
                int currentAmount = existing.getAmount();
                if (currentAmount < maxStackSize) {
                    int canAdd = Math.min(remainingAmount, maxStackSize - currentAmount);
                    existing.setAmount(currentAmount + canAdd);
                    remainingAmount -= canAdd;

                    if (remainingAmount <= 0) {
                        return 0; // All items placed successfully
                    }
                }
            }
        }

        for (int slot = 0; slot < targetInventory.getSize() && remainingAmount > 0; slot++) {
            ItemStack existing = targetInventory.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                int toPlace = Math.min(remainingAmount, maxStackSize);
                ItemStack newStack = itemToAdd.clone();
                newStack.setAmount(toPlace);
                targetInventory.setItem(slot, newStack);
                remainingAmount -= toPlace;
            }
        }

        return remainingAmount; // Return how many items couldn't be placed
    }

    /**
     * Get the next item to export from a brewing stand by checking actual filter items and slot availability
     */
    private String getNextBrewingStandItem(ExporterData exporter) {
        try {
            plugin.getLogger().info("DEBUG: getNextBrewingStandItem called for exporter " + exporter.exporterId);
            
            // Parse brewing stand filters to get actual items (not markers)
            BrewingStandFilters brewingFilters = parseBrewingStandFilters(exporter.exporterId);
            plugin.getLogger().info("DEBUG: Parsed brewing filters - fuel enabled: " + brewingFilters.fuelEnabled + 
                                  ", ingredient: " + (brewingFilters.ingredientFilter != null ? brewingFilters.ingredientFilter.getType() : "null") +
                                  ", bottles: " + java.util.Arrays.toString(brewingFilters.bottleFilters));
            
            // Get the brewing stand container to check slot availability
            Container brewingStandContainer = getTargetContainer(exporter.location.getBlock());
            if (brewingStandContainer == null) {
                plugin.getLogger().info("DEBUG: No brewing stand container found");
                return null;
            }
            
            Inventory brewingInventory = brewingStandContainer.getInventory();
            
            // Create a list of all possible exports with their priority
            List<PotentialExport> potentialExports = new ArrayList<>();
            
            // Check blaze powder (fuel) - only if fuel slot has space
            if (brewingFilters.fuelEnabled) {
                ItemStack blazePowder = new ItemStack(Material.BLAZE_POWDER);
                String blazeHash = plugin.getItemManager().generateItemHash(blazePowder);
                if (isItemAvailableInNetwork(exporter.networkId, blazeHash) && 
                    canPlaceItemInBrewingSlot(brewingInventory, blazePowder, 4)) {
                    potentialExports.add(new PotentialExport(blazeHash, 4, "fuel"));
                }
            }
            
            // Check ingredient filter - only if ingredient slot has space
            if (brewingFilters.ingredientFilter != null) {
                String ingredientHash = plugin.getItemManager().generateItemHash(brewingFilters.ingredientFilter);
                if (isItemAvailableInNetwork(exporter.networkId, ingredientHash) && 
                    canPlaceItemInBrewingSlot(brewingInventory, brewingFilters.ingredientFilter, 3)) {
                    potentialExports.add(new PotentialExport(ingredientHash, 3, "ingredient"));
                }
            }
            
            // Check each bottle filter independently - only if their specific slots have space
            for (int i = 0; i < 3; i++) {
                if (brewingFilters.bottleFilters[i] != null) {
                    String bottleHash = plugin.getItemManager().generateItemHash(brewingFilters.bottleFilters[i]);
                    if (isItemAvailableInNetwork(exporter.networkId, bottleHash) && 
                        canPlaceItemInBrewingSlot(brewingInventory, brewingFilters.bottleFilters[i], i)) {
                        potentialExports.add(new PotentialExport(bottleHash, i, "bottle " + (i + 1)));
                    }
                }
            }
            
            // If we have potential exports, use round-robin to cycle through them
            if (!potentialExports.isEmpty()) {
                // Get current cycle index for this exporter (reuse existing mechanism)
                int currentIndex = exporterCycleIndex.get(exporter.exporterId);
                PotentialExport selectedExport = potentialExports.get(currentIndex % potentialExports.size());
                
                // Update cycle index for next time
                exporterCycleIndex.put(exporter.exporterId, (currentIndex + 1) % potentialExports.size());
                
                plugin.getLogger().info("DEBUG: Selected " + selectedExport.slotName + " for export: " + selectedExport.itemHash);
                return selectedExport.itemHash;
            }
            
            plugin.getLogger().info("DEBUG: No brewing stand items available for export (either not in network or slots full)");
            return null;
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error getting next brewing stand item: " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if an item can be placed in a specific brewing stand slot
     */
    private boolean canPlaceItemInBrewingSlot(Inventory brewingInventory, ItemStack itemToPlace, int slot) {
        if (slot < 0 || slot >= brewingInventory.getSize()) {
            return false;
        }
        
        ItemStack existingItem = brewingInventory.getItem(slot);
        
        // Slot is empty - can place
        if (existingItem == null || existingItem.getType() == Material.AIR) {
            return true;
        }
        
        // Slot has same item - check if we can stack
        if (existingItem.isSimilar(itemToPlace)) {
            int maxStackSize = getBrewingStandSlotMaxAmount(slot, itemToPlace.getType());
            return existingItem.getAmount() < maxStackSize;
        }
        
        // Slot has different item - can't place
        return false;
    }

    /**
     * Get the next item to export using round-robin
     */
    private String getNextItemToExport(ExporterData exporter) {
        if (exporter.filterItems.isEmpty()) {
            return null;
        }

        int currentIndex = exporterCycleIndex.get(exporter.exporterId);

        // Try each filter item once, starting from current index
        for (int i = 0; i < exporter.filterItems.size(); i++) {
            int checkIndex = (currentIndex + i) % exporter.filterItems.size();
            String itemHash = exporter.filterItems.get(checkIndex);

            // Check if this item is available in the network
            try {
                if (isItemAvailableInNetwork(exporter.networkId, itemHash)) {
                    // Update the cycle index for next time
                    exporterCycleIndex.put(exporter.exporterId, (checkIndex + 1) % exporter.filterItems.size());
                    return itemHash;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error checking item availability: " + e.getMessage());
            }
        }

        // No items available, increment index anyway for next cycle
        exporterCycleIndex.put(exporter.exporterId, (currentIndex + 1) % exporter.filterItems.size());
        return null;
    }

    /**
     * Check if an item is available in the network
     */
    private boolean isItemAvailableInNetwork(String networkId, String itemHash) throws Exception {
        List<StoredItem> networkItems = plugin.getStorageManager().getNetworkItems(networkId);
        for (StoredItem item : networkItems) {
            if (item.itemHash().equals(itemHash) && item.quantity() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Export specific item with slot-specific routing for furnaces
     */
    private void exportItemWithSlotRouting(ExporterData exporter, String itemHash, Container targetContainer) {
        try {
            // Check container type for specialized routing
            Material containerType = targetContainer.getBlock().getType();
            boolean isFurnace = containerType == Material.FURNACE || 
                               containerType == Material.BLAST_FURNACE || 
                               containerType == Material.SMOKER;
            boolean isBrewingStand = containerType == Material.BREWING_STAND;

            if (isFurnace) {
                // Handle furnace slot routing
                exportItemToFurnace(exporter, itemHash, targetContainer);
            } else if (isBrewingStand) {
                // Handle brewing stand slot routing
                plugin.getLogger().info("DEBUG: Detected brewing stand, calling exportItemToBrewingStand for exporter " + exporter.exporterId);
                exportItemToBrewingStand(exporter, itemHash, targetContainer);
            } else {
                // Use generic export for other containers
                exportItem(exporter, itemHash, targetContainer.getInventory());
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error in slot routing export: " + e.getMessage());
        }
    }

    /**
     * Export item to specific furnace slots based on filter slot targeting
     */
    private void exportItemToFurnace(ExporterData exporter, String itemHash, Container furnaceContainer) {
        try {
            // Get slot targeting information for this item
            String slotTarget = getSlotTargetForItem(exporter.exporterId, itemHash);
            
            // Retrieve up to one stack from the network
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);
            
            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // Nothing retrieved
            }

            Inventory furnaceInventory = furnaceContainer.getInventory();
            int targetSlot;
            String slotName;

            // Determine target slot based on filter type
            if ("fuel".equals(slotTarget)) {
                targetSlot = 1; // Fuel slot (bottom)
                slotName = "fuel";
            } else {
                targetSlot = 0; // Input slot (top) - default for material and generic
                slotName = "input";
            }

            // Try to add to the specific furnace slot
            int leftoverAmount = addItemToSpecificSlot(furnaceInventory, retrievedItem, targetSlot);

            // Handle leftovers and logging
            handleExportCompletion(exporter, retrievedItem, leftoverAmount, 
                                 slotName + " slot");

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting to furnace: " + e.getMessage());
        }
    }

    /**
     * Export to brewing stand with integrated slot selection and export
     */
    private void exportBrewingStandWithSlotSelection(ExporterData exporter, Container brewingStandContainer) {
        try {
            plugin.getLogger().info("DEBUG: Starting brewing stand export with slot selection for exporter " + exporter.exporterId);
            
            // Parse brewing stand filters from exporter filter items
            BrewingStandFilters brewingFilters = parseBrewingStandFilters(exporter.exporterId);
            plugin.getLogger().info("DEBUG: Parsed brewing filters - fuel enabled: " + brewingFilters.fuelEnabled + 
                                  ", ingredient: " + (brewingFilters.ingredientFilter != null ? brewingFilters.ingredientFilter.getType() : "null") +
                                  ", bottles: " + java.util.Arrays.toString(brewingFilters.bottleFilters));
            
            Inventory brewingInventory = brewingStandContainer.getInventory();
            
            // Create a list of all possible exports with their priority
            List<PotentialExport> potentialExports = new ArrayList<>();
            
            // Check blaze powder (fuel) - only if fuel slot has space
            if (brewingFilters.fuelEnabled) {
                ItemStack blazePowder = new ItemStack(Material.BLAZE_POWDER);
                String blazeHash = plugin.getItemManager().generateItemHash(blazePowder);
                if (isItemAvailableInNetwork(exporter.networkId, blazeHash) && 
                    canPlaceItemInBrewingSlot(brewingInventory, blazePowder, 4)) {
                    potentialExports.add(new PotentialExport(blazeHash, 4, "fuel"));
                }
            }
            
            // Check ingredient filter - only if ingredient slot has space
            if (brewingFilters.ingredientFilter != null) {
                String ingredientHash = plugin.getItemManager().generateItemHash(brewingFilters.ingredientFilter);
                if (isItemAvailableInNetwork(exporter.networkId, ingredientHash) && 
                    canPlaceItemInBrewingSlot(brewingInventory, brewingFilters.ingredientFilter, 3)) {
                    potentialExports.add(new PotentialExport(ingredientHash, 3, "ingredient"));
                }
            }
            
            // Check each bottle filter independently - only if their specific slots have space
            for (int i = 0; i < 3; i++) {
                if (brewingFilters.bottleFilters[i] != null) {
                    String bottleHash = plugin.getItemManager().generateItemHash(brewingFilters.bottleFilters[i]);
                    if (isItemAvailableInNetwork(exporter.networkId, bottleHash) && 
                        canPlaceItemInBrewingSlot(brewingInventory, brewingFilters.bottleFilters[i], i)) {
                        potentialExports.add(new PotentialExport(bottleHash, i, "bottle " + (i + 1)));
                    }
                }
            }
            
            // If we have potential exports, use round-robin to cycle through them
            if (!potentialExports.isEmpty()) {
                // Get current cycle index for this exporter (reuse existing mechanism)
                int currentIndex = exporterCycleIndex.get(exporter.exporterId);
                PotentialExport selectedExport = potentialExports.get(currentIndex % potentialExports.size());
                
                // Update cycle index for next time
                exporterCycleIndex.put(exporter.exporterId, (currentIndex + 1) % potentialExports.size());
                
                plugin.getLogger().info("DEBUG: Selected " + selectedExport.slotName + " (slot " + selectedExport.targetSlot + ") for export");
                
                // Now perform the actual export to the specific slot
                exportItemToSpecificBrewingSlot(exporter, selectedExport.itemHash, selectedExport.targetSlot, selectedExport.slotName, brewingStandContainer);
            } else {
                plugin.getLogger().info("DEBUG: No brewing stand items available for export (either not in network or slots full)");
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error in brewing stand slot selection: " + e.getMessage());
        }
    }

    /**
     * Export item to a specific brewing stand slot (knows the exact slot to target)
     */
    private void exportItemToSpecificBrewingSlot(ExporterData exporter, String itemHash, int targetSlot, String slotName, Container brewingStandContainer) {
        try {
            plugin.getLogger().info("DEBUG: Exporting to specific brewing stand slot " + targetSlot + " (" + slotName + ")");
            
            // Retrieve item from network first
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);
            
            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                plugin.getLogger().info("DEBUG: No items retrieved for hash " + itemHash);
                return;
            }

            plugin.getLogger().info("DEBUG: Retrieved item: " + retrievedItem.getType() + " (amount: " + retrievedItem.getAmount() + ") for slot " + targetSlot);

            // Limit item amount to slot-appropriate stack size
            int maxAmount = getBrewingStandSlotMaxAmount(targetSlot, retrievedItem.getType());
            if (retrievedItem.getAmount() > maxAmount) {
                // Split the stack - keep what we can use, return the rest
                ItemStack excess = retrievedItem.clone();
                excess.setAmount(retrievedItem.getAmount() - maxAmount);
                retrievedItem.setAmount(maxAmount);
                
                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(excess);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);
            }

            // Try to place in the target slot
            Inventory brewingInventory = brewingStandContainer.getInventory();
            ItemStack existingItem = brewingInventory.getItem(targetSlot);
            
            int leftoverAmount = 0;
            if (existingItem == null || existingItem.getType() == Material.AIR) {
                // Slot is empty, place the item
                brewingInventory.setItem(targetSlot, retrievedItem);
                plugin.getLogger().info("DEBUG: Placed " + retrievedItem.getAmount() + " " + retrievedItem.getType() + " in empty slot " + targetSlot);
            } else if (existingItem.isSimilar(retrievedItem)) {
                // Slot has same item, try to stack
                int spaceAvailable = existingItem.getMaxStackSize() - existingItem.getAmount();
                int amountToAdd = Math.min(spaceAvailable, retrievedItem.getAmount());
                
                if (amountToAdd > 0) {
                    existingItem.setAmount(existingItem.getAmount() + amountToAdd);
                    leftoverAmount = retrievedItem.getAmount() - amountToAdd;
                    plugin.getLogger().info("DEBUG: Added " + amountToAdd + " " + retrievedItem.getType() + " to existing stack in slot " + targetSlot + " (leftover: " + leftoverAmount + ")");
                } else {
                    leftoverAmount = retrievedItem.getAmount(); // No space
                    plugin.getLogger().info("DEBUG: No space in slot " + targetSlot + ", returning " + leftoverAmount + " items to network");
                }
            } else {
                // Slot has different item, can't place
                leftoverAmount = retrievedItem.getAmount();
                plugin.getLogger().info("DEBUG: Slot " + targetSlot + " has different item, returning " + leftoverAmount + " items to network");
            }

            // Handle completion and leftovers
            handleExportCompletion(exporter, retrievedItem, leftoverAmount, "brewing stand " + slotName + " slot");

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting to specific brewing stand slot: " + e.getMessage());
        }
    }

    /**
     * Export item to specific brewing stand slots based on brewing stand filter settings
     */
    private void exportItemToBrewingStand(ExporterData exporter, String itemHash, Container brewingStandContainer) {
        try {
            plugin.getLogger().info("DEBUG: Starting brewing stand export for exporter " + exporter.exporterId + " with item hash " + itemHash);
            
            // Parse brewing stand filters from exporter filter items
            BrewingStandFilters brewingFilters = parseBrewingStandFilters(exporter.exporterId);
            
            plugin.getLogger().info("DEBUG: Parsed filters - Fuel enabled: " + brewingFilters.fuelEnabled + 
                                  ", Ingredient filter: " + (brewingFilters.ingredientFilter != null ? brewingFilters.ingredientFilter.getType() : "null") +
                                  ", Bottle filters: " + java.util.Arrays.toString(brewingFilters.bottleFilters));
            
            // Retrieve item from network first (similar to furnace approach)
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);
            
            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                plugin.getLogger().info("DEBUG: No items retrieved for hash " + itemHash);
                return; // Nothing retrieved
            }

            Material itemType = retrievedItem.getType();
            int targetSlot = -1;
            String slotName = "unknown";
            
            plugin.getLogger().info("DEBUG: Retrieved item: " + itemType + " (amount: " + retrievedItem.getAmount() + ")");

            // Determine target slot based on item type and filters
            if (itemType == Material.BLAZE_POWDER && brewingFilters.fuelEnabled) {
                targetSlot = 4; // Fuel slot
                slotName = "fuel";
                plugin.getLogger().info("DEBUG: Targeting fuel slot (4) for blaze powder");
            } else if (brewingFilters.ingredientFilter != null && 
                      itemType == brewingFilters.ingredientFilter.getType()) {
                targetSlot = 3; // Ingredient slot
                slotName = "ingredient";
                plugin.getLogger().info("DEBUG: Targeting ingredient slot (3) for " + itemType);
            } else {
                // Check bottle filters (slots 0, 1, 2)
                for (int i = 0; i < 3; i++) {
                    if (brewingFilters.bottleFilters[i] != null && 
                        itemType == brewingFilters.bottleFilters[i].getType()) {
                        targetSlot = i;
                        slotName = "bottle " + (i + 1);
                        plugin.getLogger().info("DEBUG: Targeting bottle slot " + i + " for " + itemType);
                        break;
                    }
                }
            }

            if (targetSlot == -1) {
                // No valid target slot - return items to network
                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(retrievedItem);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);
                return;
            }

            // Limit item amount to slot-appropriate stack size
            int maxAmount = getBrewingStandSlotMaxAmount(targetSlot, itemType);
            if (retrievedItem.getAmount() > maxAmount) {
                // Split the stack - keep what we can use, return the rest
                ItemStack excess = retrievedItem.clone();
                excess.setAmount(retrievedItem.getAmount() - maxAmount);
                retrievedItem.setAmount(maxAmount);
                
                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(excess);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);
            }
            
            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // No items available
            }

            // Try to place in the target slot
            Inventory brewingInventory = brewingStandContainer.getInventory();
            ItemStack existingItem = brewingInventory.getItem(targetSlot);
            
            int leftoverAmount = 0;
            if (existingItem == null || existingItem.getType() == Material.AIR) {
                // Slot is empty, place the item
                brewingInventory.setItem(targetSlot, retrievedItem);
            } else if (existingItem.isSimilar(retrievedItem)) {
                // Slot has same item, try to stack
                int spaceAvailable = existingItem.getMaxStackSize() - existingItem.getAmount();
                int amountToAdd = Math.min(spaceAvailable, retrievedItem.getAmount());
                
                if (amountToAdd > 0) {
                    existingItem.setAmount(existingItem.getAmount() + amountToAdd);
                    leftoverAmount = retrievedItem.getAmount() - amountToAdd;
                } else {
                    leftoverAmount = retrievedItem.getAmount(); // No space
                }
            } else {
                // Slot has different item, can't place
                leftoverAmount = retrievedItem.getAmount();
            }

            // Handle completion and leftovers
            handleExportCompletion(exporter, retrievedItem, leftoverAmount, 
                                 "brewing stand " + slotName + " slot");

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting to brewing stand: " + e.getMessage());
        }
    }

    /**
     * Get maximum stack size for a brewing stand slot
     */
    private int getBrewingStandSlotMaxAmount(int slot, Material itemType) {
        if (slot == 4) {
            // Fuel slot - blaze powder stacks to 64
            return Math.min(64, itemType.getMaxStackSize());
        } else if (slot == 3) {
            // Ingredient slot - most ingredients stack to 64
            return Math.min(64, itemType.getMaxStackSize());
        } else {
            // Bottle slots (0, 1, 2) - bottles typically stack to 16, potions to 1
            return Math.min(itemType.getMaxStackSize(), itemType.getMaxStackSize());
        }
    }

    /**
     * Parse brewing stand specific filters from the exporter's filter items
     */
    private BrewingStandFilters parseBrewingStandFilters(String exporterId) {
        BrewingStandFilters filters = new BrewingStandFilters();
        
        try {
            List<ItemStack> filterItems = getExporterFilterItems(exporterId);
            plugin.getLogger().info("DEBUG: Found " + filterItems.size() + " filter items for exporter " + exporterId);
            
            for (ItemStack item : filterItems) {
                plugin.getLogger().info("DEBUG: Processing filter item: " + item.getType() + " with meta: " + (item.hasItemMeta() ? "yes" : "no"));
                
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    Component displayName = item.getItemMeta().displayName();
                    if (displayName == null) continue;
                    
                    // Use PlainTextComponentSerializer for reliable text extraction
                    String nameText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName);
                    plugin.getLogger().info("DEBUG: Filter item display name: '" + nameText + "'");
                    
                    if ("BREWING_FUEL_ENABLED".equals(nameText)) {
                        filters.fuelEnabled = true;
                        plugin.getLogger().info("DEBUG: Found fuel enabled marker");
                    } else if ("BREWING_INGREDIENT".equals(nameText)) {
                        filters.ingredientFilter = item.clone();
                        // Remove the marker name to get the actual item
                        ItemMeta meta = filters.ingredientFilter.getItemMeta();
                        meta.displayName(null);
                        filters.ingredientFilter.setItemMeta(meta);
                        plugin.getLogger().info("DEBUG: Found ingredient filter: " + filters.ingredientFilter.getType());
                    } else if (nameText.startsWith("BREWING_BOTTLE_")) {
                        try {
                            int bottleIndex = Integer.parseInt(nameText.substring("BREWING_BOTTLE_".length()));
                            if (bottleIndex >= 0 && bottleIndex < 3) {
                                filters.bottleFilters[bottleIndex] = item.clone();
                                // Remove the marker name to get the actual item
                                ItemMeta meta = filters.bottleFilters[bottleIndex].getItemMeta();
                                meta.displayName(null);
                                filters.bottleFilters[bottleIndex].setItemMeta(meta);
                                plugin.getLogger().info("DEBUG: Found bottle filter " + bottleIndex + ": " + filters.bottleFilters[bottleIndex].getType());
                            }
                        } catch (NumberFormatException e) {
                            // Ignore invalid bottle indices
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error parsing brewing stand filters for " + exporterId + ": " + e.getMessage());
        }
        
        return filters;
    }

    /**
     * Data class for brewing stand filter settings
     */
    private static class BrewingStandFilters {
        boolean fuelEnabled = false;
        ItemStack ingredientFilter = null;
        ItemStack[] bottleFilters = new ItemStack[3];
    }

    /**
     * Helper class for potential brewing stand exports
     */
    private static class PotentialExport {
        final String itemHash;
        final int targetSlot;
        final String slotName;

        PotentialExport(String itemHash, int targetSlot, String slotName) {
            this.itemHash = itemHash;
            this.targetSlot = targetSlot;
            this.slotName = slotName;
        }
    }

    /**
     * Get the slot target for a specific item from the database
     */
    private String getSlotTargetForItem(String exporterId, String itemHash) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT slot_target FROM exporter_filters WHERE exporter_id = ? AND item_hash = ? LIMIT 1")) {

            stmt.setString(1, exporterId);
            stmt.setString(2, itemHash);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String slotTarget = rs.getString("slot_target");
                    return slotTarget != null ? slotTarget : "generic";
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting slot target for item: " + e.getMessage());
        }
        
        return "generic"; // Default fallback
    }

    /**
     * Add item to a specific inventory slot (for furnace slot targeting)
     */
    private int addItemToSpecificSlot(Inventory inventory, ItemStack itemToAdd, int targetSlot) {
        if (targetSlot < 0 || targetSlot >= inventory.getSize()) {
            return itemToAdd.getAmount(); // Invalid slot, return all as leftover
        }

        ItemStack slotItem = inventory.getItem(targetSlot);
        
        if (slotItem == null || slotItem.getType() == Material.AIR) {
            // Slot is empty, place the entire stack
            inventory.setItem(targetSlot, itemToAdd.clone());
            return 0; // No leftovers
        } else if (slotItem.isSimilar(itemToAdd)) {
            // Slot has similar item, try to merge
            int maxStackSize = slotItem.getMaxStackSize();
            int currentAmount = slotItem.getAmount();
            int spaceAvailable = maxStackSize - currentAmount;
            
            if (spaceAvailable > 0) {
                int amountToAdd = Math.min(spaceAvailable, itemToAdd.getAmount());
                slotItem.setAmount(currentAmount + amountToAdd);
                inventory.setItem(targetSlot, slotItem);
                return itemToAdd.getAmount() - amountToAdd;
            }
        }
        
        // Slot is full or has different item
        return itemToAdd.getAmount();
    }

    /**
     * Handle export completion (leftovers, logging, etc.)
     */
    private void handleExportCompletion(ExporterData exporter, ItemStack retrievedItem, 
                                       int leftoverAmount, String destination) {
        try {
            // If there's leftover, put it back in the network
            if (leftoverAmount > 0) {
                ItemStack leftoverStack = retrievedItem.clone();
                leftoverStack.setAmount(leftoverAmount);

                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(leftoverStack);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);

                // Calculate what was actually exported
                int exported = retrievedItem.getAmount() - leftoverAmount;
                if (exported > 0) {
                    exporter.lastExport = System.currentTimeMillis();
                    updateLastExport(exporter.exporterId);
                    plugin.getLogger().info("Exporter " + exporter.exporterId + " exported " + exported + 
                                          " " + retrievedItem.getType() + " to " + destination);
                }
            } else {
                // Everything was exported successfully
                exporter.lastExport = System.currentTimeMillis();
                updateLastExport(exporter.exporterId);
                plugin.getLogger().info("Exporter " + exporter.exporterId + " exported " + 
                                      retrievedItem.getAmount() + " " + retrievedItem.getType() + " to " + destination);
            }

            // Refresh any open terminals
            plugin.getGUIManager().refreshNetworkTerminals(exporter.networkId);

        } catch (Exception e) {
            plugin.getLogger().severe("Error handling export completion: " + e.getMessage());
        }
    }

    /**
     * FIXED: Export an item to the target inventory with stack optimization (legacy method for non-furnaces)
     */
    private void exportItem(ExporterData exporter, String itemHash, Inventory targetInventory) {
        try {
            // Retrieve up to one stack from the network
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);

            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // Nothing retrieved
            }

            // Try to add to target inventory with stack optimization
            int leftoverAmount = addItemWithStackOptimization(targetInventory, retrievedItem);

            // If there's leftover, put it back in the network
            if (leftoverAmount > 0) {
                ItemStack leftoverStack = retrievedItem.clone();
                leftoverStack.setAmount(leftoverAmount);

                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(leftoverStack);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);

                // Calculate what was actually exported
                int exported = retrievedItem.getAmount() - leftoverAmount;
                if (exported > 0) {
                    exporter.lastExport = System.currentTimeMillis();
                    updateLastExport(exporter.exporterId);
                    plugin.getLogger().info("Exporter " + exporter.exporterId + " exported " + exported + " " + retrievedItem.getType());
                }
            } else {
                // Everything was exported successfully
                exporter.lastExport = System.currentTimeMillis();
                updateLastExport(exporter.exporterId);
                plugin.getLogger().info("Exporter " + exporter.exporterId + " exported " + retrievedItem.getAmount() + " " + retrievedItem.getType());
            }

            // Refresh any open terminals
            plugin.getGUIManager().refreshNetworkTerminals(exporter.networkId);

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting item: " + e.getMessage());
        }
    }

    /**
     * Get the target container that the exporter is physically attached to
     */
    private Container getTargetContainer(Block exporterBlock) {
        try {
            Block attachedBlock = null;

            plugin.getLogger().info("DEBUG: Checking target for exporter at " + exporterBlock.getLocation() + 
                                  " of type " + exporterBlock.getType());

            if (exporterBlock.getType() == Material.PLAYER_HEAD) {
                // Floor mounted head - check block below
                attachedBlock = exporterBlock.getRelative(BlockFace.DOWN);
                plugin.getLogger().info("DEBUG: Floor mounted head, checking block below: " + 
                                      attachedBlock.getType() + " at " + attachedBlock.getLocation());
            } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
                // Wall mounted head - check block it's attached to
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) exporterBlock.getBlockData();
                BlockFace facing = directional.getFacing();
                // The block the wall head is attached to is in the opposite direction
                attachedBlock = exporterBlock.getRelative(facing.getOppositeFace());
                plugin.getLogger().info("DEBUG: Wall mounted head facing " + facing + 
                                      ", checking attached block: " + attachedBlock.getType() + 
                                      " at " + attachedBlock.getLocation());
            }

            // Check if the attached block is a container
            if (attachedBlock != null) {
                boolean isContainer = attachedBlock.getState() instanceof Container;
                plugin.getLogger().info("DEBUG: Attached block " + attachedBlock.getType() + 
                                      " is container: " + isContainer);
                
                if (isContainer) {
                    Container container = (Container) attachedBlock.getState();
                    plugin.getLogger().info("DEBUG: Returning container: " + container.getClass().getSimpleName());
                    return container;
                } else {
                    plugin.getLogger().info("DEBUG: Attached block is not a container, returning null");
                }
            } else {
                plugin.getLogger().warning("DEBUG: Could not determine attached block!");
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking attached block for exporter: " + e.getMessage());
            plugin.getLogger().warning("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        }

        plugin.getLogger().info("DEBUG: Returning null - no valid target container");
        return null;
    }

    /**
     * Create a new exporter at the given location
     */
    public String createExporter(Location location, String networkId) throws SQLException {
        String exporterId = generateExporterId();

        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO exporters (exporter_id, network_id, world_name, x, y, z, enabled) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, exporterId);
                stmt.setString(2, networkId);
                stmt.setString(3, location.getWorld().getName());
                stmt.setInt(4, location.getBlockX());
                stmt.setInt(5, location.getBlockY());
                stmt.setInt(6, location.getBlockZ());
                stmt.setBoolean(7, false); // Start disabled (no filters)
                stmt.executeUpdate();
            }
        });

        // Add to active exporters
        ExporterData data = new ExporterData(exporterId, networkId, location, false);
        activeExporters.put(exporterId, data);
        exporterCycleIndex.put(exporterId, 0);

        plugin.getLogger().info("Created exporter " + exporterId + " at " + location);
        return exporterId;
    }

    /**
     * Remove an exporter
     */
    public void removeExporter(String exporterId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Delete exporter (filters cascade delete)
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM exporters WHERE exporter_id = ?")) {
                stmt.setString(1, exporterId);
                stmt.executeUpdate();
            }
        });

        activeExporters.remove(exporterId);
        exporterCycleIndex.remove(exporterId);
        plugin.getLogger().info("Removed exporter " + exporterId);
    }

    /**
     * Get exporter at location
     */
    public ExporterData getExporterAtLocation(Location location) {
        for (ExporterData data : activeExporters.values()) {
            if (data.location.equals(location)) {
                return data;
            }
        }
        return null;
    }

    /**
     * Toggle exporter enabled state
     */
    public void toggleExporter(String exporterId, boolean enabled) throws SQLException {
        ExporterData data = activeExporters.get(exporterId);
        if (data != null) {
            data.enabled = enabled;

            plugin.getDatabaseManager().executeUpdate(
                    "UPDATE exporters SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                    enabled, exporterId);

            plugin.getLogger().info("Exporter " + exporterId + " set to " + (enabled ? "enabled" : "disabled"));
        }
    }

    /**
     * Update slot-specific filters for a furnace exporter
     */
    public void updateFurnaceExporterFilters(String exporterId, List<ItemStack> fuelItems, List<ItemStack> materialItems) throws SQLException {
        ExporterData data = activeExporters.get(exporterId);
        if (data == null) return;

        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Clear existing filters
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM exporter_filters WHERE exporter_id = ?")) {
                stmt.setString(1, exporterId);
                stmt.executeUpdate();
            }

            // Add fuel filters
            if (!fuelItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, item_data, filter_type, slot_target) VALUES (?, ?, ?, 'whitelist', 'fuel')")) {

                    for (ItemStack item : fuelItems) {
                        ItemStack template = item.clone();
                        template.setAmount(1);

                        String itemHash = plugin.getItemManager().generateItemHash(template);
                        String itemData = plugin.getStorageManager().serializeItemStack(template);

                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }

            // Add material filters
            if (!materialItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, item_data, filter_type, slot_target) VALUES (?, ?, ?, 'whitelist', 'material')")) {

                    for (ItemStack item : materialItems) {
                        ItemStack template = item.clone();
                        template.setAmount(1);

                        String itemHash = plugin.getItemManager().generateItemHash(template);
                        String itemData = plugin.getStorageManager().serializeItemStack(template);

                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        });

        // Update in-memory data with hashes from single-item templates
        data.filterItems.clear();
        for (ItemStack item : fuelItems) {
            ItemStack template = item.clone();
            template.setAmount(1);
            String itemHash = plugin.getItemManager().generateItemHash(template);
            data.filterItems.add(itemHash);
        }
        for (ItemStack item : materialItems) {
            ItemStack template = item.clone();
            template.setAmount(1);
            String itemHash = plugin.getItemManager().generateItemHash(template);
            data.filterItems.add(itemHash);
        }

        plugin.getLogger().info("Updated furnace filters for exporter " + exporterId + ": " + 
                               fuelItems.size() + " fuel items, " + materialItems.size() + " material items");
    }

    /**
     * Get slot-specific filter items for a furnace exporter
     */
    public Map<String, List<ItemStack>> getFurnaceExporterFilterItems(String exporterId) {
        Map<String, List<ItemStack>> filters = new HashMap<>();
        filters.put("fuel", new ArrayList<>());
        filters.put("material", new ArrayList<>());

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT item_data, slot_target FROM exporter_filters WHERE exporter_id = ? AND item_data IS NOT NULL")) {

            stmt.setString(1, exporterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemData = rs.getString("item_data");
                    String slotTarget = rs.getString("slot_target");
                    
                    ItemStack item = plugin.getStorageManager().deserializeItemStack(itemData);
                    if (item != null) {
                        item.setAmount(1);
                        
                        if ("fuel".equals(slotTarget)) {
                            filters.get("fuel").add(item);
                        } else if ("material".equals(slotTarget)) {
                            filters.get("material").add(item);
                        } else {
                            // Backward compatibility - treat generic/null as material
                            filters.get("material").add(item);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading furnace exporter filters: " + e.getMessage());
        }

        plugin.getLogger().info("Loaded furnace filters for exporter " + exporterId + ": " + 
                               filters.get("fuel").size() + " fuel, " + filters.get("material").size() + " material");
        return filters;
    }

    /**
     * Update filter for an exporter - FIXED to store actual item data as single-item templates
     */
    public void updateExporterFilter(String exporterId, List<ItemStack> filterItems) throws SQLException {
        ExporterData data = activeExporters.get(exporterId);
        if (data == null) return;

        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Clear existing filters
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM exporter_filters WHERE exporter_id = ?")) {
                stmt.setString(1, exporterId);
                stmt.executeUpdate();
            }

            // Add new filters with both hashes and item data
            if (!filterItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, item_data, filter_type) VALUES (?, ?, ?, 'whitelist')")) {

                    for (ItemStack item : filterItems) {
                        // Ensure we store single-item templates
                        ItemStack template = item.clone();
                        template.setAmount(1);

                        String itemHash = plugin.getItemManager().generateItemHash(template);
                        String itemData = plugin.getStorageManager().serializeItemStack(template); // Store actual item data

                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        });

        // Update in-memory data with hashes from single-item templates
        data.filterItems.clear();
        for (ItemStack item : filterItems) {
            ItemStack template = item.clone();
            template.setAmount(1);
            String itemHash = plugin.getItemManager().generateItemHash(template);
            data.filterItems.add(itemHash);
        }

        plugin.getLogger().info("Updated filters for exporter " + exporterId + ": " + filterItems.size() + " items");
    }

    /**
     * Get filter items for an exporter - FIXED to return actual ItemStacks as single-item templates
     */
    public List<ItemStack> getExporterFilterItems(String exporterId) {
        List<ItemStack> items = new ArrayList<>();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT item_data FROM exporter_filters WHERE exporter_id = ? AND item_data IS NOT NULL")) {

            stmt.setString(1, exporterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemData = rs.getString("item_data");
                    ItemStack item = plugin.getStorageManager().deserializeItemStack(itemData);
                    if (item != null) {
                        // Ensure it's a single-item template
                        item.setAmount(1);
                        items.add(item);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading exporter filters: " + e.getMessage());
        }

        plugin.getLogger().info("Loaded " + items.size() + " filter items for exporter " + exporterId);
        return items;
    }

    /**
     * Generate a unique exporter ID
     */
    private String generateExporterId() {
        return "EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * ADDED: Handle network invalidation - disconnect exporters from invalid networks
     * This should be called when a network is unregistered/dissolved
     */
    public void handleNetworkInvalidated(String networkId) {
        plugin.getLogger().info("Handling exporter disconnections for invalidated network: " + networkId);

        int disconnectedCount = 0;
        for (ExporterData exporter : activeExporters.values()) {
            if (networkId.equals(exporter.networkId)) {
                try {
                    // Mark exporter as disconnected in database
                    plugin.getDatabaseManager().executeUpdate(
                            "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                            "UNCONNECTED", exporter.exporterId);

                    // Update in memory - create new ExporterData with UNCONNECTED status
                    activeExporters.remove(exporter.exporterId);
                    ExporterData disconnectedData = new ExporterData(exporter.exporterId, "UNCONNECTED", exporter.location, false); // Disable when disconnected
                    disconnectedData.filterItems.addAll(exporter.filterItems); // Preserve filters
                    activeExporters.put(exporter.exporterId, disconnectedData);

                    plugin.getLogger().info("Disconnected exporter " + exporter.exporterId + " from invalidated network " + networkId);
                    disconnectedCount++;

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to disconnect exporter " + exporter.exporterId + ": " + e.getMessage());
                }
            }
        }

        if (disconnectedCount > 0) {
            plugin.getLogger().info("Disconnected " + disconnectedCount + " exporters from invalidated network " + networkId);
        }
    }

    /**
     * ADDED: Update exporter network assignments when networks change
     * This should be called after network detection/updates
     */
    public void updateExporterNetworkAssignments() {
        int reconnectedCount = 0;
        int disconnectedCount = 0;
        int autoDisabledCount = 0;

        for (ExporterData exporter : activeExporters.values()) {
            // Check if current network is valid
            boolean currentNetworkValid = exporter.networkId != null &&
                    !"UNCONNECTED".equals(exporter.networkId) &&
                    plugin.getNetworkManager().isNetworkValid(exporter.networkId);

            if (!currentNetworkValid) {
                // Exporter is unconnected or has invalid network - try to find a new network
                String newNetworkId = findAdjacentNetwork(exporter.location);

                if (newNetworkId != null && !newNetworkId.equals(exporter.networkId)) {
                    // Found a new valid network - reconnect
                    try {
                        plugin.getDatabaseManager().executeUpdate(
                                "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                                newNetworkId, exporter.exporterId);

                        // Update in memory
                        activeExporters.remove(exporter.exporterId);
                        ExporterData updatedData = new ExporterData(exporter.exporterId, newNetworkId, exporter.location, exporter.enabled);
                        updatedData.filterItems.addAll(exporter.filterItems);
                        activeExporters.put(exporter.exporterId, updatedData);

                        plugin.getLogger().info("Reconnected exporter " + exporter.exporterId + " to network " + newNetworkId);
                        reconnectedCount++;

                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to reconnect exporter " + exporter.exporterId + ": " + e.getMessage());
                    }
                } else if (!"UNCONNECTED".equals(exporter.networkId)) {
                    // No valid network found and not already marked as unconnected - disconnect
                    try {
                        plugin.getDatabaseManager().executeUpdate(
                                "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                                "UNCONNECTED", exporter.exporterId);

                        // Update in memory
                        activeExporters.remove(exporter.exporterId);
                        ExporterData disconnectedData = new ExporterData(exporter.exporterId, "UNCONNECTED", exporter.location, false);
                        disconnectedData.filterItems.addAll(exporter.filterItems);
                        activeExporters.put(exporter.exporterId, disconnectedData);

                        plugin.getLogger().info("Disconnected exporter " + exporter.exporterId + " - no valid network found");
                        disconnectedCount++;

                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to disconnect exporter " + exporter.exporterId + ": " + e.getMessage());
                    }
                }
            } else {
                // Network is valid, but check physical connectivity
                if (exporter.enabled && isExporterConnectedToNetwork(exporter)) {
                    // Auto-disable exporters that are enabled but physically disconnected
                    try {
                        toggleExporter(exporter.exporterId, false);
                        plugin.getLogger().info("Auto-disabled exporter " + exporter.exporterId + " during periodic validation - physically disconnected");
                        refreshExporterGUIs(exporter.exporterId);
                        autoDisabledCount++;
                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to auto-disable exporter " + exporter.exporterId + ": " + e.getMessage());
                    }
                }
            }
        }

        if (reconnectedCount > 0 || disconnectedCount > 0 || autoDisabledCount > 0) {
            plugin.getLogger().info("Exporter network assignment update: " + reconnectedCount + " reconnected, " +
                    disconnectedCount + " disconnected, " + autoDisabledCount + " auto-disabled");
        }
    }

    /**
     * Find a valid network adjacent to the given location
     */
    private String findAdjacentNetwork(Location location) {
        // Check adjacent locations for network blocks or cables
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    // Only check face-adjacent blocks
                    int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                    if (nonZero == 1) {
                        Location adjacent = location.clone().add(dx, dy, dz);
                        String networkId = plugin.getNetworkManager().getNetworkId(adjacent);

                        if (networkId != null && !networkId.startsWith("UNCONNECTED") &&
                                plugin.getNetworkManager().isNetworkValid(networkId)) {
                            return networkId;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Update last export timestamp
     */
    private void updateLastExport(String exporterId) {
        try {
            plugin.getDatabaseManager().executeUpdate(
                    "UPDATE exporters SET last_export = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                    exporterId);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update last export timestamp: " + e.getMessage());
        }
    }


}