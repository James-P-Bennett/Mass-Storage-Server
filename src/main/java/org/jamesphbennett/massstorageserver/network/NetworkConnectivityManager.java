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

/**
 * Unified manager for network connectivity analysis and conflict detection.
 * Handles all logic for how MSS blocks and cables extend networks and enforces
 * unique block constraints (one storage server, one security terminal per network).
 */
public class NetworkConnectivityManager {

    private final MassStorageServer plugin;

    public NetworkConnectivityManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Analyze what networks and blocks would be connected if placing a block at the given location
     */
    public NetworkConnectivity analyzeLocation(Location location) {
        Set<String> connectedNetworkIds = new HashSet<>();
        Map<BlockType, Set<Location>> connectedBlocks = new EnumMap<>(BlockType.class);
        Set<Location> traversedCables = new HashSet<>();
        
        // Initialize block type sets
        for (BlockType type : BlockType.values()) {
            connectedBlocks.put(type, new HashSet<>());
        }

        Set<Location> visited = new HashSet<>();
        Queue<Location> toCheck = new LinkedList<>();

        // Start with all adjacent locations
        for (Location adjacent : getAdjacentLocations(location)) {
            if (!visited.contains(adjacent)) {
                toCheck.add(adjacent);
                visited.add(adjacent);
            }
        }

        visited.add(location); // Don't revisit the placement location

        while (!toCheck.isEmpty()) {
            Location current = toCheck.poll();
            Block block = current.getBlock();

            if (isNetworkBlockOrCable(block)) {
                BlockType blockType = getBlockType(block);
                String networkId = getNetworkIdForLocation(current);

                // Handle network blocks (non-cables)
                if (blockType != BlockType.NETWORK_CABLE) {
                    connectedBlocks.get(blockType).add(current);
                    
                    // Add network ID if this block has one
                    if (networkId != null && !networkId.startsWith("orphaned_")) {
                        if (isValidNetwork(networkId) || networkId.startsWith("standalone_")) {
                            connectedNetworkIds.add(networkId);
                        }
                    }
                    
                    // MSS blocks (terminals, drive bays) can extend networks like cables!
                    // If they have no network ID, continue traversing through them
                    if (networkId == null && canExtendNetwork(blockType)) {
                        for (Location mssAdjacent : getAdjacentLocations(current)) {
                            if (!visited.contains(mssAdjacent)) {
                                visited.add(mssAdjacent);
                                toCheck.add(mssAdjacent);
                            }
                        }
                    }
                } else {
                    // Handle cables
                    traversedCables.add(current);
                    
                    // If cable has no network ID, continue traversing through it
                    if (networkId == null) {
                        for (Location cableAdjacent : getAdjacentLocations(current)) {
                            if (!visited.contains(cableAdjacent)) {
                                visited.add(cableAdjacent);
                                toCheck.add(cableAdjacent);
                            }
                        }
                    } else if (!networkId.startsWith("orphaned_")) {
                        if (isValidNetwork(networkId) || networkId.startsWith("standalone_")) {
                            connectedNetworkIds.add(networkId);
                        }
                    }
                }
            }
        }

        return new NetworkConnectivity(connectedNetworkIds, connectedBlocks, traversedCables);
    }

    /**
     * Check if placing a block would create conflicts
     */
    public ConflictResult checkPlacementConflicts(Location location, BlockType blockType) {
        NetworkConnectivity connectivity = analyzeLocation(location);
        
        // All MSS blocks and cables need to check connection conflicts
        // Storage servers and security terminals get additional unique placement checks
        ConflictResult connectionResult = checkConnectionConflicts(connectivity, blockType);
        if (connectionResult.hasConflict()) {
            return connectionResult;
        }

        // Additional unique placement checks for special blocks
        switch (blockType) {
            case STORAGE_SERVER:
                return checkStorageServerConflicts(connectivity);
            case SECURITY_TERMINAL:
                return checkSecurityTerminalConflicts(connectivity);
            default:
                return ConflictResult.noConflict();
        }
    }

    private ConflictResult checkStorageServerConflicts(NetworkConnectivity connectivity) {
        // Check if any connected network already has a storage server
        for (String networkId : connectivity.getConnectedNetworkIds()) {
            if (networkHasStorageServer(networkId)) {
                String message = plugin.getMessageManager().getMessage((org.bukkit.entity.Player) null, "errors.placement.storage-server-conflict");
                return ConflictResult.conflict(ConflictType.MULTIPLE_STORAGE_SERVERS, message);
            }
        }
        
        // Check if any standalone storage servers would be connected
        Set<Location> storageServers = connectivity.getConnectedBlocks(BlockType.STORAGE_SERVER);
        if (!storageServers.isEmpty()) {
            String message = plugin.getMessageManager().getMessage((org.bukkit.entity.Player) null, "errors.placement.storage-server-conflict");
            return ConflictResult.conflict(ConflictType.MULTIPLE_STORAGE_SERVERS, message);
        }
        
        return ConflictResult.noConflict();
    }

