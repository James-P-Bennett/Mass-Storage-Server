package org.jamesphbennett.massstorageserver.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
import org.jamesphbennett.massstorageserver.managers.ExporterManager;
import org.jamesphbennett.massstorageserver.network.NetworkManager;
import org.jamesphbennett.massstorageserver.network.NetworkInfo;
import org.jamesphbennett.massstorageserver.network.CableManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class BlockListener implements Listener {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;
    private final NetworkManager networkManager;
    private final CableManager cableManager;

    public BlockListener(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.networkManager = plugin.getNetworkManager();
        this.cableManager = plugin.getCableManager();
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
            player.sendMessage(Component.text("Storage disks cannot be placed as blocks!", NamedTextColor.RED));
            return;
        }

        // Prevent MSS components from being placed as blocks
        if (itemManager.isMSSComponent(item)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("Mass Storage components cannot be placed as blocks!", NamedTextColor.RED));
            return;
        }

        // Check if it's one of our CUSTOM network blocks, cables, or exporters
        if (!itemManager.isNetworkBlock(item) && !itemManager.isNetworkCable(item) && !itemManager.isExporter(item)) {
            return;
        }

        // Validate placement permissions
        if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
            event.setCancelled(true);
            player.sendMessage(Component.text("You don't have permission to place Mass Storage blocks.", NamedTextColor.RED));
            return;
        }

        // Handle exporter placement
        if (itemManager.isExporter(item)) {
            // Check if there's a valid network nearby to connect to
            String nearbyNetworkId = null;
            for (Location adjacent : getAdjacentLocations(location)) {
                if (isCustomNetworkBlock(adjacent.getBlock()) || cableManager.isCustomNetworkCable(adjacent.getBlock())) {
                    nearbyNetworkId = networkManager.getNetworkId(adjacent);
                    if (nearbyNetworkId != null) {
                        break;
                    }
                }
            }

            if (nearbyNetworkId == null) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Exporters must be placed adjacent to a network!", NamedTextColor.RED));
                return;
            }

            // Mark as custom block
            try {
                markLocationAsCustomBlock(location, "EXPORTER");

                // Create the exporter in the manager
                String exporterId = plugin.getExporterManager().createExporter(location, nearbyNetworkId, player);
                player.sendMessage(Component.text("Exporter created successfully! Right-click to configure.", NamedTextColor.GREEN));
                plugin.getLogger().info("Created exporter " + exporterId + " for player " + player.getName() + " at " + location);

            } catch (Exception e) {
                player.sendMessage(Component.text("Error creating exporter: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error creating exporter: " + e.getMessage());
                event.setCancelled(true);
                return;
            }
            return; // Don't continue to network detection for exporters
        }

        // Handle network cable placement
        if (itemManager.isNetworkCable(item)) {
            if (!cableManager.handleCablePlacement(player, location)) {
                event.setCancelled(true);
                return;
            }
            // Cable placement successful, continue to network detection
        } else {
            // Handle network block placement with linking prevention
            String blockType = getBlockTypeFromItem(item);

            // Check for network linking conflicts
            String linkingConflict = cableManager.checkNetworkLinkingConflict(location);
            if (linkingConflict != null) {
                event.setCancelled(true);
                player.sendMessage(Component.text(linkingConflict, NamedTextColor.RED));
                return;
            }

            // Check for storage server conflicts
            String serverConflict = cableManager.checkStorageServerConflict(location, blockType);
            if (serverConflict != null) {
                event.setCancelled(true);
                player.sendMessage(Component.text(serverConflict, NamedTextColor.RED));
                return;
            }

            // Mark this location as containing our custom block in the database (SYNCHRONOUSLY)
            try {
                markLocationAsCustomBlock(location, blockType);
            } catch (Exception e) {
                plugin.getLogger().severe("Error marking custom block location: " + e.getMessage());
                // If we can't mark the block, cancel the placement
                event.setCancelled(true);
                player.sendMessage(Component.text("Error placing block: " + e.getMessage(), NamedTextColor.RED));
                return;
            }
        }

        // Schedule network detection for next tick (after block is placed)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                NetworkInfo network = networkManager.detectNetwork(location);

                if (network != null && network.isValid()) {
                    // Check network size limit (blocks only, not cables)
                    int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                    int networkBlockCount = network.getAllBlocks().size() - network.getNetworkCables().size();

                    if (networkBlockCount > maxBlocks) {
                        // Network exceeds size limit - break the block and give it back
                        block.setType(Material.AIR);
                        removeCustomBlockMarker(location);
                        block.getWorld().dropItemNaturally(location, item);
                        player.sendMessage(Component.text("Network size limit exceeded! Maximum " + maxBlocks + " blocks per network.", NamedTextColor.RED));
                        return;
                    }

                    // ENHANCED: Register the network with comprehensive drive bay restoration
                    networkManager.registerNetwork(network, player.getUniqueId());

                    // ENHANCED: Check if any drive bay contents were restored
                    boolean hasRestoredContent = plugin.getDisksManager().checkForRestoredContent(network.getDriveBays());

                    player.sendMessage(Component.text("Mass Storage Network formed successfully!", NamedTextColor.GREEN));
                    player.sendMessage(Component.text("Network ID: " + network.getNetworkId(), NamedTextColor.GRAY));
                    player.sendMessage(Component.text("Network Size: " + networkBlockCount + "/" + maxBlocks + " blocks, " +
                            network.getNetworkCables().size() + "/" + plugin.getConfigManager().getMaxNetworkCables() + " cables", NamedTextColor.GRAY));

                    if (hasRestoredContent) {
                        player.sendMessage(Component.text("Restored drive bay contents from previous network!", NamedTextColor.AQUA));
                        player.sendMessage(Component.text("Check your terminals to see restored items.", NamedTextColor.YELLOW));
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
                                int networkBlockCount = expandedNetwork.getAllBlocks().size() - expandedNetwork.getNetworkCables().size();

                                if (networkBlockCount > maxBlocks) {
                                    // Network exceeds size limit - break the block and give it back
                                    block.setType(Material.AIR);
                                    removeCustomBlockMarker(location);
                                    block.getWorld().dropItemNaturally(location, item);
                                    player.sendMessage(Component.text("Network size limit exceeded! Maximum " + maxBlocks + " blocks per network.", NamedTextColor.RED));
                                    return;
                                }

                                // ENHANCED: Register the expanded network with restoration
                                networkManager.registerNetwork(expandedNetwork, player.getUniqueId());

                                // ENHANCED: Check if any drive bay contents were restored
                                boolean hasRestoredContent = plugin.getDisksManager().checkForRestoredContent(expandedNetwork.getDriveBays());

                                player.sendMessage(Component.text("Block added to existing network!", NamedTextColor.GREEN));
                                player.sendMessage(Component.text("Network Size: " + networkBlockCount + "/" + maxBlocks + " blocks, " +
                                        expandedNetwork.getNetworkCables().size() + "/" + plugin.getConfigManager().getMaxNetworkCables() + " cables", NamedTextColor.GRAY));

                                if (hasRestoredContent) {
                                    player.sendMessage(Component.text("Restored drive bay contents!", NamedTextColor.AQUA));
                                    player.sendMessage(Component.text("Check your terminals to see restored items.", NamedTextColor.YELLOW));
                                }
                                return;
                            }
                        }
                    }

                    if (itemManager.isStorageServer(item)) {
                        player.sendMessage(Component.text("Storage Server requires Drive Bays and Terminals to form a network.", NamedTextColor.YELLOW));
                    } else if (itemManager.isNetworkCable(item)) {
                        player.sendMessage(Component.text("Cable placed. Connect to network blocks to extend your network.", NamedTextColor.YELLOW));
                    } else {
                        player.sendMessage(Component.text("This block needs to be connected to a Storage Server to function.", NamedTextColor.YELLOW));
                    }
                }

            } catch (Exception e) {
                player.sendMessage(Component.text("Error setting up network: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error setting up network: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location location = block.getLocation();

        // Check if it's one of our CUSTOM network blocks, cables, or exporters
        if (!isCustomNetworkBlockOrCableOrExporter(block)) {
            return;
        }

        try {
            String networkId = networkManager.getNetworkId(location);

            // Handle exporter removal
            if (isCustomExporter(block)) {
                try {
                    // Get the exporter data before removing
                    ExporterManager.ExporterData exporterData = plugin.getExporterManager().getExporterAtLocation(location);
                    if (exporterData != null) {
                        // Remove from exporter manager
                        plugin.getExporterManager().removeExporter(exporterData.exporterId);
                        plugin.getLogger().info("Removed exporter " + exporterData.exporterId + " at " + location);
                    }

                    // Drop the custom item
                    event.setDropItems(false);
                    ItemStack customItem = plugin.getItemManager().createExporter();
                    if (customItem != null) {
                        block.getWorld().dropItemNaturally(location, customItem);
                    }

                    // Remove custom block marker
                    removeCustomBlockMarker(location);

                } catch (Exception e) {
                    player.sendMessage(Component.text("Error removing exporter: " + e.getMessage(), NamedTextColor.RED));
                    plugin.getLogger().severe("Error removing exporter: " + e.getMessage());
                }
                return; // Exporters don't affect network topology, so return early
            }

            // ENHANCED: Always drop drive bay contents, whether part of network or not
            if (isCustomDriveBay(block)) {
                if (networkId != null) {
                    // Drive bay is part of a network - use network-aware dropping
                    plugin.getDisksManager().dropDriveBayContents(location, networkId);
                } else {
                    // Drive bay is not part of a network - check for orphaned/standalone contents
                    plugin.getLogger().info("Drive bay at " + location + " is not part of a network, checking for standalone contents");
                    plugin.getDisksManager().dropDriveBayContentsWithoutNetwork(location);
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
                            if (isCustomNetworkBlockOrCable(adjacent.getBlock())) {
                                NetworkInfo updatedNetwork = networkManager.detectNetwork(adjacent);
                                if (updatedNetwork != null && updatedNetwork.isValid()) {
                                    networkManager.registerNetwork(updatedNetwork, player.getUniqueId());
                                    networkStillValid = true;
                                    player.sendMessage(Component.text("Network updated after block removal.", NamedTextColor.YELLOW));
                                    int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                                    int networkBlockCount = updatedNetwork.getAllBlocks().size() - updatedNetwork.getNetworkCables().size();
                                    player.sendMessage(Component.text("Network Size: " + networkBlockCount + "/" + maxBlocks + " blocks, " +
                                            updatedNetwork.getNetworkCables().size() + "/" + plugin.getConfigManager().getMaxNetworkCables() + " cables", NamedTextColor.GRAY));
                                    break;
                                }
                            }
                        }

                        if (!networkStillValid) {
                            // Network is no longer valid, unregister it
                            networkManager.unregisterNetwork(networkId);
                            player.sendMessage(Component.text("Mass Storage Network dissolved.", NamedTextColor.RED));
                        }

                    } catch (Exception e) {
                        player.sendMessage(Component.text("Error updating network: " + e.getMessage(), NamedTextColor.RED));
                        plugin.getLogger().severe("Error updating network: " + e.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            player.sendMessage(Component.text("Error handling block break: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error handling block break: " + e.getMessage());
        }
    }

    /**
     * Display storage server status information to a player
     */
    private void displayStorageServerStatus(Player player, String networkId) {
        // Add debug logging
        plugin.getLogger().info("Displaying storage server status for " + player.getName() + " with network ID: " + networkId);

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Get network information
            String ownerUUID = null;
            String lastAccessed = null;

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT owner_uuid, last_accessed FROM networks WHERE network_id = ?")) {
                stmt.setString(1, networkId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        ownerUUID = rs.getString("owner_uuid");
                        lastAccessed = rs.getString("last_accessed");
                    }
                }
            }

            // Get network block count
            int blockCount = 0;
            int cableCount = 0;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT block_type, COUNT(*) as count FROM network_blocks WHERE network_id = ? GROUP BY block_type")) {
                stmt.setString(1, networkId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String blockType = rs.getString("block_type");
                        int count = rs.getInt("count");
                        if ("NETWORK_CABLE".equals(blockType)) {
                            cableCount = count;
                        } else {
                            blockCount += count;
                        }
                    }
                }
            }

            // Get drive bay and terminal counts
            int driveBayCount = 0;
            int terminalCount = 0;
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT block_type, COUNT(*) as count FROM network_blocks WHERE network_id = ? AND block_type IN ('DRIVE_BAY', 'MSS_TERMINAL') GROUP BY block_type")) {
                stmt.setString(1, networkId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String blockType = rs.getString("block_type");
                        int count = rs.getInt("count");
                        if ("DRIVE_BAY".equals(blockType)) {
                            driveBayCount = count;
                        } else if ("MSS_TERMINAL".equals(blockType)) {
                            terminalCount = count;
                        }
                    }
                }
            }

            // Get storage disk count and total capacity
            int diskCount = 0;
            long totalItems = 0;
            int totalCells = 0;
            int usedCells = 0;

            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT COUNT(DISTINCT sd.disk_id) as disk_count, " +
                            "SUM(sd.max_cells) as total_cells, " +
                            "SUM(sd.used_cells) as used_cells, " +
                            "COALESCE(SUM(si.quantity), 0) as total_items " +
                            "FROM storage_disks sd " +
                            "LEFT JOIN storage_items si ON sd.disk_id = si.disk_id " +
                            "WHERE sd.network_id = ?")) {
                stmt.setString(1, networkId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        diskCount = rs.getInt("disk_count");
                        totalCells = rs.getInt("total_cells");
                        usedCells = rs.getInt("used_cells");
                        totalItems = rs.getLong("total_items");
                    }
                }
            }

            // Display the information
            player.sendMessage(Component.text("=== Storage Server Status ===", NamedTextColor.GOLD));
            player.sendMessage(Component.text("Network ID: " + networkId, NamedTextColor.GRAY));

            if (ownerUUID != null) {
                String ownerName = plugin.getServer().getOfflinePlayer(java.util.UUID.fromString(ownerUUID)).getName();
                player.sendMessage(Component.text("Owner: " + (ownerName != null ? ownerName : "Unknown"), NamedTextColor.GRAY));
            }

            player.sendMessage(Component.text("Network Size: " + blockCount + "/" + plugin.getConfigManager().getMaxNetworkBlocks() + " blocks", NamedTextColor.AQUA));
            player.sendMessage(Component.text("Network Cables: " + cableCount + "/" + plugin.getConfigManager().getMaxNetworkCables() + " cables", NamedTextColor.BLUE));
            player.sendMessage(Component.text("Drive Bays: " + driveBayCount, NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Terminals: " + terminalCount, NamedTextColor.GREEN));
            player.sendMessage(Component.text("Storage Disks: " + diskCount, NamedTextColor.LIGHT_PURPLE));

            if (diskCount > 0) {
                NamedTextColor storageColor = usedCells >= totalCells * 0.9 ? NamedTextColor.RED :
                        usedCells >= totalCells * 0.7 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
                player.sendMessage(Component.text("Storage Cells: " + usedCells + "/" + totalCells + " used", storageColor));
                player.sendMessage(Component.text("Total Items: " + String.format("%,d", totalItems), NamedTextColor.AQUA));
            }

            if (lastAccessed != null) {
                player.sendMessage(Component.text("Last Accessed: " + lastAccessed, NamedTextColor.DARK_GRAY));
            }

        } catch (Exception e) {
            player.sendMessage(Component.text("Error retrieving storage server status: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error retrieving storage server status: " + e.getMessage());
        }
    }

    /**
     * Handle entity explosions (creepers, TNT, etc.) that destroy MSS blocks
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.isCancelled()) return;
        plugin.getExplosionManager().handleExplosion(event.blockList(), event.getLocation());
    }

    /**
     * Handle block explosions (beds, respawn anchors, etc.) that destroy MSS blocks
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.isCancelled()) return;
        plugin.getExplosionManager().handleExplosion(event.blockList(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        // Only handle RIGHT CLICK events
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // CRITICAL: Only handle main hand interactions to prevent duplicate events
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        // CRITICAL: Check if player is awaiting search input and cancel ALL interactions
        if (plugin.getGUIManager().isAwaitingSearchInput(player)) {
            // Player is in search mode - cancel the search and the interaction
            event.setCancelled(true);
            plugin.getGUIManager().cancelSearchInput(player);
            player.sendMessage(Component.text("Search cancelled.", NamedTextColor.YELLOW));
            plugin.getLogger().info("Cancelled search input for player " + player.getName() + " due to block interaction");
            return;
        }

        // Handle Exporter interactions (only custom ones)
        if (isCustomExporter(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
                player.sendMessage(Component.text("You don't have permission to use Exporters.", NamedTextColor.RED));
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());
                if (networkId == null) {
                    player.sendMessage(Component.text("This exporter is not connected to a valid network.", NamedTextColor.RED));
                    return;
                }

                // Get the exporter from the manager
                ExporterManager.ExporterData exporterData = plugin.getExporterManager().getExporterAtLocation(block.getLocation());
                if (exporterData == null) {
                    player.sendMessage(Component.text("Exporter data not found. Try breaking and replacing the block.", NamedTextColor.RED));
                    return;
                }

                // Open exporter GUI
                plugin.getGUIManager().openExporterGUI(player, block.getLocation(), exporterData.exporterId, networkId);

            } catch (Exception e) {
                player.sendMessage(Component.text("Error accessing exporter: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error accessing exporter: " + e.getMessage());
            }
            return;
        }

        // Handle MSS Terminal interactions (only custom ones)
        if (isCustomMSSTerminal(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
                player.sendMessage(Component.text("You don't have permission to use Mass Storage terminals.", NamedTextColor.RED));
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());

                if (networkId == null) {
                    player.sendMessage(Component.text("This terminal is not connected to a valid network.", NamedTextColor.RED));
                    return;
                }

                // Check cooldown
                if (!plugin.getCooldownManager().canOperate(player.getUniqueId(), networkId)) {
                    long remaining = plugin.getCooldownManager().getRemainingCooldown(player.getUniqueId(), networkId);
                    player.sendMessage(Component.text("Please wait " + remaining + "ms before using the network again.", NamedTextColor.YELLOW));
                    return;
                }

                // Open terminal GUI
                plugin.getGUIManager().openTerminalGUI(player, block.getLocation(), networkId);

                // Record operation
                plugin.getCooldownManager().recordOperation(player.getUniqueId(), networkId);

            } catch (Exception e) {
                player.sendMessage(Component.text("Error accessing terminal: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error accessing terminal: " + e.getMessage());
            }
            return;
        }

        // Handle Storage Server interactions (only custom ones)
        if (isCustomStorageServer(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
                player.sendMessage(Component.text("You don't have permission to view Storage Server information.", NamedTextColor.RED));
                return;
            }

            // Add debug logging
            plugin.getLogger().info("Storage Server interaction by " + player.getName() + " at " + block.getLocation());

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());

                if (networkId == null) {
                    player.sendMessage(Component.text("This Storage Server is not part of a valid network.", NamedTextColor.RED));
                    return;
                }

                // Display network status information
                displayStorageServerStatus(player, networkId);

            } catch (Exception e) {
                player.sendMessage(Component.text("Error accessing Storage Server: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error accessing Storage Server: " + e.getMessage());
            }
            return;
        }

        // Handle Drive Bay interactions (only custom ones)
        if (isCustomDriveBay(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
                player.sendMessage(Component.text("You don't have permission to use Drive Bays.", NamedTextColor.RED));
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());

                // UPDATED: Allow Drive Bay access even without a valid network
                if (networkId == null) {
                    // Try to find any network ID associated with this location in the database
                    networkId = plugin.getDisksManager().findDriveBayNetworkId(block.getLocation());

                    if (networkId == null) {
                        // Generate a temporary network ID for standalone drive bay access
                        networkId = plugin.getDisksManager().generateStandaloneNetworkId(block.getLocation());

                        player.sendMessage(Component.text("Opening standalone drive bay (not connected to a network).", NamedTextColor.YELLOW));
                    } else {
                        player.sendMessage(Component.text("Opening drive bay (network connection lost).", NamedTextColor.YELLOW));
                    }
                } else if (!networkManager.isNetworkValid(networkId)) {
                    player.sendMessage(Component.text("Opening drive bay (network is no longer valid).", NamedTextColor.YELLOW));
                }

                // Open drive bay GUI regardless of network validity
                plugin.getGUIManager().openDriveBayGUI(player, block.getLocation(), networkId);

            } catch (Exception e) {
                player.sendMessage(Component.text("Error accessing drive bay: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error accessing drive bay: " + e.getMessage());
            }
        }
    }

    // Helper methods to check if blocks are OUR custom blocks or cables
    private boolean isCustomNetworkBlockOrCableOrExporter(Block block) {
        return isCustomNetworkBlock(block) || cableManager.isCustomNetworkCable(block) || isCustomExporter(block);
    }

    private boolean isCustomNetworkBlockOrCable(Block block) {
        return isCustomNetworkBlock(block) || cableManager.isCustomNetworkCable(block);
    }

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

    private boolean isCustomExporter(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "EXPORTER");
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
        if (itemManager.isNetworkCable(item)) return "NETWORK_CABLE";
        if (itemManager.isExporter(item)) return "EXPORTER";
        return "UNKNOWN";
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
        } else if (cableManager.isCustomNetworkCable(block)) {
            return itemManager.createNetworkCable();
        } else if (isCustomExporter(block)) {
            return itemManager.createExporter();
        }
        return null;
    }
}