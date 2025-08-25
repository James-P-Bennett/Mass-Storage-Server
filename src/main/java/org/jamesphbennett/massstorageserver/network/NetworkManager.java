package org.jamesphbennett.massstorageserver.network;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class NetworkManager {

    private final MassStorageServer plugin;

    // Network locks for thread safety
    private final Map<String, ReentrantLock> networkLocks = new ConcurrentHashMap<>();

    // Flag to reduce redundant drive bay restoration logging
    private final Set<String> restorationLoggedNetworks = new HashSet<>();

    public NetworkManager(MassStorageServer plugin) {
        this.plugin = plugin;
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
        Set<Location> networkCables = new HashSet<>();
        Queue<Location> toCheck = new LinkedList<>();

        toCheck.add(location);
        visited.add(location);

        Location storageServer = null;
        Location securityTerminal = null;
        Set<Location> driveBays = new HashSet<>();
        Set<Location> terminals = new HashSet<>();

        // BFS to find connected network blocks
        while (!toCheck.isEmpty()) {
            Location current = toCheck.poll();
            Block block = current.getBlock();

            if (!isNetworkBlockOrCable(block)) continue;

            if (plugin.getCableManager().isCustomNetworkCable(block)) {
                networkCables.add(current);
            } else if (isExporter(block)) {
                // Exporters count as network blocks and extend connectivity
                networkBlocks.add(current);
            } else if (isImporter(block)) {
                // Importers count as network blocks and extend connectivity
                networkBlocks.add(current);
            } else {
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
                } else if (isSecurityTerminal(block)) {
                    if (securityTerminal != null) {
                        // Multiple security terminals - invalid network
                        return null;
                    }
                    securityTerminal = current;
                    // Security terminals are part of the network but don't count toward network validity requirements
                    // They are completely optional and separate from MSS terminals
                }
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

        // Check block limit (excluding cables)
        if (networkBlocks.size() > plugin.getConfigManager().getMaxNetworkBlocks()) {
            plugin.getLogger().warning("Network exceeds block limit: " + networkBlocks.size() + "/" + plugin.getConfigManager().getMaxNetworkBlocks());
            return null;
        }

        // Check cable limits
        if (networkCables.size() > plugin.getConfigManager().getMaxNetworkCables()) {
            plugin.getLogger().warning("Network exceeds cable limit: " + networkCables.size() + "/" + plugin.getConfigManager().getMaxNetworkCables());
            return null;
        }

        // Create combined set for all blocks
        Set<Location> allBlocks = new HashSet<>();
        allBlocks.addAll(networkBlocks);
        allBlocks.addAll(networkCables);

        return new NetworkInfo(generateNetworkId(storageServer), storageServer, driveBays, terminals, allBlocks, networkCables);
    }

    /**
     * Get network ID for a location, with special handling for exporters
     */
    public String getNetworkId(Location location) {
        Block block = location.getBlock();

        // Special handling for exporters - check their stored network ID first
        if (isExporter(block)) {
            return getExporterNetworkId(location);
        }

        // For other blocks, use normal network detection
        NetworkInfo network = detectNetwork(location);
        return network != null ? network.getNetworkId() : null;
    }

    /**
     * Get the network ID for an exporter from the database
     */
    private String getExporterNetworkId(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT network_id FROM exporters WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String networkId = rs.getString("network_id");

                    // Verify the network is still valid
                    if (networkId != null && isNetworkValid(networkId)) {
                        return networkId;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting exporter network ID: " + e.getMessage());
        }

        return null;
    }

    /**
     * Check if a block is an exporter
     */
    private boolean isExporter(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return false;
        }
        return isMarkedAsCustomBlock(block.getLocation(), "EXPORTER");
    }

    /**
     * Check if a block is an importer (for completeness, but importers don't carry network signals)
     */
    private boolean isImporter(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) {
            return false;
        }
        return isMarkedAsCustomBlock(block.getLocation(), "IMPORTER");
    }

    /**
     * Register a network in the database (ENHANCED with network cable support)
     */
    public void registerNetwork(NetworkInfo network, UUID ownerUUID) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Insert or update network
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO networks (network_id, owner_uuid, last_accessed) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
                stmt.setString(1, network.getNetworkId());
                stmt.setString(2, ownerUUID != null ? ownerUUID.toString() : "00000000-0000-0000-0000-000000000000");
                stmt.executeUpdate();
            }

            // Clear existing network blocks
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM network_blocks WHERE network_id = ?")) {
                stmt.setString(1, network.getNetworkId());
                stmt.executeUpdate();
            }

            // Insert network blocks (including cables)
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

            // COMPREHENSIVE DRIVE BAY RESTORATION (with reduced logging)
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

            // Don't delete drive bay slots - preserve them for recovery
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

        // Notify GUI manager about network invalidation
        plugin.getGUIManager().handleNetworkInvalidated(networkId);

        // Remove network lock and restoration flag
        networkLocks.remove(networkId);
        restorationLoggedNetworks.remove(networkId);

        plugin.debugLog("Unregistered network " + networkId + " and preserved drive bay contents");
    }

    /**
     * COMPREHENSIVE restoration of ALL drive bay contents when network is reconstructed
     * UPDATED: Reduced logging and redundancy prevention
     */
    private void restoreAllDriveBayContents(Connection conn, String newNetworkId, Set<Location> driveBayLocations) throws SQLException {
        int totalRestoredSlots = 0;
        int totalRestoredDisks = 0;
        boolean shouldLog = !restorationLoggedNetworks.contains(newNetworkId);

        if (shouldLog) {
            plugin.debugLog("Starting drive bay restoration for network " + newNetworkId +
                    " with " + driveBayLocations.size() + " drive bays");
        }

        for (Location driveBayLocation : driveBayLocations) {
            // Restore orphaned slots for this specific drive bay location
            try (PreparedStatement updateStmt = conn.prepareStatement(
                    "UPDATE drive_bay_slots SET network_id = ? WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id LIKE 'orphaned_%'")) {
                updateStmt.setString(1, newNetworkId);
                updateStmt.setString(2, driveBayLocation.getWorld().getName());
                updateStmt.setInt(3, driveBayLocation.getBlockX());
                updateStmt.setInt(4, driveBayLocation.getBlockY());
                updateStmt.setInt(5, driveBayLocation.getBlockZ());

                int restoredSlots = updateStmt.executeUpdate();
                totalRestoredSlots += restoredSlots;
            }

            // Restore unassigned storage disks for slots in this drive bay
            try (PreparedStatement selectStmt = conn.prepareStatement(
                    "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id = ? AND disk_id IS NOT NULL")) {
                selectStmt.setString(1, driveBayLocation.getWorld().getName());
                selectStmt.setInt(2, driveBayLocation.getBlockX());
                selectStmt.setInt(3, driveBayLocation.getBlockY());
                selectStmt.setInt(4, driveBayLocation.getBlockZ());
                selectStmt.setString(5, newNetworkId);

                try (ResultSet rs = selectStmt.executeQuery()) {
                    while (rs.next()) {
                        String diskId = rs.getString("disk_id");

                        // Update disk to be part of this network
                        try (PreparedStatement diskUpdateStmt = conn.prepareStatement(
                                "UPDATE storage_disks SET network_id = ? WHERE disk_id = ? AND network_id IS NULL")) {
                            diskUpdateStmt.setString(1, newNetworkId);
                            diskUpdateStmt.setString(2, diskId);

                            int updated = diskUpdateStmt.executeUpdate();
                            if (updated > 0) {
                                totalRestoredDisks++;
                            }
                        }
                    }
                }
            }
        }

        if (shouldLog) {
            plugin.debugLog("Restoration complete for network " + newNetworkId + ": " +
                    totalRestoredSlots + " slots and " + totalRestoredDisks + " disks restored");
            restorationLoggedNetworks.add(newNetworkId);
        }
    }

    /**
     * Check if a network exists and is valid
     */
    public boolean isNetworkValid(String networkId) {
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
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking network validity: " + e.getMessage());
            return false;
        }
    }

    private List<Location> getAdjacentLocations(Location center) {
        List<Location> adjacent = new ArrayList<>();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // Only consider blocks that share a face (not edges or corners)
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

    private boolean isNetworkBlockOrCable(Block block) {
        return isNetworkBlock(block) || plugin.getCableManager().isCustomNetworkCable(block) || isExporter(block) || isImporter(block);
    }

    private boolean isNetworkBlock(Block block) {
        return isStorageServer(block) || isDriveBay(block) || isMSSTerminal(block) || isSecurityTerminal(block);
    }

    private boolean isStorageServer(Block block) {
        return block.getType() == Material.CHISELED_TUFF && isMarkedAsCustomBlock(block.getLocation(), "STORAGE_SERVER");
    }

    private boolean isDriveBay(Block block) {
        return block.getType() == Material.CHISELED_TUFF_BRICKS && isMarkedAsCustomBlock(block.getLocation(), "DRIVE_BAY");
    }

    private boolean isMSSTerminal(Block block) {
        return block.getType() == Material.CRAFTER && isMarkedAsCustomBlock(block.getLocation(), "MSS_TERMINAL");
    }

    private boolean isSecurityTerminal(Block block) {
        return block.getType() == Material.OBSERVER && isMarkedAsCustomBlock(block.getLocation(), "SECURITY_TERMINAL");
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

    private String getBlockType(Block block) {
        if (isStorageServer(block)) return "STORAGE_SERVER";
        if (isDriveBay(block)) return "DRIVE_BAY";
        if (isMSSTerminal(block)) return "MSS_TERMINAL";
        if (isSecurityTerminal(block)) return "SECURITY_TERMINAL";
        if (plugin.getCableManager().isCustomNetworkCable(block)) return "NETWORK_CABLE";
        if (isExporter(block)) return "EXPORTER";
        if (isImporter(block)) return "IMPORTER";
        return "UNKNOWN";
    }

    @FunctionalInterface
    public interface NetworkOperation<T> {
        T execute() throws Exception;
    }
}