    private ConflictResult checkSecurityTerminalConflicts(NetworkConnectivity connectivity) {
        // Check if any connected network already has a security terminal
        for (String networkId : connectivity.getConnectedNetworkIds()) {
            if (networkHasSecurityTerminal(networkId)) {
                String message = plugin.getMessageManager().getMessage((org.bukkit.entity.Player) null, "errors.placement.security-terminal-conflict");
                return ConflictResult.conflict(ConflictType.MULTIPLE_SECURITY_TERMINALS, message);
            }
        }
        
        // Check if any standalone security terminals would be connected
        Set<Location> securityTerminals = connectivity.getConnectedBlocks(BlockType.SECURITY_TERMINAL);
        if (!securityTerminals.isEmpty()) {
            String message = plugin.getMessageManager().getMessage((org.bukkit.entity.Player) null, "errors.placement.security-terminal-conflict");
            return ConflictResult.conflict(ConflictType.MULTIPLE_SECURITY_TERMINALS, message);
        }
        
        return ConflictResult.noConflict();
    }

    private ConflictResult checkConnectionConflicts(NetworkConnectivity connectivity, BlockType placingBlockType) {
        // Only check connection conflicts for blocks that can extend networks
        if (!canExtendNetwork(placingBlockType)) {
            return ConflictResult.noConflict();
        }
        
        Set<String> networksWithStorageServers = new HashSet<>();
        Set<String> networksWithSecurityTerminals = new HashSet<>();
        int standaloneStorageServers = 0;
        int standaloneSecurityTerminals = 0;
        
        // Check networks for unique blocks
        for (String networkId : connectivity.getConnectedNetworkIds()) {
            if (networkHasStorageServer(networkId)) {
                networksWithStorageServers.add(networkId);
            }
            if (networkHasSecurityTerminal(networkId)) {
                networksWithSecurityTerminals.add(networkId);
            }
        }
        
        // Count only truly standalone blocks (those with no network ID)
        standaloneStorageServers = 0;
        standaloneSecurityTerminals = 0;
        
        for (Location storageServer : connectivity.getConnectedBlocks(BlockType.STORAGE_SERVER)) {
            String networkId = getNetworkIdForLocation(storageServer);
            if (networkId == null) {
                standaloneStorageServers++;
            }
        }
        
        for (Location securityTerminal : connectivity.getConnectedBlocks(BlockType.SECURITY_TERMINAL)) {
            String networkId = getNetworkIdForLocation(securityTerminal);
            if (networkId == null) {
                standaloneSecurityTerminals++;
            }
        }
        
        // Check storage server conflicts 
        // We only block if there are multiple separate sources of storage servers:
        // - Multiple networks each with storage servers, OR
        // - Multiple standalone storage servers, OR  
        // - Mix of network(s) with storage servers AND standalone storage server(s)
        int storageServerSources = networksWithStorageServers.size() + standaloneStorageServers;
        boolean hasMultipleStorageServerSources = storageServerSources > 1;
        
        if (hasMultipleStorageServerSources) {
            String message = plugin.getMessageManager().getMessage((org.bukkit.entity.Player) null, "errors.placement.multiple-storage-servers");
            return ConflictResult.conflict(ConflictType.MULTIPLE_STORAGE_SERVERS, message);
        }
        
        // Check security terminal conflicts with same logic
        int securityTerminalSources = networksWithSecurityTerminals.size() + standaloneSecurityTerminals;
        boolean hasMultipleSecurityTerminalSources = securityTerminalSources > 1;
        
        if (hasMultipleSecurityTerminalSources) {
            String message = plugin.getMessageManager().getMessage((org.bukkit.entity.Player) null, "errors.placement.multiple-security-terminals");
            return ConflictResult.conflict(ConflictType.MULTIPLE_SECURITY_TERMINALS, message);
        }
        
        return ConflictResult.noConflict();
    }

