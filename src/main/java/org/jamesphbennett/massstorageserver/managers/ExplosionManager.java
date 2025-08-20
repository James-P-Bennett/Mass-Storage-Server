package org.jamesphbennett.massstorageserver.managers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.network.NetworkInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class ExplosionManager {

    private final MassStorageServer plugin;

    public ExplosionManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle explosion damage to MSS blocks
     * @param blockList The list of blocks affected by the explosion
     * @param explosionLocation The location where the explosion occurred
     */
    public void handleExplosion(List<Block> blockList, Location explosionLocation) {
        List<Block> customBlocksToHandle = new ArrayList<>();
        List<Location> driveBayLocations = new ArrayList<>();
        List<Location> exporterLocations = new ArrayList<>();
        List<Location> importerLocations = new ArrayList<>();

        // First pass: identify our custom blocks in the explosion
        for (Block block : blockList) {
            if (isCustomNetworkBlock(block)) {
                customBlocksToHandle.add(block);

                if (isCustomDriveBay(block)) {
                    driveBayLocations.add(block.getLocation());
                } else if (isCustomExporter(block)) {
                    exporterLocations.add(block.getLocation());
                } else if (isCustomImporter(block)) {
                    importerLocations.add(block.getLocation());
                }
            }
        }

        if (customBlocksToHandle.isEmpty()) {
            return; // No MSS blocks affected
        }

        plugin.getLogger().info("Explosion at " + explosionLocation + " affecting " + customBlocksToHandle.size() +
                " MSS blocks (" + driveBayLocations.size() + " drive bays, " + exporterLocations.size() + " exporters)");

        // Handle drive bay contents BEFORE blocks are destroyed
        for (Location driveBayLoc : driveBayLocations) {
            try {
                // Find network ID for this drive bay (if any)
                String networkId = findNetworkIdForLocation(driveBayLoc);
                if (networkId == null) {
                    // Try to find from drive bay slots table directly
                    networkId = findDriveBayNetworkIdFromDatabase(driveBayLoc);
                }

                if (networkId != null) {
                    plugin.getLogger().info("Dropping drive bay contents at " + driveBayLoc + " (network: " + networkId + ") due to explosion");
                    plugin.getDisksManager().dropDriveBayContents(driveBayLoc, networkId);
                } else {
                    plugin.getLogger().info("Drive bay at " + driveBayLoc + " has no network association, checking for orphaned contents");
                    plugin.getDisksManager().dropDriveBayContentsWithoutNetwork(driveBayLoc);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error handling drive bay explosion at " + driveBayLoc + ": " + e.getMessage());
                plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            }
        }

        // Handle exporter cleanup BEFORE blocks are destroyed
        for (Location exporterLoc : exporterLocations) {
            try {
                plugin.getLogger().info("Cleaning up exporter data at " + exporterLoc + " due to explosion");
                var exporterData = plugin.getExporterManager().getExporterAtLocation(exporterLoc);
                if (exporterData != null) {
                    plugin.getExporterManager().removeExporter(exporterData.exporterId);
                    plugin.getLogger().info("Removed exporter " + exporterData.exporterId + " due to explosion");
                } else {
                    plugin.getLogger().warning("No exporter data found at " + exporterLoc + " during explosion cleanup");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error handling exporter explosion at " + exporterLoc + ": " + e.getMessage());
                plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            }
        }

        // Handle importer cleanup BEFORE blocks are destroyed
        for (Location importerLoc : importerLocations) {
            try {
                plugin.getLogger().info("Cleaning up importer data at " + importerLoc + " due to explosion");
                var importerData = plugin.getImporterManager().getImporterAtLocation(importerLoc);
                if (importerData != null) {
                    plugin.getImporterManager().removeImporter(importerData.importerId);
                    plugin.getLogger().info("Removed importer " + importerData.importerId + " due to explosion");
                } else {
                    plugin.getLogger().warning("No importer data found at " + importerLoc + " during explosion cleanup");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error handling importer explosion at " + importerLoc + ": " + e.getMessage());
                plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            }
        }

        // Handle custom blocks - let vanilla explosion destroy them but drop custom items
        for (Block block : blockList) {
            if (isCustomNetworkBlock(block)) {
                try {
                    // Don't remove from explosion list - let vanilla mechanics destroy the block
                    // But prevent vanilla drops by setting the block to air first, then drop our custom item

                    // Get our custom item first
                    ItemStack customItem = getCustomItemForBlock(block);

                    // Remove custom block marker from database
                    removeCustomBlockMarker(block.getLocation());

                    // Set block to air to prevent vanilla drops, but keep it in explosion list
                    // The explosion system will still "destroy" it (no-op since it's already air)
                    block.setType(Material.AIR);

                    // Drop our custom item
                    if (customItem != null) {
                        block.getWorld().dropItemNaturally(block.getLocation(), customItem);
                        plugin.getLogger().info("Dropped custom item for " + getBlockTypeFromBlock(block) + " at " + block.getLocation());
                    }

                } catch (Exception e) {
                    plugin.getLogger().severe("Error handling custom block explosion: " + e.getMessage());
                }
            }
        }

        // Schedule network updates after explosion
        plugin.getServer().getScheduler().runTask(plugin, () -> updateNetworksAfterExplosion(customBlocksToHandle));
    }

    /**
     * Find network ID for a location by checking adjacent blocks
     */
    private String findNetworkIdForLocation(Location location) {
        try {
            return plugin.getNetworkManager().getNetworkId(location);
        } catch (Exception e) {
            plugin.getLogger().warning("Error finding network ID for location " + location + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Find network ID for a drive bay from the database
     */
    private String findDriveBayNetworkIdFromDatabase(Location location) {
        return plugin.getDisksManager().findDriveBayNetworkId(location);
    }

    private void updateNetworksAfterExplosion(List<Block> destroyedBlocks) {
        Set<String> affectedNetworks = new HashSet<>();

        // Find all networks that might be affected
        for (Block block : destroyedBlocks) {
            for (Location adjacent : getAdjacentLocations(block.getLocation())) {
                if (isCustomNetworkBlock(adjacent.getBlock())) {
                    try {
                        String networkId = plugin.getNetworkManager().getNetworkId(adjacent);
                        if (networkId != null) {
                            affectedNetworks.add(networkId);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error checking network after explosion: " + e.getMessage());
                    }
                }
            }
        }

        // Update each affected network
        for (String networkId : affectedNetworks) {
            try {
                // Try to re-detect the network
                boolean networkStillValid = false;

                // Find a remaining block from this network to test from
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

                            if (isCustomNetworkBlock(testLocation.getBlock())) {
                                NetworkInfo updatedNetwork = plugin.getNetworkManager().detectNetwork(testLocation);
                                if (updatedNetwork != null && updatedNetwork.isValid()) {
                                    plugin.getNetworkManager().registerNetwork(updatedNetwork, null); // No player for explosions
                                    networkStillValid = true;
                                    plugin.getLogger().info("Network " + networkId + " updated after explosion");
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Error checking network validity after explosion: " + e.getMessage());
                }

                if (!networkStillValid) {
                    // Network is destroyed, unregister it
                    plugin.getNetworkManager().unregisterNetwork(networkId);
                    plugin.getLogger().info("Network " + networkId + " dissolved due to explosion");
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Error updating network " + networkId + " after explosion: " + e.getMessage());
            }
        }
    }

    /**
     * Remove a network from the database (ENHANCED with drive bay preservation)
     */
    private void removeCustomBlockMarker(Location location) {
        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM custom_block_markers WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
                    stmt.setString(1, location.getWorld().getName());
                    stmt.setInt(2, location.getBlockX());
                    stmt.setInt(3, location.getBlockY());
                    stmt.setInt(4, location.getBlockZ());
                    stmt.executeUpdate();
                }
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error removing custom block marker: " + e.getMessage());
        }
    }

    /**
     * Check if a block is one of our custom network blocks
     */
    private boolean isCustomNetworkBlock(Block block) {
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block) || isCustomExporter(block) || isCustomImporter(block) || isCustomNetworkCable(block);
    }

    /**
     * Check if a block is a custom storage server
     */
    private boolean isCustomStorageServer(Block block) {
        if (block.getType() != org.bukkit.Material.CHISELED_TUFF) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "STORAGE_SERVER");
    }

    /**
     * Check if a block is a custom drive bay
     */
    private boolean isCustomDriveBay(Block block) {
        if (block.getType() != org.bukkit.Material.CHISELED_TUFF_BRICKS) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "DRIVE_BAY");
    }

    /**
     * Check if a block is a custom MSS terminal
     */
    private boolean isCustomMSSTerminal(Block block) {
        if (block.getType() != org.bukkit.Material.CRAFTER) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "MSS_TERMINAL");
    }

    /**
     * Check if a block is a custom exporter
     */
    private boolean isCustomExporter(Block block) {
        if (block.getType() != org.bukkit.Material.PLAYER_HEAD && block.getType() != org.bukkit.Material.PLAYER_WALL_HEAD) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "EXPORTER");
    }

    private boolean isCustomImporter(Block block) {
        if (block.getType() != org.bukkit.Material.PLAYER_HEAD && block.getType() != org.bukkit.Material.PLAYER_WALL_HEAD) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "IMPORTER");
    }

    /**
     * Check if a block is a custom network cable
     */
    private boolean isCustomNetworkCable(Block block) {
        if (block.getType() != org.bukkit.Material.HEAVY_CORE) return false;
        return plugin.getCableManager().isCustomNetworkCable(block);
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
     * Get a list of adjacent locations (face-adjacent only)
     */
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

    /**
     * Get the appropriate custom item for a destroyed block
     */
    private ItemStack getCustomItemForBlock(Block block) {
        if (isCustomStorageServer(block)) {
            return plugin.getItemManager().createStorageServer();
        } else if (isCustomDriveBay(block)) {
            return plugin.getItemManager().createDriveBay();
        } else if (isCustomMSSTerminal(block)) {
            return plugin.getItemManager().createMSSTerminal();
        } else if (isCustomExporter(block)) {
            return plugin.getItemManager().createExporter();
        } else if (isCustomImporter(block)) {
            return plugin.getItemManager().createImporter();
        } else if (isCustomNetworkCable(block)) {
            return plugin.getItemManager().createNetworkCable();
        }
        return null;
    }

    /**
     * Get the block type string for a destroyed block
     */
    private String getBlockTypeFromBlock(Block block) {
        if (isCustomStorageServer(block)) return "STORAGE_SERVER";
        if (isCustomDriveBay(block)) return "DRIVE_BAY";
        if (isCustomMSSTerminal(block)) return "MSS_TERMINAL";
        if (isCustomExporter(block)) return "EXPORTER";
        if (isCustomImporter(block)) return "IMPORTER";
        return "UNKNOWN";
    }
}