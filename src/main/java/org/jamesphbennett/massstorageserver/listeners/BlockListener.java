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
import java.util.List;

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

                    // Register the network
                    networkManager.registerNetwork(network, player.getUniqueId());

                    player.sendMessage(GREEN + "Mass Storage Network formed successfully!");
                    player.sendMessage(GRAY + "Network ID: " + network.getNetworkId());
                    player.sendMessage(GRAY + "Network Size: " + network.getAllBlocks().size() + "/" + maxBlocks + " blocks");
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

                                networkManager.registerNetwork(expandedNetwork, player.getUniqueId());
                                player.sendMessage(GREEN + "Block added to existing network!");
                                player.sendMessage(GRAY + "Network Size: " + expandedNetwork.getAllBlocks().size() + "/" + maxBlocks + " blocks");
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

            if (networkId != null) {
                // If breaking a drive bay, drop all storage disks
                if (isCustomDriveBay(block)) {
                    dropDriveBayContents(location, networkId);
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

                if (networkId == null) {
                    player.sendMessage(RED + "This drive bay is not connected to a valid network.");
                    return;
                }

                // Open drive bay GUI
                plugin.getGUIManager().openDriveBayGUI(player, block.getLocation(), networkId);

            } catch (Exception e) {
                player.sendMessage(RED + "Error accessing drive bay: " + e.getMessage());
                plugin.getLogger().severe("Error accessing drive bay: " + e.getMessage());
            }
        }
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

    private String getBlockTypeFromItem(ItemStack item) {
        if (itemManager.isStorageServer(item)) return "STORAGE_SERVER";
        if (itemManager.isDriveBay(item)) return "DRIVE_BAY";
        if (itemManager.isMSSTerminal(item)) return "MSS_TERMINAL";
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