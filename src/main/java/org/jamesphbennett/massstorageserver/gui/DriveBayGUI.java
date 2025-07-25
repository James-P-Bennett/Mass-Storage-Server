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

        // Add title item
        ItemStack title = new ItemStack(Material.CHISELED_TUFF_BRICKS);
        ItemMeta titleMeta = title.getItemMeta();
        titleMeta.setDisplayName(ChatColor.AQUA + "Drive Bay");
        List<String> titleLore = new ArrayList<>();
        titleLore.add(ChatColor.GRAY + "Insert storage disks to expand capacity");
        titleLore.add(ChatColor.GRAY + "Slots: " + maxSlots);
        titleMeta.setLore(titleLore);
        title.setItemMeta(titleMeta);
        inventory.setItem(4, title); // Top center
    }

    private int[] getDriveSlots(int maxSlots) {
        // Arrange 8 slots in a nice pattern (2 rows of 4)
        return new int[]{10, 11, 12, 13, 19, 20, 21, 22};
    }

    private void loadDrives() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT slot_number, disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? ORDER BY slot_number")) {

            stmt.setString(1, driveBayLocation.getWorld().getName());
            stmt.setInt(2, driveBayLocation.getBlockX());
            stmt.setInt(3, driveBayLocation.getBlockY());
            stmt.setInt(4, driveBayLocation.getBlockZ());

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
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading drives: " + e.getMessage());
        }
    }

    private ItemStack loadStorageDiskWithCurrentStats(String diskId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT crafter_uuid, crafter_name, used_cells, max_cells FROM storage_disks WHERE disk_id = ?")) {

            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String crafterUUID = rs.getString("crafter_uuid");
                    String crafterName = rs.getString("crafter_name");
                    int usedCells = rs.getInt("used_cells");
                    int maxCells = rs.getInt("max_cells");

                    // CRITICAL FIX: Use the specific disk ID instead of generating a new one
                    ItemStack disk = plugin.getItemManager().createStorageDiskWithId(diskId, crafterUUID, crafterName);
                    return plugin.getItemManager().updateStorageDiskLore(disk, usedCells, maxCells);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading storage disk: " + e.getMessage());
        }
        return null;
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
                                loadDrives();
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
                        // Clear cursor - the GUI refresh will show the updated disk
                        event.setCursor(null);
                    } else {
                        // Database failed - don't update GUI, disk stays on cursor
                        player.sendMessage(ChatColor.RED + "Failed to place disk - database error");
                    }
                } else {
                    // Slot occupied - swap if both are disks
                    if (plugin.getItemManager().isStorageDisk(clickedItem)) {
                        if (swapDisksInSlot(player, driveSlotIndex, clickedItem, cursorItem)) {
                            event.setCursor(clickedItem);
                            // The GUI refresh from placeDiskInSlot will show the new disk
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

                try (PreparedStatement checkStmt = conn.prepareStatement(
                        "SELECT disk_id, crafter_uuid, crafter_name FROM storage_disks WHERE disk_id = ?")) {
                    checkStmt.setString(1, diskId);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            diskExists = true;
                            existingCrafterUUID = rs.getString("crafter_uuid");
                            existingCrafterName = rs.getString("crafter_name");
                        }
                    }
                }

                // STEP 2: Only create disk if it doesn't exist at all
                if (!diskExists) {
                    String crafterUUID = plugin.getItemManager().getDiskCrafterUUID(disk);
                    String crafterName = plugin.getItemManager().getDiskCrafterName(disk); // You'll need to add this method

                    // Fallback to item lore if persistent data is missing
                    if (crafterUUID == null) {
                        crafterUUID = player.getUniqueId().toString();
                    }
                    if (crafterName == null) {
                        crafterName = player.getName();
                    }

                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO storage_disks (disk_id, crafter_uuid, crafter_name, max_cells, used_cells) VALUES (?, ?, ?, ?, ?)")) {
                        insertStmt.setString(1, diskId);
                        insertStmt.setString(2, crafterUUID);
                        insertStmt.setString(3, crafterName);
                        insertStmt.setInt(4, plugin.getConfigManager().getDefaultCellsPerDisk());
                        insertStmt.setInt(5, 0);
                        insertStmt.executeUpdate();
                    }

                    plugin.getLogger().info("Created new disk record for ID: " + diskId);
                } else {
                    plugin.getLogger().info("Found existing disk record for ID: " + diskId + " by " + existingCrafterName);
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

                // STEP 5: Update disk's network association
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE storage_disks SET network_id = ? WHERE disk_id = ?")) {
                    stmt.setString(1, networkId);
                    stmt.setString(2, diskId);
                    int rowsUpdated = stmt.executeUpdate();

                    if (rowsUpdated == 0) {
                        throw new SQLException("Failed to update disk network association - disk not found");
                    }
                }

                // STEP 6: Recalculate used_cells based on actual stored items
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE storage_disks SET used_cells = (SELECT COUNT(*) FROM storage_items WHERE disk_id = ?) WHERE disk_id = ?")) {
                    stmt.setString(1, diskId);
                    stmt.setString(2, diskId);
                    stmt.executeUpdate();
                }
            });

            // CRITICAL FIX: Refresh all terminals in this network after disk insertion
            plugin.getGUIManager().refreshNetworkTerminals(networkId);

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

            // CRITICAL FIX: Refresh all terminals in this network after disk removal
            plugin.getGUIManager().refreshNetworkTerminals(networkId);

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
            // Note: Both removeDiskFromSlot and placeDiskInSlot already call refreshNetworkTerminals
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
    }
}