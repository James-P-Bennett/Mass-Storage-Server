package org.jamesphbennett.massstorageserver.storage;

import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.database.DatabaseManager;
import org.jamesphbennett.massstorageserver.managers.ItemManager;

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

            try {
                DatabaseManager.DatabaseTransaction transaction = (Connection conn) -> {
                    // Get all storage disks in the network
                    List<String> diskIds = getNetworkDiskIds(conn, networkId);

                    if (diskIds.isEmpty()) {
                        remainders.addAll(items);
                        return;
                    }

                    for (ItemStack item : items) {
                        if (!itemManager.isItemAllowed(item)) {
                            remainders.add(item);
                            continue;
                        }

                        ItemStack remainder = storeItemInNetwork(conn, diskIds, item);
                        if (remainder != null && remainder.getAmount() > 0) {
                            remainders.add(remainder);
                        }
                    }

                    // Update disk cell counts
                    updateDiskCellCounts(conn, diskIds);
                };

                plugin.getDatabaseManager().executeTransaction(transaction);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

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
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "SELECT si.disk_id, si.item_data, si.quantity, si.max_stack_size " +
                                    "FROM storage_items si " +
                                    "JOIN storage_disks sd ON si.disk_id = sd.disk_id " +
                                    "JOIN drive_bay_slots dbs ON sd.disk_id = dbs.disk_id " + // CRITICAL: Only from drive bay disks
                                    "WHERE dbs.network_id = ? AND si.item_hash = ? AND si.quantity > 0 AND dbs.disk_id IS NOT NULL " +
                                    "ORDER BY si.quantity DESC")) {

                        stmt.setString(1, networkId);
                        stmt.setString(2, itemHash);

                        try (ResultSet rs = stmt.executeQuery()) {
                            int remainingToRetrieve = amount;
                            List<ItemStack> retrievedItems = new ArrayList<>();

                            while (rs.next() && remainingToRetrieve > 0) {
                                String diskId = rs.getString("disk_id");
                                String itemData = rs.getString("item_data");
                                int currentQuantity = rs.getInt("quantity");
                                int maxStackSize = rs.getInt("max_stack_size");

                                int toRetrieve = Math.min(remainingToRetrieve, currentQuantity);
                                int newQuantity = currentQuantity - toRetrieve;

                                // Update storage
                                try (PreparedStatement updateStmt = conn.prepareStatement(
                                        "UPDATE storage_items SET quantity = ?, updated_at = CURRENT_TIMESTAMP WHERE disk_id = ? AND item_hash = ?")) {
                                    updateStmt.setInt(1, newQuantity);
                                    updateStmt.setString(2, diskId);
                                    updateStmt.setString(3, itemHash);
                                    updateStmt.executeUpdate();
                                }

                                // If quantity reaches 0, remove the item entry
                                if (newQuantity == 0) {
                                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                                            "DELETE FROM storage_items WHERE disk_id = ? AND item_hash = ?")) {
                                        deleteStmt.setString(1, diskId);
                                        deleteStmt.setString(2, itemHash);
                                        deleteStmt.executeUpdate();
                                    }
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
                                ItemStack combinedItem = retrievedItems.get(0).clone();
                                int totalAmount = retrievedItems.stream().mapToInt(ItemStack::getAmount).sum();
                                combinedItem.setAmount(totalAmount);
                                result[0] = combinedItem;
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
     */
    public List<StoredItem> getNetworkItems(String networkId) throws Exception {
        return plugin.getNetworkManager().withNetworkLock(networkId, () -> {
            List<StoredItem> items = new ArrayList<>();

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT si.item_hash, si.item_data, SUM(si.quantity) as total_quantity " +
                                 "FROM storage_items si " +
                                 "JOIN storage_disks sd ON si.disk_id = sd.disk_id " +
                                 "JOIN drive_bay_slots dbs ON sd.disk_id = dbs.disk_id " + // CRITICAL: Only disks in drive bays
                                 "WHERE dbs.network_id = ? AND dbs.disk_id IS NOT NULL " + // Must be in a drive bay slot
                                 "GROUP BY si.item_hash, si.item_data " +
                                 "HAVING total_quantity > 0 " +
                                 "ORDER BY total_quantity DESC")) {

                stmt.setString(1, networkId);

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

                plugin.getLogger().info("Found " + items.size() + " item types from active drive bay disks in network " + networkId);

            } catch (SQLException e) {
                throw new RuntimeException(e);
            }

            return items;
        });
    }

    private ItemStack storeItemInNetwork(Connection conn, List<String> diskIds, ItemStack item) throws SQLException {
        String itemHash = itemManager.generateItemHash(item);
        String itemData = serializeItemStack(item);
        int amountToStore = item.getAmount();
        int maxStackSize = item.getMaxStackSize();
        final int MAX_ITEMS_PER_CELL = plugin.getConfigManager().getMaxItemsPerCell();

        // PHASE 1: Try to add to existing storage entries first
        for (String diskId : diskIds) {
            if (amountToStore <= 0) break;

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT quantity FROM storage_items WHERE disk_id = ? AND item_hash = ?")) {
                stmt.setString(1, diskId);
                stmt.setString(2, itemHash);

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        int currentQuantity = rs.getInt("quantity");
                        int canAdd = Math.min(amountToStore, MAX_ITEMS_PER_CELL - currentQuantity);

                        if (canAdd > 0) {
                            // Update existing storage
                            try (PreparedStatement updateStmt = conn.prepareStatement(
                                    "UPDATE storage_items SET quantity = quantity + ?, updated_at = CURRENT_TIMESTAMP WHERE disk_id = ? AND item_hash = ?")) {
                                updateStmt.setInt(1, canAdd);
                                updateStmt.setString(2, diskId);
                                updateStmt.setString(3, itemHash);
                                updateStmt.executeUpdate();
                            }

                            amountToStore -= canAdd;
                        }
                    }
                }
            }
        }

        // PHASE 2: Only create new storage cells if we still have items to store
        if (amountToStore > 0) {
            for (String diskId : diskIds) {
                if (amountToStore <= 0) break;

                // Check if disk has available cells
                if (hasAvailableCells(conn, diskId)) {
                    int canStore = Math.min(amountToStore, MAX_ITEMS_PER_CELL);

                    // Create new storage entry
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "INSERT INTO storage_items (disk_id, item_hash, item_data, quantity, max_stack_size) VALUES (?, ?, ?, ?, ?)")) {
                        stmt.setString(1, diskId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.setInt(4, canStore);
                        stmt.setInt(5, maxStackSize);
                        stmt.executeUpdate();
                    }

                    amountToStore -= canStore;
                }
            }
        }

        // Return remainder if any
        if (amountToStore > 0) {
            ItemStack remainder = item.clone();
            remainder.setAmount(amountToStore);
            return remainder;
        }

        return null;
    }

    private boolean hasAvailableCells(Connection conn, String diskId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT (SELECT max_cells FROM storage_disks WHERE disk_id = ?) - COUNT(*) as available_cells FROM storage_items WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            stmt.setString(2, diskId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("available_cells") > 0;
                }
            }
        }
        return false;
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

    private int getUsedCells(Connection conn, String diskId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT COUNT(*) FROM storage_items WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
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

    private String serializeItemStack(ItemStack item) {
        try {
            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            org.bukkit.util.io.BukkitObjectOutputStream dataOutput = new org.bukkit.util.io.BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to serialize item: " + e.getMessage());
            return "";
        }
    }

    private ItemStack deserializeItemStack(String data) {
        try {
            byte[] bytes = Base64.getDecoder().decode(data);
            java.io.ByteArrayInputStream inputStream = new java.io.ByteArrayInputStream(bytes);
            org.bukkit.util.io.BukkitObjectInputStream dataInput = new org.bukkit.util.io.BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to deserialize item: " + e.getMessage());
            return null;
        }
    }
}