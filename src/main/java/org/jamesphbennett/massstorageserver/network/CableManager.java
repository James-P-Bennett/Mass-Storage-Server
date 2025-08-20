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

                    // Only consider valid, established networks
                    if (networkId != null && !networkId.startsWith("standalone_") &&
                            !networkId.startsWith("orphaned_") && isValidNetwork(networkId)) {
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
                    if (networkId != null && isValidNetwork(networkId)) {
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
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block);
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
     * Check for storage server conflicts when networks would be combined
     */
    public String checkStorageServerConflict(Location location, String blockType) {
        try {
            if (!"STORAGE_SERVER".equals(blockType)) {
                return null; // Only storage servers can cause this conflict
            }

            // Find all adjacent networks that would be connected
            Set<String> adjacentNetworks = new HashSet<>();
            boolean hasAdjacentServer = false;

            for (Location adjacent : getAdjacentLocations(location)) {
                if (isCustomNetworkBlockOrCable(adjacent.getBlock())) {
                    String networkId = getNetworkIdForLocation(adjacent);
                    if (networkId != null && isValidNetwork(networkId)) {
                        adjacentNetworks.add(networkId);

                        // Check if any adjacent network already has a storage server
                        if (networkHasStorageServer(networkId)) {
                            hasAdjacentServer = true;
                        }
                    }
                }
            }

            if (hasAdjacentServer && !adjacentNetworks.isEmpty()) {
                return "Cannot place Storage Server: Adjacent network already has a Storage Server!";
            }

            return null;

        } catch (Exception e) {
            plugin.getLogger().severe("Error checking storage server conflict: " + e.getMessage());
            return "Error checking server conflicts.";
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