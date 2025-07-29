package org.jamesphbennett.massstorageserver.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.managers.ItemManager;
import org.jamesphbennett.massstorageserver.network.NetworkManager;
import org.jamesphbennett.massstorageserver.network.NetworkInfo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.bukkit.ChatColor.*;

public class BlockListener implements Listener {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;
    private final NetworkManager networkManager;

    public BlockListener(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.networkManager = plugin.getNetworkManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        Block block = event.getBlockPlaced();
        Location location = block.getLocation();

        // Prevent storage disks from being placed as blocks
        if (itemManager.isStorageDisk(item)) {
            event.setCancelled(true);
            return;
        }

        // Check if it's one of our CUSTOM network blocks (not just vanilla blocks)
        if (!itemManager.isNetworkBlock(item)) {
            return;
        }

        // Validate placement permissions
        if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
            event.setCancelled(true);
            player.sendMessage(RED + "You don't have permission to place Mass Storage blocks.");
            return;
        }

        // Mark this location as containing our custom block in the database (SYNCHRONOUSLY)
        try {
            markLocationAsCustomBlock(location, getBlockTypeFromItem(item));
        } catch (Exception e) {
            plugin.getLogger().severe("Error marking custom block location: " + e.getMessage());
            // If we can't mark the block, cancel the placement
            event.setCancelled(true);
            player.sendMessage(RED + "Error placing block: " + e.getMessage());
            return;
        }

        // Schedule network detection for next tick (after block is placed)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                NetworkInfo network = networkManager.detectNetwork(location);

