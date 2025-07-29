package org.jamesphbennett.massstorageserver.network;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.managers.ItemManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class NetworkManager {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;

    // Network locks for thread safety
    private final Map<String, ReentrantLock> networkLocks = new ConcurrentHashMap<>();

    public NetworkManager(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
    }

    /**
     * Get or create a lock for a specific network
     */
    private ReentrantLock getNetworkLock(String networkId) {
        return networkLocks.computeIfAbsent(networkId, k -> new ReentrantLock());
    }

    /**
     * Execute an operation with network locking
     */
    public <T> T withNetworkLock(String networkId, NetworkOperation<T> operation) throws Exception {
        ReentrantLock lock = getNetworkLock(networkId);
        lock.lock();
        try {
            return operation.execute();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Detect and validate a network starting from a given block
     * FIXED: Only accepts custom MSS blocks, not vanilla blocks
     */
    public NetworkInfo detectNetwork(Location location) {
        Set<Location> visited = new HashSet<>();
        Set<Location> networkBlocks = new HashSet<>();
        Queue<Location> toCheck = new LinkedList<>();

        toCheck.add(location);
        visited.add(location);

        Location storageServer = null;
        Set<Location> driveBays = new HashSet<>();
        Set<Location> terminals = new HashSet<>();

        // BFS to find connected network blocks
        while (!toCheck.isEmpty()) {
            Location current = toCheck.poll();
            Block block = current.getBlock();

            // CRITICAL FIX: Only accept custom MSS blocks
            if (!isCustomNetworkBlock(block)) continue;

            networkBlocks.add(current);

            // Categorize block type
            if (isCustomStorageServer(block)) {
                if (storageServer != null) {
                    // Multiple storage servers - invalid network
                    return null;
                }
                storageServer = current;
            } else if (isCustomDriveBay(block)) {
                driveBays.add(current);
            } else if (isCustomMSSTerminal(block)) {
                terminals.add(current);
            }

            // Check adjacent blocks
            for (Location adjacent : getAdjacentLocations(current)) {
                if (!visited.contains(adjacent)) {
                    visited.add(adjacent);
                    toCheck.add(adjacent);
                }
            }
        }

        // Validate network requirements
        if (storageServer == null || driveBays.isEmpty() || terminals.isEmpty()) {
            return null;
        }

        return new NetworkInfo(generateNetworkId(storageServer), storageServer, driveBays, terminals, networkBlocks);
    }

    /**
     * Get network ID for a location, if it's part of a valid network
     */
    public String getNetworkId(Location location) {
        NetworkInfo network = detectNetwork(location);
        return network != null ? network.getNetworkId() : null;
    }

    /**
     * Register a network in the database (SIMPLIFIED - no state retention)
     */
    public void registerNetwork(NetworkInfo network, UUID ownerUUID) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Insert or update network
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO networks (network_id, owner_uuid, last_accessed) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                stmt.setString(1, network.getNetworkId());
                stmt.setString(2, ownerUUID != null ? ownerUUID.toString() : "system");
                stmt.executeUpdate();
            }

            // Clear existing network blocks
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM network_blocks WHERE network_id = ?")) {
                stmt.setString(1, network.getNetworkId());
                stmt.executeUpdate();
            }

            // Insert network blocks
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO network_blocks (network_id, world_name, x, y, z, block_type) VALUES (?, ?, ?, ?, ?, ?)")) {

                for (Location loc : network.getAllBlocks()) {
                    stmt.setString(1, network.getNetworkId());
                    stmt.setString(2, loc.getWorld().getName());
                    stmt.setInt(3, loc.getBlockX());
                    stmt.setInt(4, loc.getBlockY());
                    stmt.setInt(5, loc.getBlockZ());
                    stmt.setString(6, getBlockType(loc.getBlock()));
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }

            // SIMPLIFIED: Only update existing drive bay slots to use this network ID
            // Don't try to restore any previous state
            updateExistingDriveBaySlots(conn, network.getNetworkId(), network.getDriveBays());
        });
    }

    /**
     * Remove a network from the database (FIXED - preserve drive bay contents)
     */
    public void unregisterNetwork(String networkId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Delete network blocks
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM network_blocks WHERE network_id = ?")) {
                stmt.setString(1, networkId);
                stmt.executeUpdate();
            }

            // FIXED: Mark drive bay slots as orphaned instead of deleting them
            // This preserves disk associations for when networks are reformed
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE drive_bay_slots SET network_id = ? WHERE network_id = ?")) {
                stmt.setString(1, "orphaned_" + networkId);
                stmt.setString(2, networkId);
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                    plugin.getLogger().info("Marked " + updated + " drive bay slots as orphaned for potential recovery");
                }
            }

            // Remove network association from storage disks but keep disk data
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE storage_disks SET network_id = NULL WHERE network_id = ?")) {
                stmt.setString(1, networkId);
                stmt.executeUpdate();
            }

            // Delete network
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM networks WHERE network_id = ?")) {
                stmt.setString(1, networkId);
                stmt.executeUpdate();
            }
        });

        // Notify GUI manager about network invalidation
        plugin.getGUIManager().handleNetworkInvalidated(networkId);

        // Remove network lock
        networkLocks.remove(networkId);

        plugin.getLogger().info("Unregistered network " + networkId + " and preserved drive bay contents as orphaned");
    }

    /**
     * ENHANCED: Update existing drive bay slots and restore orphaned ones
     */
    private void updateExistingDriveBaySlots(Connection conn, String networkId, Set<Location> driveBayLocations) throws SQLException {
        int restoredCount = 0;
        int updatedCount = 0;

        for (Location location : driveBayLocations) {
            // First, restore any orphaned drive bay slots at this location
            try (PreparedStatement restoreStmt = conn.prepareStatement(
                    "UPDATE drive_bay_slots SET network_id = ? WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id LIKE 'orphaned_%'")) {
                restoreStmt.setString(1, networkId);
                restoreStmt.setString(2, location.getWorld().getName());
                restoreStmt.setInt(3, location.getBlockX());
                restoreStmt.setInt(4, location.getBlockY());
                restoreStmt.setInt(5, location.getBlockZ());

                int restored = restoreStmt.executeUpdate();
                if (restored > 0) {
                    restoredCount += restored;
                    plugin.getLogger().info("Restored " + restored + " orphaned drive bay slots at " + location);
                }
            }

            // Then, update any other existing drive bay slots at this location
            try (PreparedStatement updateStmt = conn.prepareStatement(
                    "UPDATE drive_bay_slots SET network_id = ? WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id != ?")) {
                updateStmt.setString(1, networkId);
                updateStmt.setString(2, location.getWorld().getName());
                updateStmt.setInt(3, location.getBlockX());
                updateStmt.setInt(4, location.getBlockY());
                updateStmt.setInt(5, location.getBlockZ());
                updateStmt.setString(6, networkId); // Don't update slots already in this network

                int updated = updateStmt.executeUpdate();
                if (updated > 0) {
                    updatedCount += updated;
                    plugin.getLogger().info("Updated " + updated + " existing drive bay slots at " + location);
                }
            }

            // Finally, update disk network associations for any disks in this drive bay
            try (PreparedStatement diskUpdateStmt = conn.prepareStatement(
                    "UPDATE storage_disks SET network_id = ? WHERE disk_id IN (" +
                            "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id = ? AND disk_id IS NOT NULL)")) {
                diskUpdateStmt.setString(1, networkId);
                diskUpdateStmt.setString(2, location.getWorld().getName());
                diskUpdateStmt.setInt(3, location.getBlockX());
                diskUpdateStmt.setInt(4, location.getBlockY());
                diskUpdateStmt.setInt(5, location.getBlockZ());
                diskUpdateStmt.setString(6, networkId);

                int disksUpdated = diskUpdateStmt.executeUpdate();
                if (disksUpdated > 0) {
                    plugin.getLogger().info("Updated network association for " + disksUpdated + " disks at " + location);
                }
            }
        }

        if (restoredCount > 0 || updatedCount > 0) {
            plugin.getLogger().info("Drive bay restoration complete for network " + networkId + ": " +
                    restoredCount + " orphaned slots restored, " + updatedCount + " slots updated");
        }
    }

    /**
     * Check if a network exists and is valid
     */
    public boolean isNetworkValid(String networkId) throws SQLException {
        // Handle special network IDs
        if (networkId != null && (networkId.startsWith("standalone_") || networkId.startsWith("orphaned_"))) {
            return false;
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM networks WHERE network_id = ?")) {
            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Update network last accessed time
     */
    public void updateNetworkAccess(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE networks SET last_accessed = CURRENT_TIMESTAMP WHERE network_id = ?")) {
            stmt.setString(1, networkId);
            stmt.executeUpdate();
        }
    }

    // Helper methods to check if blocks are CUSTOM MSS blocks
    private boolean isCustomNetworkBlock(Block block) {
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block);
    }

    private boolean isCustomStorageServer(Block block) {
        if (block.getType() != Material.CHISELED_TUFF) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "STORAGE_SERVER");
    }

    private boolean isCustomDriveBay(Block block) {
        if (block.getType() != Material.CHISELED_TUFF_BRICKS) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "DRIVE_BAY");
    }

    private boolean isCustomMSSTerminal(Block block) {
        if (block.getType() != Material.CRAFTER) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "MSS_TERMINAL");
    }

    private boolean isMarkedAsCustomBlock(Location location, String blockType) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM custom_block_markers WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND block_type = ?")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());
            stmt.setString(5, blockType);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking custom block marker: " + e.getMessage());
            return false;
        }
    }

    private List<Location> getAdjacentLocations(Location center) {
        List<Location> adjacent = new ArrayList<>();
        int[] offsets = {-1, 0, 1};

        for (int dx : offsets) {
            for (int dy : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    // Only check face-adjacent blocks
                    int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                    if (nonZero == 1) {
                        adjacent.add(center.clone().add(dx, dy, dz));
                    }
                }
            }
        }

        return adjacent;
    }

    private String generateNetworkId(Location storageServerLocation) {
        return String.format("%s_%d_%d_%d",
                storageServerLocation.getWorld().getName(),
                storageServerLocation.getBlockX(),
                storageServerLocation.getBlockY(),
                storageServerLocation.getBlockZ());
    }

    private String getBlockType(Block block) {
        if (isCustomStorageServer(block)) return "STORAGE_SERVER";
        if (isCustomDriveBay(block)) return "DRIVE_BAY";
        if (isCustomMSSTerminal(block)) return "MSS_TERMINAL";
        return "UNKNOWN";
    }

    @FunctionalInterface
    public interface NetworkOperation<T> {
        T execute() throws Exception;
    }
}