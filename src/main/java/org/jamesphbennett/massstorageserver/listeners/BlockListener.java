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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
            // Check if exporter is being placed against (attached to) an MSS block
            Block blockAgainst = event.getBlockAgainst();
            if (isCustomMSSBlock(blockAgainst)) {
                event.setCancelled(true);
                player.sendMessage(Component.text("You cannot attach an exporter directly to the network!", NamedTextColor.RED));
                return;
            }

            // Check if there's a valid network nearby to connect to (optional)
            String nearbyNetworkId = null;
            for (Location adjacent : getAdjacentLocations(location)) {
                if (isCustomNetworkBlock(adjacent.getBlock()) || cableManager.isCustomNetworkCable(adjacent.getBlock()) || isCustomExporter(adjacent.getBlock())) {
                    nearbyNetworkId = networkManager.getNetworkId(adjacent);
                    if (nearbyNetworkId != null) {
                        break;
                    }
                }
            }

            // If no network found, use placeholder - exporter will remain inactive until connected
            if (nearbyNetworkId == null) {
                nearbyNetworkId = "UNCONNECTED";
                player.sendMessage(Component.text("Exporter placed! Connect it to a network to activate.", NamedTextColor.YELLOW));
            } else {
                player.sendMessage(Component.text("Exporter created successfully! Right-click to configure.", NamedTextColor.GREEN));
            }

            // Mark as custom block
            try {
                markLocationAsCustomBlock(location, "EXPORTER");

                // Create the exporter in the manager
                String exporterId = plugin.getExporterManager().createExporter(location, nearbyNetworkId);
                plugin.getLogger().info("Created exporter " + exporterId + " for player " + player.getName() + " at " + location +
                        (nearbyNetworkId.equals("UNCONNECTED") ? " (unconnected)" : " (connected to " + nearbyNetworkId + ")"));

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

            // Check if placing this MSS block would cause an exporter to be attached to it
            if (isMSSBlockType(blockType)) {
                String exporterConflict = checkExporterAttachmentConflict(location);
                if (exporterConflict != null) {
                    event.setCancelled(true);
                    player.sendMessage(Component.text(exporterConflict, NamedTextColor.RED));
                    return;
                }
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

                    // Register the network with comprehensive drive bay restoration
                    networkManager.registerNetwork(network, player.getUniqueId());

                    // Check if any drive bay contents were restored
                    boolean hasRestoredContent = plugin.getDisksManager().checkForRestoredContent(network.getDriveBays());

                    player.sendMessage(Component.text("Connected to network with " +
                            network.getDriveBays().size() + " drive bay(s) and " +
                            network.getTerminals().size() + " terminal(s).", NamedTextColor.GREEN));

                    // Show restoration message if items were restored
                    if (hasRestoredContent) {
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            player.sendMessage(Component.text("Drive bay contents have been restored!", NamedTextColor.AQUA));
                            player.sendMessage(Component.text("Check your terminals to see restored items.", NamedTextColor.YELLOW));
                        }, 10L);
                    }
                } else {
                    if (itemManager.isStorageServer(item)) {
                        player.sendMessage(Component.text("Storage Server requires Drive Bays and Terminals to form a network.", NamedTextColor.YELLOW));
                    } else if (itemManager.isNetworkCable(item)) {
                        player.sendMessage(Component.text("Cable placed. Connect to network blocks to extend your network.", NamedTextColor.YELLOW));
                    } else {
                        player.sendMessage(Component.text("This block needs to be connected to a Storage Server to function.", NamedTextColor.YELLOW));
                    }
                }

                plugin.getExporterManager().updateExporterNetworkAssignments();
                
                // Refresh terminals for the network (important for reconnections)
                if (network != null && network.isValid()) {
                    String finalNetworkId = networkManager.getNetworkId(location);
                    if (finalNetworkId != null) {
                        plugin.getGUIManager().refreshNetworkTerminals(finalNetworkId);
                        // Update drive bay network associations for reconnections
                        updateDriveBayNetworkAssociations(finalNetworkId, network.getDriveBays());
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
                        // Try to re-detect the network from remaining blocks and handle fragmentation
                        boolean networkStillValid = false;
                        Set<Location> processedLocations = new HashSet<>();

                        // Check each adjacent location to see if it forms a valid network segment
                        for (Location adjacent : getAdjacentLocations(location)) {
                            if (isCustomNetworkBlockOrCableOrExporter(adjacent.getBlock()) && !processedLocations.contains(adjacent)) {
                                NetworkInfo updatedNetwork = networkManager.detectNetwork(adjacent);
                                if (updatedNetwork != null && updatedNetwork.isValid()) {
                                    // Mark all blocks in this network segment as processed
                                    processedLocations.addAll(updatedNetwork.getAllBlocks());
                                    processedLocations.addAll(updatedNetwork.getNetworkCables());
                                    
                                    // Register this network segment (will get a new ID if different from original)
                                    networkManager.registerNetwork(updatedNetwork, player.getUniqueId());
                                    networkStillValid = true;
                                    
                                    // Refresh terminals for this network segment
                                    String newNetworkId = networkManager.getNetworkId(adjacent);
                                    if (newNetworkId != null) {
                                        plugin.getGUIManager().refreshNetworkTerminals(newNetworkId);
                                    }
                                    
                                    plugin.getLogger().info("Detected network segment with " + updatedNetwork.getAllBlocks().size() + " blocks and " + updatedNetwork.getNetworkCables().size() + " cables");
                                }
                            }
                        }
                        
                        if (networkStillValid) {
                            player.sendMessage(Component.text("Network updated after block removal.", NamedTextColor.GREEN));
                        }

                        if (!networkStillValid) {
                            // Network was completely broken - refresh terminals (to close them) and unregister it
                            try {
                                plugin.getGUIManager().refreshNetworkTerminals(networkId);
                                networkManager.unregisterNetwork(networkId);
                                player.sendMessage(Component.text("Storage network dissolved. Drive bay contents preserved.", NamedTextColor.YELLOW));
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error unregistering network " + networkId + ": " + e.getMessage());
                            }
                        }
                        
                        // Always refresh terminals for the original network ID to handle disconnections
                        plugin.getGUIManager().refreshNetworkTerminals(networkId);

                        plugin.getExporterManager().updateExporterNetworkAssignments();

                    } catch (Exception e) {
                        player.sendMessage(Component.text("Error updating network: " + e.getMessage(), NamedTextColor.RED));
                        plugin.getLogger().severe("Error updating network after block break: " + e.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            player.sendMessage(Component.text("Error processing block break: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error in block break event: " + e.getMessage());
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

        // Only handle main hand interactions to prevent duplicate events
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        // Check if player is awaiting search input and cancel ALL interactions
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

                // Open the exporter GUI
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
                player.sendMessage(Component.text("You don't have permission to use terminals.", NamedTextColor.RED));
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());
                if (networkId == null || !networkManager.isNetworkValid(networkId)) {
                    player.sendMessage(Component.text("This terminal is not connected to a valid network.", NamedTextColor.RED));
                    return;
                }

                // Open the terminal GUI
                plugin.getGUIManager().openTerminalGUI(player, block.getLocation(), networkId);

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
                player.sendMessage(Component.text("You don't have permission to view storage server info.", NamedTextColor.RED));
                return;
            }

            try {
                // Always display storage server information, regardless of network validity
                displayStorageServerInfo(player, block.getLocation());

            } catch (Exception e) {
                player.sendMessage(Component.text("Error accessing storage server: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error accessing storage server: " + e.getMessage());
            }
            return;
        }

        // Handle Drive Bay interactions (only custom ones)
        if (isCustomDriveBay(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("massstorageserver.use")) {
                player.sendMessage(Component.text("You don't have permission to use drive bays.", NamedTextColor.RED));
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());

                // Allow access even without network, show status message
                if (networkId == null) {
                    player.sendMessage(Component.text("Opening drive bay (no network connection).", NamedTextColor.YELLOW));
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

    /**
     * Check if a block is a custom MSS block (Storage Server, Drive Bay, or MSS Terminal)
     * Used for exporter placement validation
     */
    private boolean isCustomMSSBlock(Block block) {
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block);
    }

    /**
     * Check if a block type is an MSS block type
     */
    private boolean isMSSBlockType(String blockType) {
        return "STORAGE_SERVER".equals(blockType) || "DRIVE_BAY".equals(blockType) || "MSS_TERMINAL".equals(blockType);
    }

    /**
     * Check if placing an MSS block at this location would cause an existing exporter to be attached to it
     */
    private String checkExporterAttachmentConflict(Location mssBlockLocation) {
        // Check all adjacent locations for exporters that would be attached to this MSS block
        for (Location adjacent : getAdjacentLocations(mssBlockLocation)) {
            Block adjacentBlock = adjacent.getBlock();
            
            // Check if there's an exporter at this adjacent location
            if (isCustomExporter(adjacentBlock)) {
                // Check if this exporter would be a wall/floor mounted on the MSS block we're placing
                if (isExporterAttachedToBlock(adjacent, mssBlockLocation)) {
                    return "You cannot attach an exporter directly to the network!";
                }
            }
        }
        return null; // No conflict
    }

    /**
     * Check if an exporter at exporterLocation is attached to (wall/floor mounted on) the block at blockLocation
     */
    private boolean isExporterAttachedToBlock(Location exporterLocation, Location blockLocation) {
        Block exporterBlock = exporterLocation.getBlock();
        
        // For player heads, we need to check if they're attached to the specific block
        if (exporterBlock.getType() == Material.PLAYER_HEAD) {
            // Floor mounted head - check if it's sitting on top of the block
            Location below = exporterLocation.clone().add(0, -1, 0);
            return below.equals(blockLocation);
        } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
            // Wall mounted head - check if it's attached to any face of the block
            // Player wall heads are attached to the block they're facing away from
            org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) exporterBlock.getBlockData();
            org.bukkit.block.BlockFace facing = directional.getFacing();
            
            // The block the wall head is attached to is in the opposite direction
            Location attachedTo = exporterLocation.clone().add(facing.getOppositeFace().getDirection());
            return attachedTo.equals(blockLocation);
        }
        
        return false;
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

    private void markLocationAsCustomBlock(Location location, String blockType) throws SQLException {
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

    /**
     * Update drive bay network associations when networks change
     * This ensures that existing disks in drive bays get associated with the correct network
     */
    private void updateDriveBayNetworkAssociations(String networkId, Set<Location> driveBayLocations) {
        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                for (Location driveBayLoc : driveBayLocations) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE drive_bay_slots SET network_id = ? WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id IS NOT NULL")) {
                        
                        stmt.setString(1, networkId);
                        stmt.setString(2, driveBayLoc.getWorld().getName());
                        stmt.setInt(3, driveBayLoc.getBlockX());
                        stmt.setInt(4, driveBayLoc.getBlockY());
                        stmt.setInt(5, driveBayLoc.getBlockZ());
                        
                        int updated = stmt.executeUpdate();
                        if (updated > 0) {
                            plugin.getLogger().info("Updated " + updated + " drive bay slots to network " + networkId + " at " + driveBayLoc);
                        }
                    }
                }
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error updating drive bay network associations: " + e.getMessage());
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
        } else if (cableManager.isCustomNetworkCable(block)) {
            return itemManager.createNetworkCable();
        } else if (isCustomExporter(block)) {
            return itemManager.createExporter();
        }
        return null;
    }

    /**
     * Display storage server information to the player
     * Enhanced to detect connected blocks even for invalid networks
     */
    private void displayStorageServerInfo(Player player, Location serverLocation) {
        try {
            // Get network ID if it exists
            String networkId = networkManager.getNetworkId(serverLocation);
            boolean isValidNetwork = networkId != null && networkManager.isNetworkValid(networkId);
            
            // Detect connected blocks manually (including invalid networks)
            NetworkInfo detectedNetwork = networkManager.detectNetwork(serverLocation);
            
            int driveBayCount = 0;
            int terminalCount = 0;
            int cableCount = 0;
            int exporterCount = 0;
            int totalStorageCapacity = 0;
            int usedStorageCapacity = 0;
            
            if (detectedNetwork != null) {
                driveBayCount = detectedNetwork.getDriveBays().size();
                terminalCount = detectedNetwork.getTerminals().size();
                cableCount = detectedNetwork.getNetworkCables().size();
                
                // Count exporters in the detected network
                exporterCount = getExporterCountInArea(detectedNetwork.getAllBlocks());
                
                // Get storage capacity from valid network only
                if (isValidNetwork) {
                    totalStorageCapacity = getTotalNetworkStorageCapacity(networkId);
                    usedStorageCapacity = getUsedNetworkStorageCapacity(networkId);
                }
            }
            
            // Get configuration limits
            int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
            int maxCables = plugin.getConfigManager().getMaxNetworkCables();
            int maxExporters = plugin.getConfigManager().getMaxExporters();
            
            // Calculate total connected blocks (excluding cables)
            int totalBlocks = driveBayCount + terminalCount + exporterCount + 1; // +1 for the server itself
            
            // Display comprehensive information
            player.sendMessage(Component.text("═══ Storage Server Information ═══", NamedTextColor.AQUA));
            
            if (networkId != null) {
                player.sendMessage(Component.text("Network ID: " + networkId.substring(0, Math.min(16, networkId.length())), NamedTextColor.WHITE));
            } else {
                player.sendMessage(Component.text("Network ID: Not Assigned", NamedTextColor.GRAY));
            }
            
            // Network Status
            String status;
            NamedTextColor statusColor;
            if (isValidNetwork) {
                status = "Online";
                statusColor = NamedTextColor.GREEN;
            } else if (detectedNetwork != null && detectedNetwork.isValid()) {
                status = "Offline"; // Detected but not registered
                statusColor = NamedTextColor.YELLOW;
            } else {
                status = "Offline"; // No valid network detected
                statusColor = NamedTextColor.RED;
            }
            player.sendMessage(Component.text("Status: " + status, statusColor));
            
            player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line
            
            // Connected Components
            player.sendMessage(Component.text("Connected Components:", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("  Drive Bays: " + driveBayCount, NamedTextColor.GREEN));
            player.sendMessage(Component.text("  Terminals: " + terminalCount, NamedTextColor.GREEN));
            player.sendMessage(Component.text("  Exporters: " + exporterCount, NamedTextColor.GREEN));
            player.sendMessage(Component.text("  Cables: " + cableCount, NamedTextColor.YELLOW));
            
            player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line
            
            // Network Limits
            player.sendMessage(Component.text("Network Limits:", NamedTextColor.YELLOW));
            NamedTextColor blockLimitColor = totalBlocks > maxBlocks ? NamedTextColor.RED : NamedTextColor.GREEN;
            NamedTextColor cableLimitColor = cableCount > maxCables ? NamedTextColor.RED : NamedTextColor.GREEN;
            NamedTextColor exporterLimitColor = exporterCount > maxExporters ? NamedTextColor.RED : NamedTextColor.GREEN;
            
            player.sendMessage(Component.text("  Blocks: " + totalBlocks + "/" + maxBlocks, blockLimitColor));
            player.sendMessage(Component.text("  Cables: " + cableCount + "/" + maxCables, cableLimitColor));
            player.sendMessage(Component.text("  Bus Limit: " + exporterCount + "/" + maxExporters, exporterLimitColor));
            
            // Storage Information (only for valid networks)
            if (isValidNetwork) {
                player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line
                if (totalStorageCapacity > 0) {
                    int usagePercent = (int) ((double) usedStorageCapacity / totalStorageCapacity * 100);
                    player.sendMessage(Component.text("Storage: " + usedStorageCapacity + "/" + totalStorageCapacity + " cells (" + usagePercent + "%)", NamedTextColor.BLUE));
                } else {
                    player.sendMessage(Component.text("Storage: No disks installed", NamedTextColor.GRAY));
                }
            }

        } catch (Exception e) {
            player.sendMessage(Component.text("Error retrieving storage server information: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error displaying storage server info: " + e.getMessage());
        }
    }

    /**
     * Count exporters in the detected network area
     */
    private int getExporterCountInArea(Set<Location> networkBlocks) {
        int exporterCount = 0;
        
        // Check all adjacent locations to network blocks for exporters
        Set<Location> checkedLocations = new HashSet<>();
        
        for (Location networkBlock : networkBlocks) {
            for (Location adjacent : getAdjacentLocations(networkBlock)) {
                if (checkedLocations.contains(adjacent)) continue;
                checkedLocations.add(adjacent);
                
                Block block = adjacent.getBlock();
                if (isCustomExporter(block)) {
                    exporterCount++;
                }
            }
        }
        
        return exporterCount;
    }

    private int getTotalNetworkStorageCapacity(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT SUM(sd.max_cells) FROM storage_disks sd " +
                     "JOIN drive_bay_slots dbs ON sd.disk_id = dbs.disk_id " +
                     "WHERE dbs.network_id = ? AND dbs.disk_id IS NOT NULL")) {
            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int getUsedNetworkStorageCapacity(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT SUM(sd.used_cells) FROM storage_disks sd " +
                     "JOIN drive_bay_slots dbs ON sd.disk_id = dbs.disk_id " +
                     "WHERE dbs.network_id = ? AND dbs.disk_id IS NOT NULL")) {
            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}