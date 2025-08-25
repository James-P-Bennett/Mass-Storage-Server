package org.jamesphbennett.massstorageserver.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class CableManager {

    private final MassStorageServer plugin;
    public CableManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle cable placement with comprehensive network validation
     */
    public boolean handleCablePlacement(Player player, Location location) {
        try {
            // First check if this cable would violate network linking rules
            String conflictResult = checkNetworkLinkingConflict(location);
            if (conflictResult != null) {
                player.sendMessage(Component.text(conflictResult, NamedTextColor.RED));
                return false;
            }

            // Check for security terminal conflicts (cables can't connect networks with multiple security terminals)
            String securityConflict = checkSecurityTerminalConflict(location, "NETWORK_CABLE");
            if (securityConflict != null) {
                player.sendMessage(Component.text(securityConflict, NamedTextColor.RED));
                return false;
            }

            // Check cable limit
            if (!checkCableLimit(location, player)) {
                return false;
            }

            // Mark as custom cable
            markLocationAsCustomCable(location);

            return true;

        } catch (Exception e) {
            player.sendMessage(Component.text("Error placing cable: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error placing cable: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check for network linking conflicts when placing any network component
     */
    public String checkNetworkLinkingConflict(Location location) {
        try {
            Set<String> adjacentNetworks = new HashSet<>();
            List<Location> adjacentLocations = getAdjacentLocations(location);

            for (Location adjacent : adjacentLocations) {
                Block adjacentBlock = adjacent.getBlock();

                // Check if adjacent block is a custom MSS block (including cables)
                if (isCustomNetworkBlockOrCable(adjacentBlock)) {
                    String networkId = getNetworkIdForLocation(adjacent);

                    // Only consider valid, established networks for conflict checking
                    // BUT also allow connecting to standalone network blocks (special case)
                    if (networkId != null && !networkId.startsWith("standalone_") &&
                            !networkId.startsWith("orphaned_") && isValidNetwork(networkId)) {
                        adjacentNetworks.add(networkId);
                    } else if (networkId != null && networkId.startsWith("standalone_") && 
                               isCustomNetworkBlock(adjacentBlock)) {
                        // Allow connecting to standalone network blocks (single blocks without full networks)
                        // This enables cables to connect to individual security terminals, storage servers, etc.
                        adjacentNetworks.add(networkId);
                    }
                }
            }

            // If more than one established network would be connected, prevent placement
            if (adjacentNetworks.size() > 1) {
                return "Cannot connect different established networks! Found " + adjacentNetworks.size() + " networks.";
            }

            return null; // No conflict

        } catch (Exception e) {
            plugin.getLogger().severe("Error checking network linking conflict: " + e.getMessage());
            return "Error checking network conflicts.";
        }
    }

    /**
     * Check if placing this cable would exceed the cable limit for the network
     */
    private boolean checkCableLimit(Location location, Player player) {
        try {
            // Find what network this cable would belong to
            String targetNetworkId = null;

            for (Location adjacent : getAdjacentLocations(location)) {
                if (isCustomNetworkBlockOrCable(adjacent.getBlock())) {
                    String networkId = getNetworkIdForLocation(adjacent);
                    // Allow connecting to valid networks OR standalone network blocks
                    if (networkId != null && isValidNetwork(networkId)) {
                        targetNetworkId = networkId;
                        break;
                    } else if (networkId != null && networkId.startsWith("standalone_") && 
                               isCustomNetworkBlock(adjacent.getBlock())) {
                        // Allow connecting to standalone network blocks for network building
                        targetNetworkId = networkId;
                        break;
                    }
                }
            }

            if (targetNetworkId != null) {
                int currentCableCount = getCableCountForNetwork(targetNetworkId);
                int maxCables = plugin.getConfigManager().getMaxNetworkCables();

                if (currentCableCount >= maxCables) {
                    player.sendMessage(Component.text("Network cable limit exceeded! Maximum " + maxCables + " cables per network.", NamedTextColor.RED));
                    return false;
                }

                plugin.debugLog("Cable placement: Network " + targetNetworkId + " will have " + (currentCableCount + 1) + "/" + maxCables + " cables");
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Error checking cable limit: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get cable count for a specific network
     */
    private int getCableCountForNetwork(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM network_blocks WHERE network_id = ? AND block_type = 'NETWORK_CABLE'")) {

            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Check if a block is a custom network cable (strict validation)
     */
    public boolean isCustomNetworkCable(Block block) {
        if (block.getType() != Material.HEAVY_CORE) return false;
        return isMarkedAsCustomCable(block.getLocation());
    }

    /**
     * Check if a block is any custom network block or cable
     */
    public boolean isCustomNetworkBlockOrCable(Block block) {
        return isCustomNetworkBlock(block) || isCustomNetworkCable(block);
    }

    /**
     * Check if a block is a custom network block (not cable)
     */
    private boolean isCustomNetworkBlock(Block block) {
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block) || isCustomSecurityTerminal(block);
    }

    /**
     * Check if a location is marked as a custom cable in the database
     */
    private boolean isMarkedAsCustomCable(Location location) {
        return isMarkedAsCustomBlock(location, "NETWORK_CABLE");
    }

    /**
     * Mark a location as containing a custom cable
     */
    private void markLocationAsCustomCable(Location location) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO custom_block_markers (world_name, x, y, z, block_type) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());
                stmt.setString(5, "NETWORK_CABLE");
                stmt.executeUpdate();
            }
        });
    }

    /**
     * Get network ID for a location
     */
    private String getNetworkIdForLocation(Location location) {
        try {
            return plugin.getNetworkManager().getNetworkId(location);
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting network ID for location: " + e.getMessage());
            return null;
        }
    }

    /**
     * Check if a network is valid and established
     */
    private boolean isValidNetwork(String networkId) {
        try {
            return plugin.getNetworkManager().isNetworkValid(networkId);
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking network validity: " + e.getMessage());
            return false;
        }
    }

    /**
     * Check for storage server conflicts when networks would be combined (ENHANCED with comprehensive detection)
     */
    public String checkStorageServerConflict(Location location, String blockType) {
        try {
            if (!"STORAGE_SERVER".equals(blockType)) {
                return null; // Only storage servers can cause this conflict
            }

            plugin.getLogger().info("[Storage Server Debug] Checking conflict for storage server at " + location);

            // Use comprehensive network detection to find all networks that would be connected
            Set<String> connectedNetworks = findAllConnectedNetworks(location);
            Set<Location> connectedStorageServers = findAllConnectedStorageServers(location);
            
            plugin.getLogger().info("[Storage Server Debug] Storage server placement would connect to " + connectedNetworks.size() + " networks: " + connectedNetworks);
            plugin.getLogger().info("[Storage Server Debug] Storage server placement would connect to " + connectedStorageServers.size() + " standalone storage servers");

            // Check if any connected network has a storage server
            boolean hasAdjacentServer = false;
            for (String networkId : connectedNetworks) {
                if (networkHasStorageServer(networkId)) {
                    plugin.getLogger().info("[Storage Server Debug] BLOCKING: Connected network " + networkId + " already has storage server");
                    hasAdjacentServer = true;
                    break;
                }
            }
            
            // Check if any standalone storage servers would be connected
            if (!connectedStorageServers.isEmpty()) {
                plugin.getLogger().info("[Storage Server Debug] BLOCKING: Would connect to " + connectedStorageServers.size() + " standalone storage servers");
                hasAdjacentServer = true;
            }

            if (hasAdjacentServer && (!connectedNetworks.isEmpty() || !connectedStorageServers.isEmpty())) {
                plugin.getLogger().info("[Storage Server Debug] BLOCKING: Storage server placement - adjacent network/server has storage server");
                return "Cannot place Storage Server: Adjacent network already has a Storage Server!";
            }

            plugin.getLogger().info("[Storage Server Debug] NO CONFLICT: Allowing storage server placement");
            return null;

        } catch (Exception e) {
            plugin.getLogger().severe("Error checking storage server conflict: " + e.getMessage());
            return "Error checking server conflicts.";
        }
    }

    /**
     * Check for security terminal conflicts when networks would be combined (COPIED FROM STORAGE SERVER LOGIC)
     */
    public String checkSecurityTerminalConflict(Location location, String blockType) {
        try {
            // Find all adjacent networks that would be connected
            Set<String> adjacentNetworks = new HashSet<>();
            boolean hasAdjacentSecurityTerminal = false;
            int standaloneSecurityTerminalCount = 0;

            plugin.getLogger().info("[Security Terminal Debug] Checking conflict for " + blockType + " at " + location);

            // For security terminal placement, we need to check cable chains more thoroughly
            if ("SECURITY_TERMINAL".equals(blockType)) {
                // Use network detection to find all networks/terminals this would connect to
                Set<String> connectedNetworks = findAllConnectedNetworks(location);
                Set<Location> connectedSecurityTerminals = findAllConnectedSecurityTerminals(location);
                
                plugin.getLogger().info("[Security Terminal Debug] Security terminal placement would connect to " + connectedNetworks.size() + " networks: " + connectedNetworks);
                plugin.getLogger().info("[Security Terminal Debug] Security terminal placement would connect to " + connectedSecurityTerminals.size() + " standalone security terminals");
                
                // Check if any connected network has a security terminal
                for (String networkId : connectedNetworks) {
                    if (networkHasSecurityTerminal(networkId)) {
                        plugin.getLogger().info("[Security Terminal Debug] BLOCKING: Connected network " + networkId + " already has security terminal");
                        hasAdjacentSecurityTerminal = true;
                        break;
                    }
                }
                
                // Check if any standalone security terminals would be connected
                if (!connectedSecurityTerminals.isEmpty()) {
                    plugin.getLogger().info("[Security Terminal Debug] BLOCKING: Would connect to " + connectedSecurityTerminals.size() + " standalone security terminals");
                    hasAdjacentSecurityTerminal = true;
                    standaloneSecurityTerminalCount = connectedSecurityTerminals.size();
                }
                
                adjacentNetworks.addAll(connectedNetworks);
            } else {
                // For other blocks (cables, MSS blocks), use the existing logic
                for (Location adjacent : getAdjacentLocations(location)) {
                    if (isCustomNetworkBlockOrCable(adjacent.getBlock())) {
                        String networkId = getNetworkIdForLocation(adjacent);
                        plugin.getLogger().info("[Security Terminal Debug] Adjacent block at " + adjacent + " has network ID: " + networkId);
                        
                        // Special case: standalone security terminals might have no network ID
                        if (networkId == null && isCustomSecurityTerminal(adjacent.getBlock())) {
                            standaloneSecurityTerminalCount++;
                            plugin.getLogger().info("[Security Terminal Debug] Found standalone security terminal (no network ID) at " + adjacent);
                            hasAdjacentSecurityTerminal = true;
                            continue;
                        }
                        
                        // Special case: cables might connect to standalone security terminals through cable chains
                        if (networkId == null && isCustomNetworkCable(adjacent.getBlock())) {
                            plugin.getLogger().info("[Security Terminal Debug] Found cable with no network ID at " + adjacent + ", traversing cable chain");
                            // Traverse the entire cable chain to find what it connects to
                            Set<Location> visited = new HashSet<>();
                            Queue<Location> cablesToCheck = new LinkedList<>();
                            cablesToCheck.add(adjacent);
                            visited.add(adjacent);
                            visited.add(location); // Don't go back to the original location
                            
                            while (!cablesToCheck.isEmpty()) {
                                Location currentCable = cablesToCheck.poll();
                                
                                for (Location cableAdjacent : getAdjacentLocations(currentCable)) {
                                    if (visited.contains(cableAdjacent)) continue;
                                    visited.add(cableAdjacent);
                                    
                                    Block adjBlock = cableAdjacent.getBlock();
                                    
                                    // If we find a security terminal, check its network status
                                    if (isCustomSecurityTerminal(adjBlock)) {
                                        String cableConnectedNetworkId = getNetworkIdForLocation(cableAdjacent);
                                        plugin.getLogger().info("[Security Terminal Debug] Cable chain connects to security terminal at " + cableAdjacent + " with network ID: " + cableConnectedNetworkId);
                                        if (cableConnectedNetworkId == null) {
                                            standaloneSecurityTerminalCount++;
                                            plugin.getLogger().info("[Security Terminal Debug] Found standalone security terminal through cable chain");
                                            hasAdjacentSecurityTerminal = true;
                                        }
                                    }
                                    // Continue following the cable chain
                                    else if (isCustomNetworkCable(adjBlock)) {
                                        String chainNetworkId = getNetworkIdForLocation(cableAdjacent);
                                        if (chainNetworkId == null) {
                                            cablesToCheck.add(cableAdjacent);
                                        }
                                    }
                                }
                            }
                        }
                        
                        // Check both valid networks AND standalone networks
                        if (networkId != null && !networkId.startsWith("orphaned_")) {
                            boolean isValidNet = isValidNetwork(networkId);
                            boolean isStandaloneNet = networkId.startsWith("standalone_");
                            
                            plugin.getLogger().info("[Security Terminal Debug] Network " + networkId + " - isValid: " + isValidNet + ", isStandalone: " + isStandaloneNet);
                            
                            if (isValidNet || (isStandaloneNet && isCustomNetworkBlock(adjacent.getBlock()))) {
                                adjacentNetworks.add(networkId);

                                // Check if any adjacent network already has a security terminal
                                boolean hasSecurityTerminal = networkHasSecurityTerminal(networkId);
                                plugin.getLogger().info("[Security Terminal Debug] Network " + networkId + " has security terminal: " + hasSecurityTerminal);
                                
                                if (hasSecurityTerminal) {
                                    hasAdjacentSecurityTerminal = true;
                                }
                            }
                        }
                    }
                }
            }

            plugin.getLogger().info("[Security Terminal Debug] Found " + adjacentNetworks.size() + " adjacent networks: " + adjacentNetworks);
            plugin.getLogger().info("[Security Terminal Debug] Found " + standaloneSecurityTerminalCount + " standalone security terminals");
            plugin.getLogger().info("[Security Terminal Debug] Has adjacent security terminal: " + hasAdjacentSecurityTerminal);

            // If placing a security terminal and an adjacent network already has one
            if ("SECURITY_TERMINAL".equals(blockType) && hasAdjacentSecurityTerminal && (!adjacentNetworks.isEmpty() || standaloneSecurityTerminalCount > 0)) {
                plugin.getLogger().info("[Security Terminal Debug] BLOCKING: Security terminal placement - adjacent network/terminal has security terminal");
                return "Cannot place Security Terminal: Adjacent network already has a Security Terminal!";
            }

            // If placing any other block (cable, MSS block, etc.) that would connect networks with security terminals
            if (!"SECURITY_TERMINAL".equals(blockType) && hasAdjacentSecurityTerminal) {
                // Count networks with security terminals (including standalone terminals as separate "networks")
                int networksWithSecurityTerminals = standaloneSecurityTerminalCount;
                for (String networkId : adjacentNetworks) {
                    if (networkHasSecurityTerminal(networkId)) {
                        networksWithSecurityTerminals++;
                    }
                }
                
                plugin.getLogger().info("[Security Terminal Debug] Total networks/terminals with security terminals: " + networksWithSecurityTerminals);
                
                // Block if connecting multiple security terminals (standalone + network, or multiple networks)
                if (networksWithSecurityTerminals > 1 || (standaloneSecurityTerminalCount > 0 && !adjacentNetworks.isEmpty())) {
                    plugin.getLogger().info("[Security Terminal Debug] BLOCKING: Cable/block placement - would connect multiple security terminals");
                    return "Cannot connect networks: Multiple networks have Security Terminals!";
                }
            }

            plugin.getLogger().info("[Security Terminal Debug] NO CONFLICT: Allowing placement");
            return null;

        } catch (Exception e) {
            plugin.getLogger().severe("Error checking security terminal conflict: " + e.getMessage());
            return "Error checking security terminal conflicts.";
        }
    }

    /**
     * Check if a network has a storage server
     */
    private boolean networkHasStorageServer(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM network_blocks WHERE network_id = ? AND block_type = 'STORAGE_SERVER'")) {

            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Check if a network has a security terminal
     */
    private boolean networkHasSecurityTerminal(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM security_terminals WHERE network_id = ?")) {

            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /**
     * Get adjacent locations (face-adjacent only)
     */
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

    private boolean isCustomSecurityTerminal(Block block) {
        if (block.getType() != Material.OBSERVER) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "SECURITY_TERMINAL");
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

    /**
     * Find all networks that would be connected if placing a block at this location
     */
    private Set<String> findAllConnectedNetworks(Location location) {
        Set<String> connectedNetworks = new HashSet<>();
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
            
            if (isCustomNetworkBlockOrCable(block)) {
                String networkId = getNetworkIdForLocation(current);
                
                // Add valid networks and standalone networks to the connected set
                if (networkId != null && !networkId.startsWith("orphaned_")) {
                    if (isValidNetwork(networkId) || networkId.startsWith("standalone_")) {
                        connectedNetworks.add(networkId);
                        plugin.getLogger().info("[Security Terminal Debug] Found connected network: " + networkId + " at " + current);
                    }
                }
                
                // If this is a cable with no network ID, continue traversing
                if (networkId == null && isCustomNetworkCable(block)) {
                    for (Location cableAdjacent : getAdjacentLocations(current)) {
                        if (!visited.contains(cableAdjacent)) {
                            visited.add(cableAdjacent);
                            toCheck.add(cableAdjacent);
                        }
                    }
                }
            }
        }
        
        return connectedNetworks;
    }
    
    /**
     * Find all standalone security terminals that would be connected if placing a block at this location
     */
    private Set<Location> findAllConnectedSecurityTerminals(Location location) {
        Set<Location> connectedSecurityTerminals = new HashSet<>();
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
            
            // Check if this is a standalone security terminal
            if (isCustomSecurityTerminal(block)) {
                String networkId = getNetworkIdForLocation(current);
                if (networkId == null) {
                    connectedSecurityTerminals.add(current);
                    plugin.getLogger().info("[Security Terminal Debug] Found connected standalone security terminal at " + current);
                }
            }
            
            // Continue traversing cables with no network ID
            if (isCustomNetworkCable(block)) {
                String networkId = getNetworkIdForLocation(current);
                if (networkId == null) {
                    for (Location cableAdjacent : getAdjacentLocations(current)) {
                        if (!visited.contains(cableAdjacent)) {
                            visited.add(cableAdjacent);
                            toCheck.add(cableAdjacent);
                        }
                    }
                }
            }
        }
        
        return connectedSecurityTerminals;
    }
    
    /**
     * Find all standalone storage servers that would be connected if placing a block at this location
     */
    private Set<Location> findAllConnectedStorageServers(Location location) {
        Set<Location> connectedStorageServers = new HashSet<>();
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
            
            // Check if this is a standalone storage server
            if (isCustomStorageServer(block)) {
                String networkId = getNetworkIdForLocation(current);
                if (networkId == null || networkId.startsWith("standalone_")) {
                    connectedStorageServers.add(current);
                    plugin.getLogger().info("[Storage Server Debug] Found connected standalone storage server at " + current);
                }
            }
            
            // Continue traversing cables with no network ID
            if (isCustomNetworkCable(block)) {
                String networkId = getNetworkIdForLocation(current);
                if (networkId == null) {
                    for (Location cableAdjacent : getAdjacentLocations(current)) {
                        if (!visited.contains(cableAdjacent)) {
                            visited.add(cableAdjacent);
                            toCheck.add(cableAdjacent);
                        }
                    }
                }
            }
        }
        
        return connectedStorageServers;
    }

    /**
     * Get adjacent locations (face-adjacent only)
     */
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
}