    private boolean networkHasStorageServer(String networkId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM network_blocks WHERE network_id = ? AND block_type = 'STORAGE_SERVER'")) {

            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking storage server in network: " + e.getMessage());
            return false;
        }
    }

    private boolean networkHasSecurityTerminal(String networkId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM security_terminals WHERE network_id = ?")) {

            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking security terminal in network: " + e.getMessage());
            return false;
        }
    }

    private boolean isNetworkBlockOrCable(Block block) {
        return getBlockType(block) != BlockType.UNKNOWN;
    }

    public BlockType getBlockType(Block block) {
        if (block.getType() == Material.CHISELED_TUFF && isMarkedAsCustomBlock(block.getLocation(), "STORAGE_SERVER")) {
            return BlockType.STORAGE_SERVER;
        }
        if (block.getType() == Material.CHISELED_TUFF_BRICKS && isMarkedAsCustomBlock(block.getLocation(), "DRIVE_BAY")) {
            return BlockType.DRIVE_BAY;
        }
        if (block.getType() == Material.CRAFTER && isMarkedAsCustomBlock(block.getLocation(), "MSS_TERMINAL")) {
            return BlockType.MSS_TERMINAL;
        }
        if (block.getType() == Material.OBSERVER && isMarkedAsCustomBlock(block.getLocation(), "SECURITY_TERMINAL")) {
            return BlockType.SECURITY_TERMINAL;
        }
        if (block.getType() == Material.HEAVY_CORE && isMarkedAsCustomBlock(block.getLocation(), "NETWORK_CABLE")) {
            return BlockType.NETWORK_CABLE;
        }
        if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
            if (isMarkedAsCustomBlock(block.getLocation(), "EXPORTER")) {
                return BlockType.EXPORTER;
            }
            if (isMarkedAsCustomBlock(block.getLocation(), "IMPORTER")) {
                return BlockType.IMPORTER;
            }
        }
        return BlockType.UNKNOWN;
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

    private String getNetworkIdForLocation(Location location) {
        try {
            return plugin.getNetworkManager().getNetworkId(location);
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting network ID for location: " + e.getMessage());
            return null;
        }
    }

    private boolean isValidNetwork(String networkId) {
        try {
            return plugin.getNetworkManager().isNetworkValid(networkId);
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking network validity: " + e.getMessage());
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

    /**
     * Determine if a block type can extend networks (act like cables for connectivity)
     * All MSS blocks can extend networks - uniqueness is enforced through conflict checking
     */
    private boolean canExtendNetwork(BlockType blockType) {
        switch (blockType) {
            case MSS_TERMINAL:
            case DRIVE_BAY:
            case EXPORTER:
            case IMPORTER:
            case STORAGE_SERVER:
            case SECURITY_TERMINAL:
                return true; // All MSS blocks can extend networks
            case NETWORK_CABLE:
                return true; // Obviously cables extend networks
            default:
                return false;
        }
    }

    // Enums and data classes

    public enum BlockType {
        STORAGE_SERVER,
        DRIVE_BAY,
        MSS_TERMINAL,
        SECURITY_TERMINAL,
        NETWORK_CABLE,
        EXPORTER,
        IMPORTER,
        UNKNOWN
    }

    public enum ConflictType {
        MULTIPLE_STORAGE_SERVERS,
        MULTIPLE_SECURITY_TERMINALS,
        NETWORK_LIMIT_EXCEEDED,
        CABLE_LIMIT_EXCEEDED
    }

    public static class NetworkConnectivity {
        private final Set<String> connectedNetworkIds;
        private final Map<BlockType, Set<Location>> connectedBlocks;
        private final Set<Location> traversedCables;

        public NetworkConnectivity(Set<String> connectedNetworkIds, 
                                 Map<BlockType, Set<Location>> connectedBlocks, 
                                 Set<Location> traversedCables) {
            this.connectedNetworkIds = connectedNetworkIds;
            this.connectedBlocks = connectedBlocks;
            this.traversedCables = traversedCables;
        }

        public Set<String> getConnectedNetworkIds() {
            return connectedNetworkIds;
        }

        public Set<Location> getConnectedBlocks(BlockType type) {
            return connectedBlocks.getOrDefault(type, Collections.emptySet());
        }

        public Set<Location> getTraversedCables() {
            return traversedCables;
        }

        public boolean hasConnectedBlocks(BlockType type) {
            return !getConnectedBlocks(type).isEmpty();
        }
    }

    public static class ConflictResult {
        private final boolean hasConflict;
        private final ConflictType conflictType;
        private final String message;

        private ConflictResult(boolean hasConflict, ConflictType conflictType, String message) {
            this.hasConflict = hasConflict;
            this.conflictType = conflictType;
            this.message = message;
        }

        public static ConflictResult noConflict() {
            return new ConflictResult(false, null, null);
        }

        public static ConflictResult conflict(ConflictType type, String message) {
            return new ConflictResult(true, type, message);
        }

        public boolean hasConflict() {
            return hasConflict;
        }

        public ConflictType getConflictType() {
            return conflictType;
        }

        public String getMessage() {
            return message;
        }
    }
}