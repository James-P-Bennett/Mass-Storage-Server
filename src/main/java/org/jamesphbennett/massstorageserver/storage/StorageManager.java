package org.jamesphbennett.massstorageserver.storage;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.database.DatabaseManager;
import org.jamesphbennett.massstorageserver.managers.ItemManager;
import org.jamesphbennett.massstorageserver.network.NetworkInfo;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class StorageManager {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;

    public StorageManager(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
    }

    /**
     * Store items in the network
     * @param networkId The network to store items in
     * @param items Items to store
     * @return Items that couldn't be stored (remainder)
     */
    public List<ItemStack> storeItems(String networkId, List<ItemStack> items) throws Exception {
        return plugin.getNetworkManager().withNetworkLock(networkId, () -> {
            List<ItemStack> remainders = new ArrayList<>();

            plugin.getLogger().info("Starting storage operation for " + items.size() + " item stacks in network " + networkId);

            try {
                DatabaseManager.DatabaseTransaction transaction = (Connection conn) -> {
                    // Get all storage disks in the network
                    List<String> diskIds = getNetworkDiskIds(conn, networkId);

                    plugin.getLogger().info("Found " + diskIds.size() + " storage disks in network " + networkId);

                    if (diskIds.isEmpty()) {
                        plugin.getLogger().warning("No storage disks found in network " + networkId);
                        remainders.addAll(items);
                        return;
                    }

                    // Log disk capacities
                    for (String diskId : diskIds) {
                        int availableCells = getAvailableCells(conn, diskId);
                        int maxCells = getMaxCells(conn, diskId);
                        plugin.getLogger().info("Disk " + diskId + ": " + availableCells + "/" + maxCells + " cells available");
                    }

                    for (ItemStack item : items) {
                        if (itemManager.isItemBlacklisted(item)) {
                            plugin.getLogger().warning("Item not allowed: " + item.getType());
                            remainders.add(item);
                            continue;
                        }

                        plugin.getLogger().info("Processing " + item.getAmount() + " " + item.getType());
                        ItemStack remainder = storeItemInNetwork(conn, diskIds, item);
                        if (remainder != null && remainder.getAmount() > 0) {
                            plugin.getLogger().warning("Could not store " + remainder.getAmount() + " " + remainder.getType());
                            remainders.add(remainder);
                        }
                    }

                    // Update disk cell counts
                    updateDiskCellCounts(conn, diskIds);

                    // Log final disk states
                    for (String diskId : diskIds) {
                        int availableCells = getAvailableCells(conn, diskId);
                        int maxCells = getMaxCells(conn, diskId);
                        plugin.getLogger().info("Final disk " + diskId + ": " + availableCells + "/" + maxCells + " cells available");
                    }
                };

                plugin.getDatabaseManager().executeTransaction(transaction);
                plugin.getLogger().info("Storage transaction completed successfully");

            } catch (SQLException e) {
                plugin.getLogger().severe("Storage transaction failed: " + e.getMessage());
                plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
                throw new RuntimeException("Storage operation failed: " + e.getMessage(), e);
            }

            plugin.getLogger().info("Storage operation complete. " + remainders.size() + " item stacks could not be stored");
            return remainders;
        });
    }

    /**
     * Retrieve items from the network
     * @param networkId The network to retrieve from
     * @param itemHash Hash of the item type to retrieve
     * @param amount Amount to retrieve
     * @return The retrieved items, or null if not available
     */
    public ItemStack retrieveItems(String networkId, String itemHash, int amount) throws Exception {
        return plugin.getNetworkManager().withNetworkLock(networkId, () -> {
            ItemStack[] result = new ItemStack[1];

            try {
                DatabaseManager.DatabaseTransaction transaction = (Connection conn) -> {
                    // Find the item in storage - ONLY from disks currently in drive bays
                    // ORDER BY quantity ASC to take from less full cells first
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "SELECT si.id, si.disk_id, si.item_data, si.quantity, si.max_stack_size " +
                                    "FROM storage_items si " +
                                    "JOIN storage_disks sd ON si.disk_id = sd.disk_id " +
                                    "JOIN drive_bay_slots dbs ON sd.disk_id = dbs.disk_id " +
                                    "WHERE dbs.network_id = ? AND si.item_hash = ? AND si.quantity > 0 AND dbs.disk_id IS NOT NULL " +
                                    "ORDER BY si.quantity ASC")) { // CHANGED: Take from less full cells first

                        stmt.setString(1, networkId);
                        stmt.setString(2, itemHash);

                        try (ResultSet rs = stmt.executeQuery()) {
                            int remainingToRetrieve = amount;
                            List<ItemStack> retrievedItems = new ArrayList<>();

                            while (rs.next() && remainingToRetrieve > 0) {
                                int cellId = rs.getInt("id");
                                String diskId = rs.getString("disk_id");
                                String itemData = rs.getString("item_data");
                                int currentQuantity = rs.getInt("quantity");

                                int toRetrieve = Math.min(remainingToRetrieve, currentQuantity);
                                int newQuantity = currentQuantity - toRetrieve;

                                plugin.getLogger().info("Retrieving " + toRetrieve + " from cell " + cellId + " in disk " + diskId +
                                        " (cell had " + currentQuantity + ", will have " + newQuantity + ")");

                                // Update this specific cell
                                try (PreparedStatement updateStmt = conn.prepareStatement(
                                        "UPDATE storage_items SET quantity = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                                    updateStmt.setInt(1, newQuantity);
                                    updateStmt.setInt(2, cellId);
                                    updateStmt.executeUpdate();
                                }

                                // If quantity reaches 0, remove the cell entry
                                if (newQuantity == 0) {
                                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                                            "DELETE FROM storage_items WHERE id = ?")) {
                                        deleteStmt.setInt(1, cellId);
                                        deleteStmt.executeUpdate();
                                    }
                                    plugin.getLogger().info("Removed empty cell " + cellId + " from disk " + diskId);
                                }

                                // Deserialize item and set amount
                                ItemStack item = deserializeItemStack(itemData);
                                if (item != null) {
                                    item.setAmount(toRetrieve);
                                    retrievedItems.add(item);
                                    remainingToRetrieve -= toRetrieve;
                                }
                            }

                            // Combine all retrieved items into one stack
                            if (!retrievedItems.isEmpty()) {
                                ItemStack combinedItem = retrievedItems.getFirst().clone();
                                int totalAmount = retrievedItems.stream().mapToInt(ItemStack::getAmount).sum();
                                combinedItem.setAmount(totalAmount);
                                result[0] = combinedItem;
                                plugin.getLogger().info("Successfully combined " + retrievedItems.size() + " retrievals into " + totalAmount + " items");
                            }
                        }
                    }

                    // Update disk cell counts for disks that are currently in drive bays
                    List<String> diskIds = getNetworkDiskIds(conn, networkId);
                    updateDiskCellCounts(conn, diskIds);
                };

                plugin.getDatabaseManager().executeTransaction(transaction);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            return result[0];
        });
    }

    /**
     * Get all stored items in a network for display in terminal
     * Consolidate same item types from multiple disks into single display slot
     */
    public List<StoredItem> getNetworkItems(String networkId) throws Exception {
        return plugin.getNetworkManager().withNetworkLock(networkId, () -> {
            List<StoredItem> items = new ArrayList<>();

            try {
                // Get currently connected drive bays for this network (real-time detection)
                Set<String> connectedDiskIds = getConnectedDiskIdsForNetwork(networkId);
                
                if (connectedDiskIds.isEmpty()) {
                    plugin.getLogger().info("No connected drive bays found for network " + networkId);
                    return items;
                }

                try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                    // Build a dynamic query based on actually connected disks
                    StringBuilder queryBuilder = getStringBuilder(connectedDiskIds);

                    try (PreparedStatement stmt = conn.prepareStatement(queryBuilder.toString())) {
                        int paramIndex = 1;
                        for (String diskId : connectedDiskIds) {
                            stmt.setString(paramIndex++, diskId);
                        }

                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                String itemHash = rs.getString("item_hash");
                                String itemData = rs.getString("item_data");
                                int quantity = rs.getInt("total_quantity");

                                ItemStack item = deserializeItemStack(itemData);
                                if (item != null) {
                                    items.add(new StoredItem(itemHash, item, quantity));
                                }
                            }
                        }
                    }
                }

                plugin.getLogger().info("Found " + items.size() + " consolidated item types from " + connectedDiskIds.size() + " connected disks in network " + networkId);

            } catch (Exception e) {
                plugin.getLogger().severe("Error getting network items for " + networkId + ": " + e.getMessage());
                throw new RuntimeException(e);
            }

            return items;
        });
    }

    private static @NotNull StringBuilder getStringBuilder(Set<String> connectedDiskIds) {
        StringBuilder queryBuilder = new StringBuilder(
                "SELECT si.item_hash, si.item_data, SUM(si.quantity) as total_quantity " +
                "FROM storage_items si " +
                "JOIN storage_disks sd ON si.disk_id = sd.disk_id " +
                "WHERE sd.disk_id IN (");

        // Add placeholders for disk IDs
        for (int i = 0; i < connectedDiskIds.size(); i++) {
            if (i > 0) queryBuilder.append(", ");
            queryBuilder.append("?");
        }

        queryBuilder.append(") GROUP BY si.item_hash " +
                "HAVING total_quantity > 0 " +
                "ORDER BY total_quantity DESC");
        return queryBuilder;
    }

    /**
     * Get disk IDs that are currently connected to a network by checking actual connectivity
     * This ensures terminals only show items from drive bays that are currently part of the network
     */
    private Set<String> getConnectedDiskIdsForNetwork(String networkId) throws Exception {
        Set<String> connectedDiskIds = new HashSet<>();
        
        try {
            // Get network info to find all currently connected drive bays
            Set<Location> connectedDriveBays = new HashSet<>();
            
            // Find any block in this network to detect the full topology
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT world_name, x, y, z FROM network_blocks WHERE network_id = ? LIMIT 1")) {
                
                stmt.setString(1, networkId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Location testLocation = new Location(
                                plugin.getServer().getWorld(rs.getString("world_name")),
                                rs.getInt("x"),
                                rs.getInt("y"),
                                rs.getInt("z")
                        );
                        
                        // Detect the current network topology from this location
                        NetworkInfo currentNetwork = plugin.getNetworkManager().detectNetwork(testLocation);
                        if (currentNetwork != null && currentNetwork.isValid()) {
                            connectedDriveBays = currentNetwork.getDriveBays();
                        }
                    }
                }
            }
            
            if (connectedDriveBays.isEmpty()) {
                plugin.getLogger().info("No drive bays detected in current network topology for " + networkId);
                return connectedDiskIds;
            }
            
            // Now get disk IDs from these connected drive bay locations
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                for (Location driveBayLocation : connectedDriveBays) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id IS NOT NULL")) {
                        
                        stmt.setString(1, driveBayLocation.getWorld().getName());
                        stmt.setInt(2, driveBayLocation.getBlockX());
                        stmt.setInt(3, driveBayLocation.getBlockY());
                        stmt.setInt(4, driveBayLocation.getBlockZ());
                        
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                String diskId = rs.getString("disk_id");
                                if (diskId != null) {
                                    connectedDiskIds.add(diskId);
                                }
                            }
                        }
                    }
                }
            }
            
            plugin.getLogger().info("Found " + connectedDiskIds.size() + " disks in " + connectedDriveBays.size() + " connected drive bays for network " + networkId);
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error detecting connected disks for network " + networkId + ": " + e.getMessage());
            throw e;
        }
        
        return connectedDiskIds;
    }

    private ItemStack storeItemInNetwork(Connection conn, List<String> diskIds, ItemStack item) throws SQLException {
        String itemHash = itemManager.generateItemHash(item);
        String itemData = serializeItemStack(item);
        int amountToStore = item.getAmount();
        int maxStackSize = item.getMaxStackSize();

        plugin.getLogger().info("Storing " + amountToStore + " " + item.getType() + " (hash: " + itemHash.substring(0, 8) + "...)");

        // PHASE 1: Fill existing partial cells first (most space-efficient)
        for (String diskId : diskIds) {
            if (amountToStore <= 0) break;

            // Get the maximum items per cell for this specific disk
            int MAX_ITEMS_PER_CELL = getDiskMaxItemsPerCell(conn, diskId);
            plugin.getLogger().info("Disk " + diskId + " has capacity of " + MAX_ITEMS_PER_CELL + " items per cell");

            // Get all partial cells for this item type, ordered by quantity DESC (fill fuller cells first)
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, quantity FROM storage_items WHERE disk_id = ? AND item_hash = ? AND quantity < ? ORDER BY quantity DESC")) {
                stmt.setString(1, diskId);
                stmt.setString(2, itemHash);
                stmt.setInt(3, MAX_ITEMS_PER_CELL);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next() && amountToStore > 0) {
                        int cellId = rs.getInt("id");
                        int currentQuantity = rs.getInt("quantity");
                        int canAdd = Math.min(amountToStore, MAX_ITEMS_PER_CELL - currentQuantity);

                        if (canAdd > 0) {
                            // Update this specific cell
                            try (PreparedStatement updateStmt = conn.prepareStatement(
                                    "UPDATE storage_items SET quantity = quantity + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                                updateStmt.setInt(1, canAdd);
                                updateStmt.setInt(2, cellId);
                                updateStmt.executeUpdate();
                            }

                            amountToStore -= canAdd;
                            plugin.getLogger().info("Added " + canAdd + " items to existing cell " + cellId + " in disk " + diskId +
                                    " (now " + (currentQuantity + canAdd) + "/" + MAX_ITEMS_PER_CELL + ")");
                        }
                    }
                }
            }
        }

        // PHASE 2: Create new cells for remaining items
        for (String diskId : diskIds) {
            if (amountToStore <= 0) break;

            // Get the maximum items per cell for this specific disk
            int MAX_ITEMS_PER_CELL = getDiskMaxItemsPerCell(conn, diskId);

            // Check available cells
            int availableCells = getAvailableCells(conn, diskId);
            plugin.getLogger().info("Disk " + diskId + " has " + availableCells + " available cells (capacity: " + MAX_ITEMS_PER_CELL + " per cell)");

            while (availableCells > 0 && amountToStore > 0) {
                int canStore = Math.min(amountToStore, MAX_ITEMS_PER_CELL);

                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO storage_items (disk_id, item_hash, item_data, quantity, max_stack_size) VALUES (?, ?, ?, ?, ?)")) {
                    stmt.setString(1, diskId);
                    stmt.setString(2, itemHash);
                    stmt.setString(3, itemData);
                    stmt.setInt(4, canStore);
                    stmt.setInt(5, maxStackSize);

                    stmt.executeUpdate();

                    amountToStore -= canStore;
                    availableCells--;
                    plugin.getLogger().info("Created new cell in disk " + diskId + " with " + canStore + " items (" + availableCells + " cells remaining)");

                } catch (SQLException e) {
                    plugin.getLogger().severe("Error creating new storage cell in disk " + diskId + ": " + e.getMessage());
                    break; // Stop trying this disk
                }
            }
        }

        // Return remainder if any
        if (amountToStore > 0) {
            plugin.getLogger().warning("Could not store " + amountToStore + " items - network storage full");

            // Log detailed capacity info for debugging
            for (String diskId : diskIds) {
                try {
                    int availableCells = getAvailableCells(conn, diskId);
                    int maxCells = getMaxCells(conn, diskId);
                    int maxItemsPerCell = getDiskMaxItemsPerCell(conn, diskId);
                    plugin.getLogger().info("Disk " + diskId + " final state: " + availableCells + "/" + maxCells +
                            " cells available (" + maxItemsPerCell + " items per cell)");
                } catch (SQLException e) {
                    plugin.getLogger().warning("Error getting final disk state: " + e.getMessage());
                }
            }

            ItemStack remainder = item.clone();
            remainder.setAmount(amountToStore);
            return remainder;
        }

        plugin.getLogger().info("Successfully stored all " + item.getAmount() + " items");
        return null;
    }

    // Helper method to get disk-specific capacity
    private int getDiskMaxItemsPerCell(Connection conn, String diskId) throws SQLException {
        try {
            // Get the tier from the database and return tier-specific capacity
            String tier = getTierFromDatabase(conn, diskId);
            return plugin.getItemManager().getItemsPerCellForTier(tier);
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting tier-specific capacity for disk " + diskId + ": " + e.getMessage());
            return 127; // 1k tier default fallback
        }
    }

    /**
     * Helper method to get tier from database within an existing connection
     */
    private String getTierFromDatabase(Connection conn, String diskId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT tier FROM storage_disks WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String tier = rs.getString("tier");
                    return tier != null ? tier : "1k"; // Default to 1k if null
                }
            }
        }
        return "1k"; // Default fallback
    }

    // Helper method to get available cells (replace the existing hasAvailableCells method)
    private int getAvailableCells(Connection conn, String diskId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT (SELECT max_cells FROM storage_disks WHERE disk_id = ?) - COUNT(*) as available_cells FROM storage_items WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            stmt.setString(2, diskId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("available_cells");
                }
            }
        }
        return 0;
    }

    private List<String> getNetworkDiskIds(Connection conn, String networkId) throws SQLException {
        List<String> diskIds = new ArrayList<>();

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT dbs.disk_id FROM drive_bay_slots dbs WHERE dbs.network_id = ? AND dbs.disk_id IS NOT NULL ORDER BY dbs.slot_number")) {
            stmt.setString(1, networkId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    diskIds.add(rs.getString("disk_id"));
                }
            }
        }

        return diskIds;
    }

    private void updateDiskCellCounts(Connection conn, List<String> diskIds) throws SQLException {
        for (String diskId : diskIds) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE storage_disks SET used_cells = (SELECT COUNT(*) FROM storage_items WHERE disk_id = ?), updated_at = CURRENT_TIMESTAMP WHERE disk_id = ?")) {
                stmt.setString(1, diskId);
                stmt.setString(2, diskId);
                stmt.executeUpdate();
            }
        }
    }

    private int getMaxCells(Connection conn, String diskId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT max_cells FROM storage_disks WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 27;
            }
        }
    }

    public String serializeItemStack(ItemStack item) {
        try {
            // Use Paper's modern serialization
            byte[] serialized = item.serializeAsBytes();
            return Base64.getEncoder().encodeToString(serialized);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to serialize item: " + e.getMessage());
            return "";
        }
    }

    public ItemStack deserializeItemStack(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            // Use Paper's modern deserialization
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize item: " + e.getMessage());
            return null;
        }
    }
}