package org.jamesphbennett.massstorageserver.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            updateExporterNetworkAssignments();
        }, 600L, 600L); // 600 ticks = 30 seconds

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
            return false;
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
                return true; // Found matching network at adjacent location
            }
        }

        return false; // No adjacent network blocks found
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
            // ENHANCED: Check if exporter is physically connected to its network
            if (!isExporterConnectedToNetwork(exporter)) {
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

            // FIXED: Check if inventory has space (including partial stacks)
            if (!hasInventorySpace(targetInventory)) {
                return; // Inventory is completely full
            }

            // Get the next item to export based on round-robin
            String itemHashToExport = getNextItemToExport(exporter);
            if (itemHashToExport == null) {
                return; // No items to export
            }

            // Try to export the item
            exportItem(exporter, itemHashToExport, targetInventory);

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

        // Phase 1: Fill existing partial stacks of the same item type
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

        // Phase 2: Fill empty slots with remaining items
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
            if (item.getItemHash().equals(itemHash) && item.getQuantity() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * FIXED: Export an item to the target inventory with stack optimization
     */
    private void exportItem(ExporterData exporter, String itemHash, Inventory targetInventory) {
        try {
            // Retrieve up to one stack from the network
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);

            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // Nothing retrieved
            }

            // FIXED: Try to add to target inventory with stack optimization
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
     * Get the target container that the exporter is connected to
     */
    private Container getTargetContainer(Block exporterBlock) {
        // Check each valid direction (N, S, E, W, DOWN)
        BlockFace[] validFaces = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN};

        for (BlockFace face : validFaces) {
            Block targetBlock = exporterBlock.getRelative(face);
            if (targetBlock.getState() instanceof Container container) {
                return container;
            }
        }

        return null;
    }

    /**
     * Create a new exporter at the given location
     */
    public String createExporter(Location location, String networkId, Player placer) throws SQLException {
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
     * Update filter for an exporter - now stores actual item data
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

            // Add new filters with hashes
            if (!filterItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, filter_type) VALUES (?, ?, 'whitelist')")) {

                    for (ItemStack item : filterItems) {
                        String itemHash = plugin.getItemManager().generateItemHash(item);
                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        });

        // Update in-memory data with hashes
        data.filterItems.clear();
        for (ItemStack item : filterItems) {
            String itemHash = plugin.getItemManager().generateItemHash(item);
            data.filterItems.add(itemHash);
        }

        plugin.getLogger().info("Updated filters for exporter " + exporterId + ": " + filterItems.size() + " items");
    }

    /**
     * Get filter items for an exporter (returns actual ItemStacks for GUI)
     */
    public List<ItemStack> getExporterFilterItems(String exporterId) {
        List<ItemStack> items = new ArrayList<>();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT item_hash FROM exporter_filters WHERE exporter_id = ? AND filter_type = 'whitelist'")) {

            stmt.setString(1, exporterId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemHash = rs.getString("item_hash");

                    // Try to find a stored item with this hash to get the ItemStack
                    ExporterData exporter = activeExporters.get(exporterId);
                    if (exporter != null) {
                        try {
                            List<StoredItem> networkItems = plugin.getStorageManager().getNetworkItems(exporter.networkId);
                            for (StoredItem storedItem : networkItems) {
                                if (storedItem.getItemHash().equals(itemHash)) {
                                    items.add(storedItem.getItemStack().clone());
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not resolve filter item for hash " + itemHash + ": " + e.getMessage());
                        }
                    }
                }
            }

        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading exporter filter items: " + e.getMessage());
        }

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
                if (exporter.enabled && !isExporterConnectedToNetwork(exporter)) {
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
     * Update network assignments for exporters when networks merge/split
     * (LEGACY METHOD - kept for compatibility)
     */
    public void updateNetworkAssignments() {
        // Call the new method
        updateExporterNetworkAssignments();
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
     * Get filters for an exporter (legacy method - returns hashes for compatibility)
     */
    public List<String> getExporterFilters(String exporterId) {
        ExporterData data = activeExporters.get(exporterId);
        return data != null ? new ArrayList<>(data.filterItems) : new ArrayList<>();
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

    /**
     * Get all active exporters
     */
    public Collection<ExporterData> getActiveExporters() {
        return activeExporters.values();
    }

    /**
     * Get exporter by ID
     */
    public ExporterData getExporter(String exporterId) {
        return activeExporters.get(exporterId);
    }
}