                if (network != null && network.isValid()) {
                    // Check network size limit
                    int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                    if (network.getAllBlocks().size() > maxBlocks) {
                        // Network exceeds size limit - break the block and give it back
                        block.setType(Material.AIR);
                        removeCustomBlockMarker(location);
                        block.getWorld().dropItemNaturally(location, item);
                        player.sendMessage(RED + "Network size limit exceeded! Maximum " + maxBlocks + " blocks per network.");
                        return;
                    }

                    // ENHANCED: Register the network with comprehensive drive bay restoration
                    networkManager.registerNetwork(network, player.getUniqueId());

                    // ENHANCED: Check if any drive bay contents were restored
                    boolean hasRestoredContent = checkForRestoredContent(network.getDriveBays());

                    player.sendMessage(GREEN + "Mass Storage Network formed successfully!");
                    player.sendMessage(GRAY + "Network ID: " + network.getNetworkId());
                    player.sendMessage(GRAY + "Network Size: " + network.getAllBlocks().size() + "/" + maxBlocks + " blocks");

                    if (hasRestoredContent) {
                        player.sendMessage(AQUA + "Restored drive bay contents from previous network!");
                        player.sendMessage(YELLOW + "Check your terminals to see restored items.");
                    }
                } else {
                    // Check if this block connects to an existing network
                    for (Location adjacent : getAdjacentLocations(location)) {
                        String existingNetworkId = networkManager.getNetworkId(adjacent);
                        if (existingNetworkId != null) {
                            // Re-detect the expanded network
                            NetworkInfo expandedNetwork = networkManager.detectNetwork(adjacent);
                            if (expandedNetwork != null && expandedNetwork.isValid()) {
                                // Check network size limit
                                int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                                if (expandedNetwork.getAllBlocks().size() > maxBlocks) {
                                    // Network exceeds size limit - break the block and give it back
                                    block.setType(Material.AIR);
                                    removeCustomBlockMarker(location);
                                    block.getWorld().dropItemNaturally(location, item);
                                    player.sendMessage(RED + "Network size limit exceeded! Maximum " + maxBlocks + " blocks per network.");
                                    return;
                                }

                                // ENHANCED: Register the expanded network with restoration
                                networkManager.registerNetwork(expandedNetwork, player.getUniqueId());

                                // ENHANCED: Check if any drive bay contents were restored
                                boolean hasRestoredContent = checkForRestoredContent(expandedNetwork.getDriveBays());

                                player.sendMessage(GREEN + "Block added to existing network!");
                                player.sendMessage(GRAY + "Network Size: " + expandedNetwork.getAllBlocks().size() + "/" + maxBlocks + " blocks");

                                if (hasRestoredContent) {
                                    player.sendMessage(AQUA + "Restored drive bay contents!");
                                    player.sendMessage(YELLOW + "Check your terminals to see restored items.");
                                }
                                return;
                            }
                        }
                    }

                    if (itemManager.isStorageServer(item)) {
                        player.sendMessage(YELLOW + "Storage Server requires Drive Bays and Terminals to form a network.");
                    } else {
                        player.sendMessage(YELLOW + "This block needs to be connected to a Storage Server to function.");
                    }
                }

            } catch (Exception e) {
                player.sendMessage(RED + "Error setting up network: " + e.getMessage());
                plugin.getLogger().severe("Error setting up network: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location location = block.getLocation();

        // Check if it's one of our CUSTOM network blocks (not just vanilla blocks)
        if (!isCustomNetworkBlock(block)) {
            return;
        }

        try {
            String networkId = networkManager.getNetworkId(location);

            // ENHANCED: Always drop drive bay contents, whether part of network or not
            if (isCustomDriveBay(block)) {
                if (networkId != null) {
                    // Drive bay is part of a network - use network-aware dropping
                    dropDriveBayContents(location, networkId);
                } else {
                    // Drive bay is not part of a network - check for orphaned/standalone contents
                    plugin.getLogger().info("Drive bay at " + location + " is not part of a network, checking for standalone contents");
                    dropDriveBayContentsWithoutNetwork(location);
                }
            }

            // Always drop the custom item and prevent vanilla drops
            event.setDropItems(false);
            ItemStack customItem = getCustomItemForBlock(block);
            if (customItem != null) {
                block.getWorld().dropItemNaturally(location, customItem);
            }

            // Remove custom block marker
            removeCustomBlockMarker(location);

            // Update the network after the block is broken (if it was part of a network)
            if (networkId != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        // Try to re-detect the network from remaining blocks
                        boolean networkStillValid = false;

                        for (Location adjacent : getAdjacentLocations(location)) {
                            if (isCustomNetworkBlock(adjacent.getBlock())) {
                                NetworkInfo updatedNetwork = networkManager.detectNetwork(adjacent);
                                if (updatedNetwork != null && updatedNetwork.isValid()) {
                                    networkManager.registerNetwork(updatedNetwork, player.getUniqueId());
                                    networkStillValid = true;
                                    player.sendMessage(YELLOW + "Network updated after block removal.");
                                    int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                                    player.sendMessage(GRAY + "Network Size: " + updatedNetwork.getAllBlocks().size() + "/" + maxBlocks + " blocks");
                                    break;
                                }
                            }
                        }

                        if (!networkStillValid) {
                            // Network is no longer valid, unregister it
                            networkManager.unregisterNetwork(networkId);
                            player.sendMessage(RED + "Mass Storage Network dissolved.");
                        }

                    } catch (Exception e) {
                        player.sendMessage(RED + "Error updating network: " + e.getMessage());
                        plugin.getLogger().severe("Error updating network: " + e.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            player.sendMessage(RED + "Error handling block break: " + e.getMessage());
            plugin.getLogger().severe("Error handling block break: " + e.getMessage());
        }
    }

    /**
     * Handle entity explosions (creepers, TNT, etc.) that destroy MSS blocks
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.isCancelled()) return;

        handleExplosion(event.blockList(), event.getLocation());
    }

    /**
     * Handle block explosions (beds, respawn anchors, etc.) that destroy MSS blocks
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.isCancelled()) return;

        handleExplosion(event.blockList(), event.getBlock().getLocation());
    }

    /**
     * Common explosion handling for both entity and block explosions
     */
    private void handleExplosion(List<Block> blockList, Location explosionLocation) {
        List<Block> customBlocksToHandle = new ArrayList<>();
        List<Location> driveBayLocations = new ArrayList<>();

        // First pass: identify our custom blocks in the explosion
        for (Block block : blockList) {
            if (isCustomNetworkBlock(block)) {
                customBlocksToHandle.add(block);

                if (isCustomDriveBay(block)) {
                    driveBayLocations.add(block.getLocation());
                }
            }
        }

        if (customBlocksToHandle.isEmpty()) {
            return; // No MSS blocks affected
        }

        plugin.getLogger().info("Explosion at " + explosionLocation + " affecting " + customBlocksToHandle.size() +
                " MSS blocks (" + driveBayLocations.size() + " drive bays)");

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
                    dropDriveBayContents(driveBayLoc, networkId);
                } else {
                    plugin.getLogger().info("Drive bay at " + driveBayLoc + " has no network association, checking for orphaned contents");
                    dropDriveBayContentsWithoutNetwork(driveBayLoc);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error handling drive bay explosion at " + driveBayLoc + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Remove custom block markers and drop custom items
        Iterator<Block> iterator = blockList.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();

            if (isCustomNetworkBlock(block)) {
                try {
                    // Remove from explosion list so vanilla block isn't dropped
                    iterator.remove();

                    // Drop our custom item instead
                    ItemStack customItem = getCustomItemForBlock(block);
                    if (customItem != null) {
                        block.getWorld().dropItemNaturally(block.getLocation(), customItem);
                        plugin.getLogger().info("Dropped custom item for " + getBlockTypeFromBlock(block) + " at " + block.getLocation());
                    }

                    // Remove custom block marker
                    removeCustomBlockMarker(block.getLocation());

                } catch (Exception e) {
                    plugin.getLogger().severe("Error handling custom block explosion: " + e.getMessage());
                }
            }
        }

        // Schedule network updates after explosion
        if (!customBlocksToHandle.isEmpty()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                updateNetworksAfterExplosion(customBlocksToHandle);
            });
        }
    }

    /**
     * Find network ID for a location by checking adjacent blocks
     */
    private String findNetworkIdForLocation(Location location) {
        try {
            return networkManager.getNetworkId(location);
        } catch (Exception e) {
            plugin.getLogger().warning("Error finding network ID for location " + location + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Find network ID for a drive bay from the database
     */
    private String findDriveBayNetworkIdFromDatabase(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT DISTINCT network_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? LIMIT 1")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("network_id");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error finding drive bay network ID from database: " + e.getMessage());
        }
        return null;
    }

    /**
     * Drop drive bay contents when network association is unknown
     */
    private void dropDriveBayContentsWithoutNetwork(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id IS NOT NULL")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            List<String> diskIds = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    diskIds.add(rs.getString("disk_id"));
                }
            }

            plugin.getLogger().info("Found " + diskIds.size() + " disks to drop from networkless drive bay");

            // Drop each disk and remove from database
            for (String diskId : diskIds) {
                // Get disk info for recreation
                try (PreparedStatement diskStmt = conn.prepareStatement(
                        "SELECT crafter_uuid, crafter_name, used_cells, max_cells FROM storage_disks WHERE disk_id = ?")) {
                    diskStmt.setString(1, diskId);

                    try (ResultSet diskRs = diskStmt.executeQuery()) {
                        if (diskRs.next()) {
                            String crafterUUID = diskRs.getString("crafter_uuid");
                            String crafterName = diskRs.getString("crafter_name");
                            int usedCells = diskRs.getInt("used_cells");
                            int maxCells = diskRs.getInt("max_cells");

                            // Create disk item with correct ID
                            ItemStack disk = itemManager.createStorageDiskWithId(diskId, crafterUUID, crafterName);
                            disk = itemManager.updateStorageDiskLore(disk, usedCells, maxCells);

                            // Drop the disk
                            location.getWorld().dropItemNaturally(location, disk);
                            plugin.getLogger().info("Dropped disk " + diskId + " with " + usedCells + "/" + maxCells + " cells used");
                        }
                    }
                }

                // Remove from drive bay slots
                try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id = ?")) {
                    deleteStmt.setString(1, location.getWorld().getName());
                    deleteStmt.setInt(2, location.getBlockX());
                    deleteStmt.setInt(3, location.getBlockY());
                    deleteStmt.setInt(4, location.getBlockZ());
                    deleteStmt.setString(5, diskId);
                    deleteStmt.executeUpdate();
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error dropping networkless drive bay contents: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Update networks after explosion damage
     */
    private void updateNetworksAfterExplosion(List<Block> destroyedBlocks) {
        Set<String> affectedNetworks = new HashSet<>();

        // Find all networks that might be affected
        for (Block block : destroyedBlocks) {
            for (Location adjacent : getAdjacentLocations(block.getLocation())) {
                if (isCustomNetworkBlock(adjacent.getBlock())) {
                    try {
                        String networkId = networkManager.getNetworkId(adjacent);
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
                                NetworkInfo updatedNetwork = networkManager.detectNetwork(testLocation);
                                if (updatedNetwork != null && updatedNetwork.isValid()) {
                                    networkManager.registerNetwork(updatedNetwork, null); // No player for explosions
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
                    networkManager.unregisterNetwork(networkId);
                    plugin.getLogger().info("Network " + networkId + " dissolved due to explosion");
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Error updating network " + networkId + " after explosion: " + e.getMessage());
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        // Only handle RIGHT CLICK events
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        // IMPORTANT: Check if player is awaiting search input and cancel interaction
        if (plugin.getGUIManager().isAwaitingSearchInput(player)) {
            // Player is in search mode, don't open any GUIs
            return;
        }

        // Handle MSS Terminal interactions (only custom ones)
        if (isCustomMSSTerminal(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
                player.sendMessage(RED + "You don't have permission to use Mass Storage terminals.");
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());

                if (networkId == null) {
                    player.sendMessage(RED + "This terminal is not connected to a valid network.");
                    return;
                }

                // Check cooldown
                if (!plugin.getCooldownManager().canOperate(player.getUniqueId(), networkId)) {
                    long remaining = plugin.getCooldownManager().getRemainingCooldown(player.getUniqueId(), networkId);
                    player.sendMessage(YELLOW + "Please wait " + remaining + "ms before using the network again.");
                    return;
                }

                // Open terminal GUI
                plugin.getGUIManager().openTerminalGUI(player, block.getLocation(), networkId);

                // Record operation
                plugin.getCooldownManager().recordOperation(player.getUniqueId(), networkId);

            } catch (Exception e) {
                player.sendMessage(RED + "Error accessing terminal: " + e.getMessage());
                plugin.getLogger().severe("Error accessing terminal: " + e.getMessage());
            }
        }

        // Handle Drive Bay interactions (only custom ones)
        else if (isCustomDriveBay(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
                player.sendMessage(RED + "You don't have permission to use Drive Bays.");
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());

                // UPDATED: Allow Drive Bay access even without a valid network
                if (networkId == null) {
                    // Try to find any network ID associated with this location in the database
                    networkId = findDriveBayNetworkId(block.getLocation());

                    if (networkId == null) {
                        // Generate a temporary network ID for standalone drive bay access
                        networkId = "standalone_" + block.getLocation().getWorld().getName() + "_" +
                                block.getLocation().getBlockX() + "_" +
                                block.getLocation().getBlockY() + "_" +
                                block.getLocation().getBlockZ();

                        player.sendMessage(YELLOW + "Opening standalone drive bay (not connected to a network).");
                    } else {
                        player.sendMessage(YELLOW + "Opening drive bay (network connection lost).");
                    }
                } else if (!networkManager.isNetworkValid(networkId)) {
                    player.sendMessage(YELLOW + "Opening drive bay (network is no longer valid).");
                }

                // Open drive bay GUI regardless of network validity
                plugin.getGUIManager().openDriveBayGUI(player, block.getLocation(), networkId);

            } catch (Exception e) {
                player.sendMessage(RED + "Error accessing drive bay: " + e.getMessage());
                plugin.getLogger().severe("Error accessing drive bay: " + e.getMessage());
            }
        }

    }

    /**
     * Check if any drive bays at the given locations have restored content
     */
    private boolean checkForRestoredContent(Set<Location> driveBayLocations) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            for (Location location : driveBayLocations) {
                // Check if this drive bay has any disks with stored items
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM drive_bay_slots dbs " +
                                "JOIN storage_disks sd ON dbs.disk_id = sd.disk_id " +
                                "JOIN storage_items si ON sd.disk_id = si.disk_id " +
                                "WHERE dbs.world_name = ? AND dbs.x = ? AND dbs.y = ? AND dbs.z = ? " +
                                "AND dbs.disk_id IS NOT NULL")) {

                    stmt.setString(1, location.getWorld().getName());
                    stmt.setInt(2, location.getBlockX());
                    stmt.setInt(3, location.getBlockY());
                    stmt.setInt(4, location.getBlockZ());

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            return true; // Found stored items in this drive bay
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking for restored content: " + e.getMessage());
        }
        return false;
    }

    // Helper methods to check if blocks are OUR custom blocks
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

    private void markLocationAsCustomBlock(Location location, String blockType) {
        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO custom_block_markers (world_name, x, y, z, block_type) VALUES (?, ?, ?, ?, ?)")) {
                    stmt.setString(1, location.getWorld().getName());
                    stmt.setInt(2, location.getBlockX());
                    stmt.setInt(3, location.getBlockY());
                    stmt.setInt(4, location.getBlockZ());
                    stmt.setString(5, blockType);
                    stmt.executeUpdate();
                }
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error marking custom block: " + e.getMessage());
        }
    }

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
     * Helper method to find network ID associated with a drive bay location from database
     */
    private String findDriveBayNetworkId(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {

            // First try to find an active network ID
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT DISTINCT network_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id NOT LIKE 'orphaned_%' LIMIT 1")) {

                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("network_id");
                    }
                }
            }

            // If no active network found, look for orphaned drive bay slots
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT DISTINCT network_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id LIKE 'orphaned_%' LIMIT 1")) {

                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String orphanedNetworkId = rs.getString("network_id");
                        plugin.getLogger().info("Found orphaned drive bay slots with network ID: " + orphanedNetworkId);
                        return orphanedNetworkId;
                    }
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error finding drive bay network ID: " + e.getMessage());
        }
        return null;
    }

    private String getBlockTypeFromItem(ItemStack item) {
        if (itemManager.isStorageServer(item)) return "STORAGE_SERVER";
        if (itemManager.isDriveBay(item)) return "DRIVE_BAY";
        if (itemManager.isMSSTerminal(item)) return "MSS_TERMINAL";
        return "UNKNOWN";
    }

    private String getBlockTypeFromBlock(Block block) {
        if (isCustomStorageServer(block)) return "STORAGE_SERVER";
        if (isCustomDriveBay(block)) return "DRIVE_BAY";
        if (isCustomMSSTerminal(block)) return "MSS_TERMINAL";
        return "UNKNOWN";
    }

    private void dropDriveBayContents(Location location, String networkId) {
        plugin.getLogger().info("Dropping drive bay contents at " + location + " for network " + networkId);

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id IS NOT NULL")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            List<String> diskIds = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    diskIds.add(rs.getString("disk_id"));
                }
            }

            plugin.getLogger().info("Found " + diskIds.size() + " disks to drop from drive bay");

            // Drop each disk and remove from database
            for (String diskId : diskIds) {
                // Get disk info for recreation
                try (PreparedStatement diskStmt = conn.prepareStatement(
                        "SELECT crafter_uuid, crafter_name, used_cells, max_cells FROM storage_disks WHERE disk_id = ?")) {
                    diskStmt.setString(1, diskId);

                    try (ResultSet diskRs = diskStmt.executeQuery()) {
                        if (diskRs.next()) {
                            String crafterUUID = diskRs.getString("crafter_uuid");
                            String crafterName = diskRs.getString("crafter_name");
                            int usedCells = diskRs.getInt("used_cells");
                            int maxCells = diskRs.getInt("max_cells");

                            // Create disk item with correct ID
                            ItemStack disk = itemManager.createStorageDiskWithId(diskId, crafterUUID, crafterName);
                            disk = itemManager.updateStorageDiskLore(disk, usedCells, maxCells);

                            // Drop the disk
                            location.getWorld().dropItemNaturally(location, disk);
                            plugin.getLogger().info("Dropped disk " + diskId + " with " + usedCells + "/" + maxCells + " cells used");
                        }
                    }
                }

                // Remove from drive bay slots (but keep disk data in storage_disks and storage_items)
                try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id = ?")) {
                    deleteStmt.setString(1, location.getWorld().getName());
                    deleteStmt.setInt(2, location.getBlockX());
                    deleteStmt.setInt(3, location.getBlockY());
                    deleteStmt.setInt(4, location.getBlockZ());
                    deleteStmt.setString(5, diskId);
                    deleteStmt.executeUpdate();
                }
            }

            // CRITICAL: Refresh all terminals in the network after drive bay destruction
            if (!diskIds.isEmpty()) {
                plugin.getGUIManager().refreshNetworkTerminals(networkId);
                plugin.getLogger().info("Refreshed terminals after drive bay destruction containing " + diskIds.size() + " disks");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error dropping drive bay contents: " + e.getMessage());
            e.printStackTrace();
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

    private ItemStack getCustomItemForBlock(Block block) {
        if (isCustomStorageServer(block)) {
            return itemManager.createStorageServer();
        } else if (isCustomDriveBay(block)) {
            return itemManager.createDriveBay();
        } else if (isCustomMSSTerminal(block)) {
            return itemManager.createMSSTerminal();
        }
        return null;
    }
}