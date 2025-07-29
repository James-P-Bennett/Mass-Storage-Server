package org.jamesphbennett.massstorageserver.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class DriveBayGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location driveBayLocation;
    private final String networkId;
    private final Inventory inventory;

    public DriveBayGUI(MassStorageServer plugin, Location driveBayLocation, String networkId) {
        this.plugin = plugin;
        this.driveBayLocation = driveBayLocation;
        this.networkId = networkId;

        // Create inventory - 3 rows for 8 drive slots + decorative items
        this.inventory = Bukkit.createInventory(null, 27, ChatColor.AQUA + "Drive Bay");

        setupGUI();
        loadDrives();
    }

    private void setupGUI() {
        int maxSlots = plugin.getConfigManager().getMaxDriveBaySlots();

        // Fill with background glass
        ItemStack background = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta backgroundMeta = background.getItemMeta();
        backgroundMeta.setDisplayName(" ");
        background.setItemMeta(backgroundMeta);

        // Fill entire inventory with background
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, background);
        }

        // Clear drive slots (configurable positions for 8 slots)
        int[] driveSlots = getDriveSlots(maxSlots);
        for (int slot : driveSlots) {
            inventory.setItem(slot, null);
        }

        // Add title item with network status
        updateTitleItem();
    }

    /**
     * Update the title item to show network status
     */
    private void updateTitleItem() {
        ItemStack title = new ItemStack(Material.CHISELED_TUFF_BRICKS);
        ItemMeta titleMeta = title.getItemMeta();
        titleMeta.setDisplayName(ChatColor.AQUA + "Drive Bay");

        List<String> titleLore = new ArrayList<>();
        titleLore.add(ChatColor.GRAY + "Insert storage disks to expand capacity");

        // Check network status
        boolean networkValid = isNetworkValid();
        if (networkValid) {
            titleLore.add(ChatColor.GREEN + "Network Status: Connected");
            titleLore.add(ChatColor.GRAY + "Slots: " + plugin.getConfigManager().getMaxDriveBaySlots());
        } else {
            titleLore.add(ChatColor.RED + "Network Status: Disconnected");
            titleLore.add(ChatColor.YELLOW + "You can still manage disks");
            titleLore.add(ChatColor.GRAY + "Slots: " + plugin.getConfigManager().getMaxDriveBaySlots());
        }

        titleMeta.setLore(titleLore);
        title.setItemMeta(titleMeta);
        inventory.setItem(4, title); // Top center
    }

    /**
     * Check if the network is still valid
     */
    private boolean isNetworkValid() {
        try {
            return plugin.getNetworkManager().isNetworkValid(networkId);
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking network validity: " + e.getMessage());
            return false;
        }
    }

    private int[] getDriveSlots(int maxSlots) {
        // Arrange 7 slots centered in the middle row (slots 10-16, using 11-17 for centering)
        // Row layout: [ ] [X] [X] [X] [X] [X] [X] [X] [ ]
        //             9   10  11  12  13  14  15  16  17
        return new int[]{10, 11, 12, 13, 14, 15, 16};
    }

    private void loadDrives() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {

            // First try to load drives using the exact network ID and location
            PreparedStatement stmt = conn.prepareStatement(
                    "SELECT slot_number, disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? ORDER BY slot_number");

            stmt.setString(1, driveBayLocation.getWorld().getName());
            stmt.setInt(2, driveBayLocation.getBlockX());
            stmt.setInt(3, driveBayLocation.getBlockY());
            stmt.setInt(4, driveBayLocation.getBlockZ());

            boolean foundAnyDisks = false;

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int slotNumber = rs.getInt("slot_number");
                    String diskId = rs.getString("disk_id");

                    if (diskId != null) {
                        // Load the storage disk item with current stats
                        ItemStack disk = loadStorageDiskWithCurrentStats(diskId);
                        if (disk != null) {
                            int[] driveSlots = getDriveSlots(plugin.getConfigManager().getMaxDriveBaySlots());
                            if (slotNumber < driveSlots.length) {
                                inventory.setItem(driveSlots[slotNumber], disk);
                                foundAnyDisks = true;
                            }
                        }
                    }
                }
            }

            // If no disks found and this is a standalone network ID,
            // try to find any drive bay slots at this location regardless of network ID
            if (!foundAnyDisks && networkId.startsWith("standalone_")) {
                plugin.getLogger().info("No disks found for standalone network, searching by location only");

                PreparedStatement anyNetworkStmt = conn.prepareStatement(
                        "SELECT slot_number, disk_id, network_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? ORDER BY slot_number");

                anyNetworkStmt.setString(1, driveBayLocation.getWorld().getName());
                anyNetworkStmt.setInt(2, driveBayLocation.getBlockX());
                anyNetworkStmt.setInt(3, driveBayLocation.getBlockY());
                anyNetworkStmt.setInt(4, driveBayLocation.getBlockZ());

                try (ResultSet rs = anyNetworkStmt.executeQuery()) {
                    while (rs.next()) {
                        int slotNumber = rs.getInt("slot_number");
                        String diskId = rs.getString("disk_id");
                        String originalNetworkId = rs.getString("network_id");

                        if (diskId != null) {
                            plugin.getLogger().info("Found disk " + diskId + " in slot " + slotNumber + " from network " + originalNetworkId);

                            // Load the storage disk item with current stats
                            ItemStack disk = loadStorageDiskWithCurrentStats(diskId);
                            if (disk != null) {
                                int[] driveSlots = getDriveSlots(plugin.getConfigManager().getMaxDriveBaySlots());
                                if (slotNumber < driveSlots.length) {
                                    inventory.setItem(driveSlots[slotNumber], disk);
                                    foundAnyDisks = true;
                                }
                            }
                        }
                    }
                }
                anyNetworkStmt.close();
            }

            if (foundAnyDisks) {
                plugin.getLogger().info("Loaded drives for drive bay at " + driveBayLocation + " (network: " + networkId + ")");
            } else {
                plugin.getLogger().info("No drives found for drive bay at " + driveBayLocation + " (network: " + networkId + ")");
            }

            stmt.close();
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading drives: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private ItemStack loadStorageDiskWithCurrentStats(String diskId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT crafter_uuid, crafter_name, used_cells, max_cells, tier FROM storage_disks WHERE disk_id = ?")) {

            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String crafterUUID = rs.getString("crafter_uuid");
                    String crafterName = rs.getString("crafter_name");
                    int usedCells = rs.getInt("used_cells");
                    int maxCells = rs.getInt("max_cells");
                    String tier = rs.getString("tier");

                    // CRITICAL FIX: Default to 1k if tier is null
                    if (tier == null || tier.isEmpty()) {
                        tier = "1k";
                        plugin.getLogger().warning("Disk " + diskId + " had no tier, defaulting to 1k");
                    }

                    // Create disk with specific tier
                    ItemStack disk = createStorageDiskWithSpecificTier(diskId, crafterUUID, crafterName, tier);
                    return plugin.getItemManager().updateStorageDiskLore(disk, usedCells, maxCells);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading storage disk: " + e.getMessage());
        }
        return null;
    }

    /**
     * CRITICAL FIX: Create a storage disk with the exact tier from database
     */
    private ItemStack createStorageDiskWithSpecificTier(String diskId, String crafterUUID, String crafterName, String tier) {
        // Get the correct material for the tier
        Material material = getMaterialForTier(tier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        int defaultCells = 64; // HARDCODED: All disks have 64 cells

        // Set display name with tier color
        String displayName = switch (tier.toLowerCase()) {
            case "1k" -> ChatColor.WHITE + "Storage Disk [1K]";
            case "4k" -> ChatColor.YELLOW + "Storage Disk [4K]";
            case "16k" -> ChatColor.AQUA + "Storage Disk [16K]";
            case "64k" -> ChatColor.LIGHT_PURPLE + "Storage Disk [64K]";
            default -> ChatColor.WHITE + "Storage Disk [1K]";
        };
        meta.setDisplayName(displayName);

        // Calculate capacity info
        int itemsPerCell = plugin.getItemManager().getItemsPerCellForTier(tier);
        int totalCapacity = defaultCells * itemsPerCell;

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Capacity: " + String.format("%,d", itemsPerCell) + " items per cell");
        lore.add(ChatColor.YELLOW + "Cells Used: 0/" + defaultCells);
        lore.add(ChatColor.AQUA + "Total Capacity: " + String.format("%,d", totalCapacity) + " items");
        lore.add("");
        lore.add(ChatColor.GRAY + "Tier: " + plugin.getItemManager().getTierDisplayName(tier));
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Crafted by: " + crafterName);
        lore.add(ChatColor.DARK_GRAY + "ID: " + diskId);
        meta.setLore(lore);

        meta.setCustomModelData(1004 + getTierModelOffset(tier));

        // Set persistent data
        NamespacedKey STORAGE_DISK_KEY = new NamespacedKey(plugin, "storage_disk");
        NamespacedKey DISK_ID_KEY = new NamespacedKey(plugin, "disk_id");
        NamespacedKey DISK_CRAFTER_UUID_KEY = new NamespacedKey(plugin, "disk_crafter_uuid");
        NamespacedKey DISK_CRAFTER_NAME_KEY = new NamespacedKey(plugin, "disk_crafter_name");
        NamespacedKey DISK_TIER_KEY = new NamespacedKey(plugin, "disk_tier");
        NamespacedKey DISK_USED_CELLS_KEY = new NamespacedKey(plugin, "disk_used_cells");
        NamespacedKey DISK_MAX_CELLS_KEY = new NamespacedKey(plugin, "disk_max_cells");

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(STORAGE_DISK_KEY, PersistentDataType.BOOLEAN, true);
        pdc.set(DISK_ID_KEY, PersistentDataType.STRING, diskId);
        pdc.set(DISK_CRAFTER_UUID_KEY, PersistentDataType.STRING, crafterUUID);
        pdc.set(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING, crafterName);
        pdc.set(DISK_TIER_KEY, PersistentDataType.STRING, tier);
        pdc.set(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, 0);
        pdc.set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, 64); // HARDCODED: All disks have 64 cells

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Get material for tier (copied from ItemManager for consistency)
     */
    private Material getMaterialForTier(String tier) {
        return switch (tier.toLowerCase()) {
            case "1k" -> Material.ACACIA_PRESSURE_PLATE;
            case "4k" -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
            case "16k" -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
            case "64k" -> Material.POLISHED_BLACKSTONE_PRESSURE_PLATE;
            default -> Material.ACACIA_PRESSURE_PLATE;
        };
    }

    /**
     * Get model data offset for tier (copied from ItemManager for consistency)
     */
    private int getTierModelOffset(String tier) {
        return switch (tier.toLowerCase()) {
            case "1k" -> 0;
            case "4k" -> 1;
            case "16k" -> 2;
            case "64k" -> 3;
            default -> 0;
        };
    }

    public void open(Player player) {
        player.openInventory(inventory);
        // Register this instance as a listener
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        int[] driveSlots = getDriveSlots(plugin.getConfigManager().getMaxDriveBaySlots());

        // Handle shift-clicks from player inventory
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            if (slot >= inventory.getSize()) {
                // Shift-clicking FROM player inventory TO drive bay
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem != null && !clickedItem.getType().isAir()) {
                    event.setCancelled(true);

                    if (!plugin.getItemManager().isStorageDisk(clickedItem)) {
                        player.sendMessage(ChatColor.RED + "Only storage disks can be placed in drive bays!");
                        return;
                    }

                    // Find first empty drive slot
                    for (int i = 0; i < driveSlots.length; i++) {
                        int driveSlot = driveSlots[i];
                        if (inventory.getItem(driveSlot) == null || inventory.getItem(driveSlot).getType().isAir()) {
                            // Found empty slot, place disk here
                            if (placeDiskInSlot(player, i, clickedItem)) {
                                // Remove from player inventory
                                clickedItem.setAmount(clickedItem.getAmount() - 1);
                                if (clickedItem.getAmount() <= 0) {
                                    event.setCurrentItem(null);
                                }
                                // Refresh the GUI to show the placed disk
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    loadDrives();
                                    updateTitleItem(); // Update network status
                                });
                            }
                            return;
                        }
                    }

                    player.sendMessage(ChatColor.RED + "No empty drive slots available!");
                    return;
                }
            } else {
                // Shift-clicking FROM drive bay TO player inventory
                boolean isDriveSlot = false;
                int driveSlotIndex = -1;
                for (int i = 0; i < driveSlots.length; i++) {
                    if (driveSlots[i] == slot) {
                        isDriveSlot = true;
                        driveSlotIndex = i;
                        break;
                    }
                }

                if (isDriveSlot) {
                    ItemStack clickedItem = event.getCurrentItem();
                    if (clickedItem != null && !clickedItem.getType().isAir() &&
                            plugin.getItemManager().isStorageDisk(clickedItem)) {

                        event.setCancelled(true);

                        // Check if player has space
                        if (player.getInventory().firstEmpty() == -1) {
                            player.sendMessage(ChatColor.RED + "Your inventory is full!");
                            return;
                        }

                        if (removeDiskFromSlot(player, driveSlotIndex)) {
                            // Get updated disk with current stats before giving to player
                            String diskId = plugin.getItemManager().getStorageDiskId(clickedItem);
                            ItemStack updatedDisk = loadStorageDiskWithCurrentStats(diskId);
                            if (updatedDisk != null) {
                                player.getInventory().addItem(updatedDisk);
                            } else {
                                player.getInventory().addItem(clickedItem);
                            }
                            inventory.setItem(slot, null);
                            // Refresh GUI to show the disk removal
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                loadDrives();
                                updateTitleItem(); // Update network status
                            });
                        }
                    }
                } else {
                    // Clicking on background/decoration - cancel
                    event.setCancelled(true);
                }
            }
            return;
        }

        // Check if clicking on a drive slot (non-shift clicks)
        boolean isDriveSlot = false;
        int driveSlotIndex = -1;
        for (int i = 0; i < driveSlots.length; i++) {
            if (driveSlots[i] == slot) {
                isDriveSlot = true;
                driveSlotIndex = i;
                break;
            }
        }

        if (isDriveSlot) {
            // Handle drive slot interactions (existing logic)
            handleDriveSlotClick(event, player, driveSlotIndex, slot);
        } else if (slot < inventory.getSize()) {
            // Clicking on background/decoration - cancel
            event.setCancelled(true);
        }
        // Clicks in player inventory are allowed (for non-shift clicks)
    }

    private void handleDriveSlotClick(InventoryClickEvent event, Player player, int driveSlotIndex, int slot) {
        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        if (cursorItem != null && !cursorItem.getType().isAir()) {
            // Player is trying to place an item
            if (plugin.getItemManager().isStorageDisk(cursorItem)) {
                // Valid storage disk
                event.setCancelled(true);

                if (clickedItem == null || clickedItem.getType().isAir()) {
                    // Empty slot - place the disk
                    if (placeDiskInSlot(player, driveSlotIndex, cursorItem)) {
                        // Clear cursor and refresh GUI to show the placed disk
                        event.setCursor(null);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            loadDrives();
                            updateTitleItem(); // Update network status
                        });
                    } else {
                        // Database failed - don't update GUI, disk stays on cursor
                        player.sendMessage(ChatColor.RED + "Failed to place disk - database error");
                    }
                } else {
                    // Slot occupied - swap if both are disks
                    if (plugin.getItemManager().isStorageDisk(clickedItem)) {
                        if (swapDisksInSlot(player, driveSlotIndex, clickedItem, cursorItem)) {
                            event.setCursor(clickedItem);
                            // Refresh GUI to show the swapped disk
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                loadDrives();
                                updateTitleItem(); // Update network status
                            });
                        }
                    }
                }
            } else {
                // Invalid item for drive bay
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "Only storage disks can be placed in drive bays!");
            }
        } else {
            // Player is trying to take an item
            if (clickedItem != null && !clickedItem.getType().isAir()) {
                if (plugin.getItemManager().isStorageDisk(clickedItem)) {
                    event.setCancelled(true);
                    if (removeDiskFromSlot(player, driveSlotIndex)) {
                        // Get updated disk with current stats before giving to player
                        String diskId = plugin.getItemManager().getStorageDiskId(clickedItem);
                        ItemStack updatedDisk = loadStorageDiskWithCurrentStats(diskId);
                        if (updatedDisk != null) {
                            event.setCursor(updatedDisk);
                        } else {
                            event.setCursor(clickedItem);
                        }
                        inventory.setItem(slot, null);
                        // Refresh GUI to show the removed disk
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            loadDrives();
                            updateTitleItem(); // Update network status
                        });
                    }
                }
            }
        }
    }

    private boolean placeDiskInSlot(Player player, int slotIndex, ItemStack disk) {
        String diskId = plugin.getItemManager().getStorageDiskId(disk);
        if (diskId == null) {
            player.sendMessage(ChatColor.RED + "Invalid storage disk!");
            return false;
        }

        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                // STEP 1: Check if the storage disk exists in the database
                boolean diskExists = false;
                String existingCrafterUUID = null;
                String existingCrafterName = null;
                String existingTier = null;

                try (PreparedStatement checkStmt = conn.prepareStatement(
                        "SELECT disk_id, crafter_uuid, crafter_name, tier FROM storage_disks WHERE disk_id = ?")) {
                    checkStmt.setString(1, diskId);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            diskExists = true;
                            existingCrafterUUID = rs.getString("crafter_uuid");
                            existingCrafterName = rs.getString("crafter_name");
                            existingTier = rs.getString("tier");
                        }
                    }
                }

                // STEP 2: Only create disk if it doesn't exist at all
                if (!diskExists) {
                    String crafterUUID = plugin.getItemManager().getDiskCrafterUUID(disk);
                    String crafterName = plugin.getItemManager().getDiskCrafterName(disk);
                    String tier = plugin.getItemManager().getDiskTier(disk);

                    // Fallback to item data if persistent data is missing
                    if (crafterUUID == null) {
                        crafterUUID = player.getUniqueId().toString();
                    }
                    if (crafterName == null) {
                        crafterName = player.getName();
                    }
                    if (tier == null) {
                        tier = "1k"; // Default fallback
                    }

                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO storage_disks (disk_id, crafter_uuid, crafter_name, tier, max_cells, used_cells) VALUES (?, ?, ?, ?, ?, ?)")) {
                        insertStmt.setString(1, diskId);
                        insertStmt.setString(2, crafterUUID);
                        insertStmt.setString(3, crafterName);
                        insertStmt.setString(4, tier);
                        insertStmt.setInt(5, plugin.getConfigManager().getDefaultCellsPerDisk()); // Should be 64
                        insertStmt.setInt(6, 0);
                        insertStmt.executeUpdate();
                    }

                    plugin.getLogger().info("Created new disk record for ID: " + diskId + " with tier: " + tier);
                } else {
                    plugin.getLogger().info("Found existing disk record for ID: " + diskId + " by " + existingCrafterName + " (tier: " + existingTier + ")");
                }

                // STEP 3: Check if this disk is already in another drive bay slot
                try (PreparedStatement conflictCheck = conn.prepareStatement(
                        "SELECT world_name, x, y, z, slot_number FROM drive_bay_slots WHERE disk_id = ?")) {
                    conflictCheck.setString(1, diskId);
                    try (ResultSet rs = conflictCheck.executeQuery()) {
                        if (rs.next()) {
                            // Remove from old location first
                            try (PreparedStatement removeOld = conn.prepareStatement(
                                    "DELETE FROM drive_bay_slots WHERE disk_id = ?")) {
                                removeOld.setString(1, diskId);
                                removeOld.executeUpdate();
                            }
                            plugin.getLogger().info("Moved disk " + diskId + " from another location");
                        }
                    }
                }

                // STEP 4: Insert drive bay slot
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO drive_bay_slots (network_id, world_name, x, y, z, slot_number, disk_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, networkId);
                    stmt.setString(2, driveBayLocation.getWorld().getName());
                    stmt.setInt(3, driveBayLocation.getBlockX());
                    stmt.setInt(4, driveBayLocation.getBlockY());
                    stmt.setInt(5, driveBayLocation.getBlockZ());
                    stmt.setInt(6, slotIndex);
                    stmt.setString(7, diskId);
                    stmt.executeUpdate();
                }

                // STEP 5: Update disk's network association (only if network is valid and not standalone)
                boolean networkValid = isNetworkValid();
                boolean isStandaloneNetwork = networkId.startsWith("standalone_");

                if (networkValid && !isStandaloneNetwork) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE storage_disks SET network_id = ? WHERE disk_id = ?")) {
                        stmt.setString(1, networkId);
                        stmt.setString(2, diskId);
                        int rowsUpdated = stmt.executeUpdate();

                        if (rowsUpdated == 0) {
                            throw new SQLException("Failed to update disk network association - disk not found");
                        }
                    }
                } else {
                    // Network is invalid or standalone, don't associate disk with it
                    plugin.getLogger().info("Network " + networkId + " is invalid or standalone, not associating disk " + diskId + " with it");
                }

                // STEP 6: Recalculate used_cells based on actual stored items
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE storage_disks SET used_cells = (SELECT COUNT(*) FROM storage_items WHERE disk_id = ?) WHERE disk_id = ?")) {
                    stmt.setString(1, diskId);
                    stmt.setString(2, diskId);
                    stmt.executeUpdate();
                }
            });

            // CRITICAL FIX: Only refresh network terminals if network is valid
            if (isNetworkValid()) {
                plugin.getGUIManager().refreshNetworkTerminals(networkId);
            }

            player.sendMessage(ChatColor.GREEN + "Storage disk inserted successfully!");
            plugin.getLogger().info("Successfully placed disk " + diskId + " in slot " + slotIndex);
            return true;
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Error inserting disk: " + e.getMessage());
            plugin.getLogger().severe("Error inserting disk " + diskId + ": " + e.getMessage());
            e.printStackTrace(); // Add stack trace for debugging
            return false;
        }
    }

    private boolean removeDiskFromSlot(Player player, int slotIndex) {
        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                // Get disk ID first
                String diskId = null;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND slot_number = ?")) {
                    stmt.setString(1, driveBayLocation.getWorld().getName());
                    stmt.setInt(2, driveBayLocation.getBlockX());
                    stmt.setInt(3, driveBayLocation.getBlockY());
                    stmt.setInt(4, driveBayLocation.getBlockZ());
                    stmt.setInt(5, slotIndex);

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            diskId = rs.getString("disk_id");
                        }
                    }
                }

                // Remove from drive bay slot ONLY - don't touch the disk's network association or stored items
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND slot_number = ?")) {
                    stmt.setString(1, driveBayLocation.getWorld().getName());
                    stmt.setInt(2, driveBayLocation.getBlockX());
                    stmt.setInt(3, driveBayLocation.getBlockY());
                    stmt.setInt(4, driveBayLocation.getBlockZ());
                    stmt.setInt(5, slotIndex);
                    stmt.executeUpdate();
                }

                // DO NOT remove network association - this was causing data loss!
                // The disk keeps its data and network association for when it's re-inserted
            });

            // CRITICAL FIX: Only refresh network terminals if network is valid
            if (isNetworkValid()) {
                plugin.getGUIManager().refreshNetworkTerminals(networkId);
            }

            player.sendMessage(ChatColor.YELLOW + "Storage disk removed successfully!");
            return true;
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Error removing disk: " + e.getMessage());
            plugin.getLogger().severe("Error removing disk: " + e.getMessage());
            return false;
        }
    }

    private boolean swapDisksInSlot(Player player, int slotIndex, ItemStack oldDisk, ItemStack newDisk) {
        // Remove old disk first, then place new disk
        if (removeDiskFromSlot(player, slotIndex)) {
            boolean success = placeDiskInSlot(player, slotIndex, newDisk);
            // Note: Both removeDiskFromSlot and placeDiskInSlot already call refreshNetworkTerminals if network is valid
            // so terminals will be refreshed twice, but this ensures consistency
            return success;
        }
        return false;
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        // Check if dragging into our inventory
        for (int slot : event.getRawSlots()) {
            if (slot < inventory.getSize()) {
                int[] driveSlots = getDriveSlots(plugin.getConfigManager().getMaxDriveBaySlots());

                // Only allow dragging into drive slots
                boolean isDriveSlot = false;
                for (int driveSlot : driveSlots) {
                    if (driveSlot == slot) {
                        isDriveSlot = true;
                        break;
                    }
                }

                if (!isDriveSlot) {
                    event.setCancelled(true);
                    return;
                }

                // Check if dragging a valid storage disk
                if (!plugin.getItemManager().isStorageDisk(event.getOldCursor())) {
                    event.setCancelled(true);
                    ((Player) event.getWhoClicked()).sendMessage(ChatColor.RED + "Only storage disks can be placed in drive bays!");
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        // Unregister this listener
        InventoryClickEvent.getHandlerList().unregister(this);
        InventoryDragEvent.getHandlerList().unregister(this);
        InventoryCloseEvent.getHandlerList().unregister(this);

        // Remove from GUI manager
        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().closeGUI(player);
        }
    }

    /**
     * Refresh the GUI to show current disk states (call this when disks are updated)
     */
    public void refreshDiskDisplay() {
        loadDrives();
        updateTitleItem(); // Update network status
    }
}