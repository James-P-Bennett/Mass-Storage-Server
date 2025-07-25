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
     * Register a network in the database
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
        });
    }

    /**
     * Remove a network from the database (ENHANCED with GUI handling)
     */
    public void unregisterNetwork(String networkId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Delete network blocks
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM network_blocks WHERE network_id = ?")) {
                stmt.setString(1, networkId);
                stmt.executeUpdate();
            }

            // Delete drive bay slots
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM drive_bay_slots WHERE network_id = ?")) {
                stmt.setString(1, networkId);
                stmt.executeUpdate();
            }

            // Update storage disks to remove network association
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

        plugin.getLogger().info("Unregistered network " + networkId + " and closed associated GUIs");
    }

    /**
     * Check if a network exists and is valid
     */
    public boolean isNetworkValid(String networkId) throws SQLException {
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