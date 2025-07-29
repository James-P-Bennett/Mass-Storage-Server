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

            if (!isNetworkBlock(block)) continue;

            networkBlocks.add(current);

            // Categorize block type
            if (isStorageServer(block)) {
                if (storageServer != null) {
                    // Multiple storage servers - invalid network
                    return null;
                }
                storageServer = current;
            } else if (isDriveBay(block)) {
                driveBays.add(current);
            } else if (isMSSTerminal(block)) {
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
     * Register a network in the database (ENHANCED with comprehensive drive bay restoration)
     */
    public void registerNetwork(NetworkInfo network, UUID ownerUUID) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Insert or update network
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO networks (network_id, owner_uuid, last_accessed) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                stmt.setString(1, network.getNetworkId());
                stmt.setString(2, ownerUUID.toString());
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

            // COMPREHENSIVE DRIVE BAY RESTORATION
            restoreAllDriveBayContents(conn, network.getNetworkId(), network.getDriveBays());
        });
    }

    /**
     * Remove a network from the database (ENHANCED with drive bay preservation)
     */
    public void unregisterNetwork(String networkId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Delete network blocks
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM network_blocks WHERE network_id = ?")) {
                stmt.setString(1, networkId);
                stmt.executeUpdate();
            }

            // UPDATED: Don't delete drive bay slots - preserve them for recovery
            // Instead, mark them as orphaned by setting network_id to a special value
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE drive_bay_slots SET network_id = ? WHERE network_id = ?")) {
                stmt.setString(1, "orphaned_" + networkId);
                stmt.setString(2, networkId);
                stmt.executeUpdate();
            }

            // Update storage disks to remove network association but keep disk data
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

        // CRITICAL: Notify GUI manager about network invalidation
        plugin.getGUIManager().handleNetworkInvalidated(networkId);

        // Remove network lock
        networkLocks.remove(networkId);

        plugin.getLogger().info("Unregistered network " + networkId + " and preserved drive bay contents");
    }

    /**
     * COMPREHENSIVE restoration of ALL drive bay contents when network is reconstructed
     */
    private void restoreAllDriveBayContents(Connection conn, String newNetworkId, Set<Location> driveBayLocations) throws SQLException {
        int totalRestoredSlots = 0;
        int totalRestoredDisks = 0;

        plugin.getLogger().info("Starting comprehensive drive bay restoration for network " + newNetworkId +
                " with " + driveBayLocations.size() + " drive bay locations");

        for (Location location : driveBayLocations) {
            plugin.getLogger().info("Checking drive bay at " + location + " for restoration");

            // STEP 1: Restore orphaned drive bay slots at this location
            int restoredSlots = restoreOrphanedDriveBaySlots(conn, newNetworkId, location);
            totalRestoredSlots += restoredSlots;

            // STEP 2: Find and restore disks that should be associated with this network
            int restoredDisks = restoreNetworkDiskAssociations(conn, newNetworkId, location);
            totalRestoredDisks += restoredDisks;

            // STEP 3: Update disk cell counts for any restored disks
            updateDiskCellCountsForLocation(conn, location);
        }

        if (totalRestoredSlots > 0 || totalRestoredDisks > 0) {
            plugin.getLogger().info("Network " + newNetworkId + " restoration complete: " +
                    totalRestoredSlots + " drive bay slots restored, " +
                    totalRestoredDisks + " disk associations restored");

            // Refresh all terminals and drive bays for this network
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                plugin.getGUIManager().refreshNetworkTerminals(newNetworkId);
                plugin.getGUIManager().refreshNetworkDriveBays(newNetworkId);
            });
        } else {
            plugin.getLogger().info("No drive bay contents needed restoration for network " + newNetworkId);
        }
    }

    /**
     * Restore orphaned drive bay slots at a specific location
     */
    private int restoreOrphanedDriveBaySlots(Connection conn, String newNetworkId, Location location) throws SQLException {
        int restoredCount = 0;

        // Find any orphaned or standalone drive bay slots at this location
        try (PreparedStatement findStmt = conn.prepareStatement(
                "SELECT network_id, slot_number, disk_id FROM drive_bay_slots " +
                        "WHERE world_name = ? AND x = ? AND y = ? AND z = ? " +
                        "AND (network_id LIKE 'orphaned_%' OR network_id LIKE 'standalone_%' OR network_id != ?)")) {

            findStmt.setString(1, location.getWorld().getName());
            findStmt.setInt(2, location.getBlockX());
            findStmt.setInt(3, location.getBlockY());
            findStmt.setInt(4, location.getBlockZ());
            findStmt.setString(5, newNetworkId);

            List<DriveSlotInfo> slotsToRestore = new ArrayList<>();
            try (ResultSet rs = findStmt.executeQuery()) {
                while (rs.next()) {
                    String oldNetworkId = rs.getString("network_id");
                    int slotNumber = rs.getInt("slot_number");
                    String diskId = rs.getString("disk_id");
                    slotsToRestore.add(new DriveSlotInfo(oldNetworkId, slotNumber, diskId));

                    plugin.getLogger().info("Found drive bay slot to restore: slot " + slotNumber +
                            " with disk " + diskId + " from network " + oldNetworkId);
                }
            }

            // Restore each slot to the new network
            for (DriveSlotInfo slotInfo : slotsToRestore) {
                try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE drive_bay_slots SET network_id = ? " +
                                "WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND slot_number = ?")) {
                    updateStmt.setString(1, newNetworkId);
                    updateStmt.setString(2, location.getWorld().getName());
                    updateStmt.setInt(3, location.getBlockX());
                    updateStmt.setInt(4, location.getBlockY());
                    updateStmt.setInt(5, location.getBlockZ());
                    updateStmt.setInt(6, slotInfo.slotNumber);

                    int updated = updateStmt.executeUpdate();
                    if (updated > 0) {
                        restoredCount++;
                        plugin.getLogger().info("Restored drive bay slot " + slotInfo.slotNumber +
                                " at " + location + " to network " + newNetworkId);
                    }
                }
            }
        }

        return restoredCount;
    }

    /**
     * Restore network associations for disks that are in drive bay slots at this location
     */
    private int restoreNetworkDiskAssociations(Connection conn, String newNetworkId, Location location) throws SQLException {
        int restoredCount = 0;

        // Find all disks in drive bay slots at this location and update their network association
        try (PreparedStatement findDisksStmt = conn.prepareStatement(
                "SELECT DISTINCT dbs.disk_id FROM drive_bay_slots dbs " +
                        "JOIN storage_disks sd ON dbs.disk_id = sd.disk_id " +
                        "WHERE dbs.world_name = ? AND dbs.x = ? AND dbs.y = ? AND dbs.z = ? " +
                        "AND dbs.network_id = ? AND dbs.disk_id IS NOT NULL " +
                        "AND (sd.network_id IS NULL OR sd.network_id != ?)")) {

            findDisksStmt.setString(1, location.getWorld().getName());
            findDisksStmt.setInt(2, location.getBlockX());
            findDisksStmt.setInt(3, location.getBlockY());
            findDisksStmt.setInt(4, location.getBlockZ());
            findDisksStmt.setString(5, newNetworkId);
            findDisksStmt.setString(6, newNetworkId);

            List<String> disksToRestore = new ArrayList<>();
            try (ResultSet rs = findDisksStmt.executeQuery()) {
                while (rs.next()) {
                    String diskId = rs.getString("disk_id");
                    if (diskId != null) {
                        disksToRestore.add(diskId);
                    }
                }
            }

            // Update network association for each disk
            for (String diskId : disksToRestore) {
                try (PreparedStatement updateDiskStmt = conn.prepareStatement(
                        "UPDATE storage_disks SET network_id = ? WHERE disk_id = ?")) {
                    updateDiskStmt.setString(1, newNetworkId);
                    updateDiskStmt.setString(2, diskId);

                    int updated = updateDiskStmt.executeUpdate();
                    if (updated > 0) {
                        restoredCount++;
                        plugin.getLogger().info("Restored network association for disk " + diskId +
                                " to network " + newNetworkId);
                    }
                }
            }
        }

        return restoredCount;
    }

    /**
     * Update disk cell counts for all disks at a specific drive bay location
     */
    private void updateDiskCellCountsForLocation(Connection conn, Location location) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT DISTINCT dbs.disk_id FROM drive_bay_slots dbs " +
                        "WHERE dbs.world_name = ? AND dbs.x = ? AND dbs.y = ? AND dbs.z = ? " +
                        "AND dbs.disk_id IS NOT NULL")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String diskId = rs.getString("disk_id");
                    if (diskId != null) {
                        try (PreparedStatement updateStmt = conn.prepareStatement(
                                "UPDATE storage_disks SET used_cells = (SELECT COUNT(*) FROM storage_items WHERE disk_id = ?), updated_at = CURRENT_TIMESTAMP WHERE disk_id = ?")) {
                            updateStmt.setString(1, diskId);
                            updateStmt.setString(2, diskId);
                            updateStmt.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    /**
     * Helper class to store drive slot information
     */
    private static class DriveSlotInfo {
        final String oldNetworkId;
        final int slotNumber;
        final String diskId;

        DriveSlotInfo(String oldNetworkId, int slotNumber, String diskId) {
            this.oldNetworkId = oldNetworkId;
            this.slotNumber = slotNumber;
            this.diskId = diskId;
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
     * Restore orphaned drive bay slots when a network is reformed at the same location
     * (DEPRECATED - now handled by comprehensive restoration)
     */
    @Deprecated
    public void restoreOrphanedDriveBays(String newNetworkId, Set<Location> driveBayLocations) throws SQLException {
        // This method is now deprecated - the comprehensive restoration handles everything
        plugin.getLogger().info("restoreOrphanedDriveBays called but comprehensive restoration is now used instead");
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

    private List<Location> getAdjacentLocations(Location center) {
        List<Location> adjacent = new ArrayList<>();
        int[] offsets = {-1, 0, 1};

        for (int dx : offsets) {
            for (int dy : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // Skip center

                    // Only check face-adjacent blocks (not diagonal)
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

    private boolean isNetworkBlock(Block block) {
        return isStorageServer(block) || isDriveBay(block) || isMSSTerminal(block);
    }

    private boolean isStorageServer(Block block) {
        return block.getType() == Material.CHISELED_TUFF;
    }

    private boolean isDriveBay(Block block) {
        return block.getType() == Material.CHISELED_TUFF_BRICKS;
    }

    private boolean isMSSTerminal(Block block) {
        return block.getType() == Material.CRAFTER;
    }

    private String getBlockType(Block block) {
        if (isStorageServer(block)) return "STORAGE_SERVER";
        if (isDriveBay(block)) return "DRIVE_BAY";
        if (isMSSTerminal(block)) return "MSS_TERMINAL";
        return "UNKNOWN";
    }

    @FunctionalInterface
    public interface NetworkOperation<T> {
        T execute() throws Exception;
    }
}