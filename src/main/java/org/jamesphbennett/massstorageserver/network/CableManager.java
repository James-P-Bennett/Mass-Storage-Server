package org.jamesphbennett.massstorageserver.network;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final NetworkConnectivityManager connectivityManager;
    
    public CableManager(MassStorageServer plugin) {
        this.plugin = plugin;
        this.connectivityManager = new NetworkConnectivityManager(plugin);
    }

    /**
     * Handle cable placement with comprehensive network validation
     */
    public boolean handleCablePlacement(Player player, Location location) {
        try {
            // First check if this cable would violate network linking rules
            String linkingConflict = checkNetworkLinkingConflict(location);
            if (linkingConflict != null) {
                player.sendMessage(Component.text(linkingConflict, NamedTextColor.RED));
                return false;
            }

            // Use unified connectivity manager for conflict checking
            NetworkConnectivityManager.ConflictResult conflictResult = 
                connectivityManager.checkPlacementConflicts(location, NetworkConnectivityManager.BlockType.NETWORK_CABLE);
            if (conflictResult.hasConflict()) {
                Component message = MiniMessage.miniMessage().deserialize(conflictResult.getMessage());
                player.sendMessage(message);
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
            NetworkConnectivityManager.NetworkConnectivity connectivity = connectivityManager.analyzeLocation(location);
            
            // Find the target network this cable would connect to
            String targetNetworkId = null;
            for (String networkId : connectivity.getConnectedNetworkIds()) {
                targetNetworkId = networkId;
                break; // Take the first network found
            }

            if (targetNetworkId != null) {
                int currentCableCount = getCableCountForNetwork(targetNetworkId);
                int maxCables = plugin.getConfigManager().getMaxNetworkCables();

                if (currentCableCount >= maxCables) {
                    String message = plugin.getMessageManager().getMessage(player, "errors.placement.cable-limit-exceeded")
                        .replace("{max}", String.valueOf(maxCables));
                    player.sendMessage(Component.text(message, NamedTextColor.RED));
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
     * Check if a block is any custom network block or cable (delegates to connectivity manager)
     */
    public boolean isCustomNetworkBlockOrCable(Block block) {
        return connectivityManager.getBlockType(block) != NetworkConnectivityManager.BlockType.UNKNOWN;
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
     * Check for storage server conflicts AND general MSS block connection conflicts using unified connectivity manager
     */
    public String checkStorageServerConflict(Location location, String blockType) {
        // Convert string blockType to enum
        NetworkConnectivityManager.BlockType blockTypeEnum = getBlockTypeEnum(blockType);
        if (blockTypeEnum == NetworkConnectivityManager.BlockType.UNKNOWN) {
            return null;
        }

        NetworkConnectivityManager.ConflictResult result = 
            connectivityManager.checkPlacementConflicts(location, blockTypeEnum);
        
        return result.hasConflict() ? result.getMessage() : null;
    }

    /**
     * Check for security terminal conflicts AND general MSS block connection conflicts using unified connectivity manager
     */
    public String checkSecurityTerminalConflict(Location location, String blockType) {
        // Convert string blockType to enum
        NetworkConnectivityManager.BlockType blockTypeEnum = getBlockTypeEnum(blockType);
        if (blockTypeEnum == NetworkConnectivityManager.BlockType.UNKNOWN) {
            return null;
        }

        NetworkConnectivityManager.ConflictResult result = 
            connectivityManager.checkPlacementConflicts(location, blockTypeEnum);
        
        // Only return security terminal conflicts (storage server conflicts are handled by checkStorageServerConflict)
        if (result.hasConflict() && result.getConflictType() == NetworkConnectivityManager.ConflictType.MULTIPLE_SECURITY_TERMINALS) {
            return result.getMessage();
        }
        
        return null;
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
     * Check if a block is a custom network block (not cable)
     */
    private boolean isCustomNetworkBlock(Block block) {
        NetworkConnectivityManager.BlockType blockType = connectivityManager.getBlockType(block);
        return blockType != NetworkConnectivityManager.BlockType.NETWORK_CABLE && 
               blockType != NetworkConnectivityManager.BlockType.UNKNOWN;
    }

    /**
     * Check if a location is marked as a custom block in the database
     */
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
     * Convert string block type to enum
     */
    private NetworkConnectivityManager.BlockType getBlockTypeEnum(String blockType) {
        switch (blockType) {
            case "STORAGE_SERVER":
                return NetworkConnectivityManager.BlockType.STORAGE_SERVER;
            case "DRIVE_BAY":
                return NetworkConnectivityManager.BlockType.DRIVE_BAY;
            case "MSS_TERMINAL":
                return NetworkConnectivityManager.BlockType.MSS_TERMINAL;
            case "SECURITY_TERMINAL":
                return NetworkConnectivityManager.BlockType.SECURITY_TERMINAL;
            case "NETWORK_CABLE":
                return NetworkConnectivityManager.BlockType.NETWORK_CABLE;
            case "EXPORTER":
                return NetworkConnectivityManager.BlockType.EXPORTER;
            case "IMPORTER":
                return NetworkConnectivityManager.BlockType.IMPORTER;
            default:
                return NetworkConnectivityManager.BlockType.UNKNOWN;
        }
    }
}