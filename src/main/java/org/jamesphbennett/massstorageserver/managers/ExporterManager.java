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

        plugin.getLogger().info("Export task started with " + tickInterval + " tick interval");
    }

    /**
     * Process a single export operation
     */
    private void processExport(ExporterData exporter) {
        try {
            // Check if network is still valid
            if (!plugin.getNetworkManager().isNetworkValid(exporter.networkId)) {
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

            // Check if inventory has space
            if (targetInventory.firstEmpty() == -1) {
                return; // Inventory is full
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
     * Export an item to the target inventory
     */
    private void exportItem(ExporterData exporter, String itemHash, Inventory targetInventory) {
        try {
            // Retrieve up to one stack from the network
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);

            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // Nothing retrieved
            }

            // Try to add to target inventory
            HashMap<Integer, ItemStack> leftover = targetInventory.addItem(retrievedItem);

            // If there's leftover, put it back in the network
            if (!leftover.isEmpty()) {
                for (ItemStack leftoverStack : leftover.values()) {
                    List<ItemStack> toReturn = new ArrayList<>();
                    toReturn.add(leftoverStack);
                    plugin.getStorageManager().storeItems(exporter.networkId, toReturn);
                }

                // Calculate what was actually exported
                int exported = retrievedItem.getAmount() - leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
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

            // Add new filters with both hash and item data
            if (!filterItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, item_data, filter_type) VALUES (?, ?, ?, 'whitelist')")) {
                    for (ItemStack item : filterItems) {
                        String itemHash = plugin.getItemManager().generateItemHash(item);
                        String itemData = serializeItemStack(item);

                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        });

        // Update in memory (store hashes for compatibility with existing export logic)
        data.filterItems.clear();
        for (ItemStack item : filterItems) {
            String itemHash = plugin.getItemManager().generateItemHash(item);
            data.filterItems.add(itemHash);
        }

        // Enable if filters are set, disable if empty
        if (!filterItems.isEmpty() && !data.enabled) {
            toggleExporter(exporterId, true);
        } else if (filterItems.isEmpty() && data.enabled) {
            toggleExporter(exporterId, false);
        }

        plugin.getLogger().info("Updated filters for exporter " + exporterId + " (" + filterItems.size() + " items)");
    }

    /**
     * Get filter items for an exporter with actual ItemStack data
     */
    public List<ItemStack> getExporterFilterItems(String exporterId) {
        List<ItemStack> filterItems = new ArrayList<>();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT item_data FROM exporter_filters WHERE exporter_id = ? AND filter_type = 'whitelist' ORDER BY created_at")) {

            stmt.setString(1, exporterId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemData = rs.getString("item_data");
                    if (itemData != null && !itemData.isEmpty()) {
                        ItemStack item = deserializeItemStack(itemData);
                        if (item != null) {
                            filterItems.add(item);
                        }
                    }
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error loading filter items for exporter " + exporterId + ": " + e.getMessage());
        }

        return filterItems;
    }

    /**
     * Update exporter network assignments when networks change
     * This should be called after network detection/updates
     */
    public void updateExporterNetworkAssignments() {
        for (ExporterData exporter : activeExporters.values()) {
            // Only update unconnected exporters or validate existing connections
            if ("UNCONNECTED".equals(exporter.networkId) || !plugin.getNetworkManager().isNetworkValid(exporter.networkId)) {

                // Check if this exporter is now adjacent to a valid network
                String newNetworkId = findAdjacentNetwork(exporter.location);

                if (newNetworkId != null && !newNetworkId.equals(exporter.networkId)) {
                    // Update the exporter's network assignment
                    try {
                        plugin.getDatabaseManager().executeUpdate(
                                "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                                newNetworkId, exporter.exporterId);

                        // Update in memory
                        activeExporters.remove(exporter.exporterId);
                        ExporterData updatedData = new ExporterData(exporter.exporterId, newNetworkId, exporter.location, exporter.enabled);
                        updatedData.filterItems.addAll(exporter.filterItems);
                        activeExporters.put(exporter.exporterId, updatedData);

                        plugin.getLogger().info("Updated exporter " + exporter.exporterId + " network assignment: " +
                                exporter.networkId + " -> " + newNetworkId);

                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to update exporter network assignment: " + e.getMessage());
                    }
                }
            }
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
            plugin.getLogger().warning("Failed to update last export time: " + e.getMessage());
        }
    }

    /**
     * Generate unique exporter ID
     */
    private String generateExporterId() {
        return "EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    /**
     * Reload all exporters (for plugin reload)
     */
    public void reloadExporters() {
        activeExporters.clear();
        exporterCycleIndex.clear();
        loadExporters();
    }

    /**
     * Serialize ItemStack to Base64 string for database storage
     */
    private String serializeItemStack(ItemStack item) {
        try {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            org.bukkit.util.io.BukkitObjectOutputStream dataOutput = new org.bukkit.util.io.BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return java.util.Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to serialize filter item: " + e.getMessage());
            return "";
        }
    }

    /**
     * Deserialize ItemStack from Base64 string from database
     */
    private ItemStack deserializeItemStack(String data) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(data);
            java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(bytes);
            org.bukkit.util.io.BukkitObjectInputStream dataInput = new org.bukkit.util.io.BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize filter item: " + e.getMessage());
            return null;
        }
    }
}