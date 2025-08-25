package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
import java.util.Objects;

public class DriveBayGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location driveBayLocation;
    private final String networkId;
    private final Inventory inventory;

    public DriveBayGUI(MassStorageServer plugin, Location driveBayLocation, String networkId) {
        this.plugin = plugin;
        this.driveBayLocation = driveBayLocation;
        this.networkId = networkId;

        this.inventory = Bukkit.createInventory(null, 27, plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.title"));

        setupGUI();
        loadDrives();
    }

    private void setupGUI() {

        ItemStack background = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta backgroundMeta = background.getItemMeta();
        backgroundMeta.displayName(Component.text(" "));
        background.setItemMeta(backgroundMeta);

        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, background);
        }

        int[] driveSlots = getDriveSlots();
        for (int slot : driveSlots) {
            inventory.setItem(slot, null);
        }

        updateTitleItem();
    }

    /**
     * Update the title item to show network status
     */
    private void updateTitleItem() {
        ItemStack title = new ItemStack(Material.CHISELED_TUFF_BRICKS);
        ItemMeta titleMeta = title.getItemMeta();
        titleMeta.displayName(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.title"));

        List<Component> titleLore = new ArrayList<>();
        titleLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.slot.insert"));

        boolean networkValid = isNetworkValid();
        if (networkValid) {
            titleLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.status.connected"));
            titleLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.info.slots", "filled", 0, "total", plugin.getConfigManager().getMaxDriveBaySlots()));
        } else {
            titleLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.status.disconnected"));
            titleLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.status.standalone"));
            titleLore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.info.slots", "filled", 0, "total", plugin.getConfigManager().getMaxDriveBaySlots()));
        }

        titleMeta.lore(titleLore);
        title.setItemMeta(titleMeta);
        inventory.setItem(4, title);
    }

    /**
     * Check if the network is still valid
     */
    private boolean isNetworkValid() {
        try {
            return networkId != null && plugin.getNetworkManager().isNetworkValid(networkId);
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking network validity: " + e.getMessage());
            return false;
        }
    }

    private int[] getDriveSlots() {
        return new int[]{10, 11, 12, 13, 14, 15, 16};
    }

    private void loadDrives() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {

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
                        ItemStack disk = loadStorageDiskWithCurrentStats(diskId);
                        if (disk != null) {
                            int[] driveSlots = getDriveSlots();
                            if (slotNumber < driveSlots.length) {
                                inventory.setItem(driveSlots[slotNumber], disk);
                                foundAnyDisks = true;
                            }
                        }
                    }
                }
            }

            if (!foundAnyDisks && networkId != null && networkId.startsWith("standalone_")) {
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

                            ItemStack disk = loadStorageDiskWithCurrentStats(diskId);
                            if (disk != null) {
                                int[] driveSlots = getDriveSlots();
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
                plugin.debugLog("Loaded drives for drive bay at " + driveBayLocation + " (network: " + networkId + ")");
            } else {
                plugin.debugLog("No drives found for drive bay at " + driveBayLocation + " (network: " + networkId + ")");
            }

            stmt.close();
        } catch (Exception e) {
            plugin.getLogger().severe("Error loading drives: " + e.getMessage());
            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
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

                    if (tier == null || tier.isEmpty()) {
                        tier = "1k";
                        plugin.getLogger().warning("Disk " + diskId + " had no tier, defaulting to 1k");
                    }

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
     * Create a storage disk with the exact tier from database
     */
    private ItemStack createStorageDiskWithSpecificTier(String diskId, String crafterUUID, String crafterName, String tier) {
        Material material = getMaterialForTier(tier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        int defaultCells = 64;

        Component displayName = switch (tier.toLowerCase()) {
            case "4k" -> plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.slot.disk", "tier", "4K");
            case "16k" -> plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.slot.disk", "tier", "16K");
            case "64k" -> plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.slot.disk", "tier", "64K");
            default -> plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.slot.disk", "tier", "1K");
        };
        meta.displayName(displayName);

        int itemsPerCell = plugin.getItemManager().getItemsPerCellForTier(tier);
        int totalCapacity = defaultCells * itemsPerCell;

        List<Component> lore = new ArrayList<>();
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.disk.capacity", "capacity", String.format("%,d", itemsPerCell)));
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.slot.usage", "used", 0, "max", defaultCells));
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.disk.total-capacity", "total", String.format("%,d", totalCapacity)));
        lore.add(Component.empty());
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.disk.tier", "tier", plugin.getItemManager().getTierDisplayName(tier)));
        lore.add(Component.empty());
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.disk.crafter", "crafter", crafterName));
        lore.add(plugin.getMessageManager().getMessageComponent(null, "gui.drive-bay.disk.id", "id", diskId));
        meta.lore(lore);


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
        pdc.set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, 64);

        item.setItemMeta(meta);
        return item;
    }

    private Material getMaterialForTier(String tier) {
        return switch (tier.toLowerCase()) {
            case "4k" -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
            case "16k" -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
            case "64k" -> Material.POLISHED_BLACKSTONE_PRESSURE_PLATE;
            default -> Material.ACACIA_PRESSURE_PLATE;
        };
    }

    public void open(Player player) {
        player.openInventory(inventory);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int slot = event.getRawSlot();
        int[] driveSlots = getDriveSlots();

        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            if (slot >= inventory.getSize()) {
                ItemStack clickedItem = event.getCurrentItem();
                if (clickedItem != null && !clickedItem.getType().isAir()) {
                    event.setCancelled(true);

                    if (!plugin.getItemManager().isStorageDisk(clickedItem)) {
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.drive-bay.error.only-disks"));
                        return;
                    }

                    for (int i = 0; i < driveSlots.length; i++) {
                        int driveSlot = driveSlots[i];
                        if (inventory.getItem(driveSlot) == null || Objects.requireNonNull(inventory.getItem(driveSlot)).getType().isAir()) {
                            if (placeDiskInSlot(player, i, clickedItem)) {
                                clickedItem.setAmount(clickedItem.getAmount() - 1);
                                if (clickedItem.getAmount() <= 0) {
                                    event.setCurrentItem(null);
                                }
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    loadDrives();
                                    updateTitleItem();
                                });
                            }
                            return;
                        }
                    }

                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.drive-bay.error.no-slots"));
                    return;
                }
            } else {
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

                        if (player.getInventory().firstEmpty() == -1) {
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.drive-bay.error.inventory-full"));
                            return;
                        }

                        if (removeDiskFromSlot(player, driveSlotIndex)) {
                            String diskId = plugin.getItemManager().getStorageDiskId(clickedItem);
                            ItemStack updatedDisk = loadStorageDiskWithCurrentStats(diskId);
                            player.getInventory().addItem(Objects.requireNonNullElse(updatedDisk, clickedItem));
                            inventory.setItem(slot, null);
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                loadDrives();
                                updateTitleItem();
                            });
                        }
                    }
                } else {
                    event.setCancelled(true);
                }
            }
            return;
        }

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
            handleDriveSlotClick(event, player, driveSlotIndex, slot);
        } else if (slot < inventory.getSize()) {
            event.setCancelled(true);
        }
    }

    private void handleDriveSlotClick(InventoryClickEvent event, Player player, int driveSlotIndex, int slot) {
        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        if (!cursorItem.getType().isAir()) {
            if (plugin.getItemManager().isStorageDisk(cursorItem)) {
                event.setCancelled(true);

                if (clickedItem == null || clickedItem.getType().isAir()) {
                    if (placeDiskInSlot(player, driveSlotIndex, cursorItem)) {
                        event.getView().setCursor(null);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            loadDrives();
                            updateTitleItem();
                        });
                    } else {
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.drive-bay.error.database"));
                    }
                } else {
                    if (plugin.getItemManager().isStorageDisk(clickedItem)) {
                        if (swapDisksInSlot(player, driveSlotIndex, cursorItem)) {
                            event.getView().setCursor(clickedItem);
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                loadDrives();
                                updateTitleItem();
                            });
                        }
                    }
                }
            } else {
                event.setCancelled(true);
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.drive-bay.error.only-disks"));
            }
        } else {
            if (clickedItem != null && !clickedItem.getType().isAir()) {
                if (plugin.getItemManager().isStorageDisk(clickedItem)) {
                    event.setCancelled(true);
                    if (removeDiskFromSlot(player, driveSlotIndex)) {
                        String diskId = plugin.getItemManager().getStorageDiskId(clickedItem);
                        ItemStack updatedDisk = loadStorageDiskWithCurrentStats(diskId);
                        event.getView().setCursor(Objects.requireNonNullElse(updatedDisk, clickedItem));
                        inventory.setItem(slot, null);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            loadDrives();
                            updateTitleItem();
                        });
                    }
                }
            }
        }
    }

    private boolean placeDiskInSlot(Player player, int slotIndex, ItemStack disk) {
        String diskId = plugin.getItemManager().getStorageDiskId(disk);
        if (diskId == null) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.drive-bay.error.invalid-disk"));
            return false;
        }

        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                boolean diskExists = false;
                String existingCrafterName = null;
                String existingTier = null;

                try (PreparedStatement checkStmt = conn.prepareStatement(
                        "SELECT disk_id, crafter_uuid, crafter_name, tier FROM storage_disks WHERE disk_id = ?")) {
                    checkStmt.setString(1, diskId);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            diskExists = true;
                            existingCrafterName = rs.getString("crafter_name");
                            existingTier = rs.getString("tier");
                        }
                    }
                }

                if (!diskExists) {
                    String crafterUUID = plugin.getItemManager().getDiskCrafterUUID(disk);
                    String crafterName = plugin.getItemManager().getDiskCrafterName(disk);
                    String tier = plugin.getItemManager().getDiskTier(disk);

                    if (crafterUUID == null) {
                        crafterUUID = player.getUniqueId().toString();
                    }
                    if (crafterName == null) {
                        crafterName = player.getName();
                    }
                    if (tier == null) {
                        tier = "1k";
                    }

                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO storage_disks (disk_id, crafter_uuid, crafter_name, tier, max_cells, used_cells) VALUES (?, ?, ?, ?, ?, ?)")) {
                        insertStmt.setString(1, diskId);
                        insertStmt.setString(2, crafterUUID);
                        insertStmt.setString(3, crafterName);
                        insertStmt.setString(4, tier);
                        insertStmt.setInt(5, plugin.getConfigManager().getDefaultCellsPerDisk());
                        insertStmt.setInt(6, 0);
                        insertStmt.executeUpdate();
                    }

                    plugin.getLogger().info("Created new disk record for ID: " + diskId + " with tier: " + tier);
                } else {
                    plugin.debugLog("Found existing disk record for ID: " + diskId + " by " + existingCrafterName + " (tier: " + existingTier + ")");
                }

                try (PreparedStatement conflictCheck = conn.prepareStatement(
                        "SELECT world_name, x, y, z, slot_number FROM drive_bay_slots WHERE disk_id = ?")) {
                    conflictCheck.setString(1, diskId);
                    try (ResultSet rs = conflictCheck.executeQuery()) {
                        if (rs.next()) {
                            try (PreparedStatement removeOld = conn.prepareStatement(
                                    "DELETE FROM drive_bay_slots WHERE disk_id = ?")) {
                                removeOld.setString(1, diskId);
                                removeOld.executeUpdate();
                            }
                            plugin.getLogger().info("Moved disk " + diskId + " from another location");
                        }
                    }
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO drive_bay_slots (network_id, world_name, x, y, z, slot_number, disk_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    stmt.setString(1, networkId != null ? networkId : "disconnected_" + System.currentTimeMillis());
                    stmt.setString(2, driveBayLocation.getWorld().getName());
                    stmt.setInt(3, driveBayLocation.getBlockX());
                    stmt.setInt(4, driveBayLocation.getBlockY());
                    stmt.setInt(5, driveBayLocation.getBlockZ());
                    stmt.setInt(6, slotIndex);
                    stmt.setString(7, diskId);
                    stmt.executeUpdate();
                }

                boolean networkValid = networkId != null && isNetworkValid();
                boolean isStandaloneNetwork = networkId != null && networkId.startsWith("standalone_");
                boolean isOrphanedNetwork = networkId != null && networkId.startsWith("orphaned_");

                if (networkValid && !isStandaloneNetwork && !isOrphanedNetwork) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE storage_disks SET network_id = ? WHERE disk_id = ?")) {
                        stmt.setString(1, networkId);
                        stmt.setString(2, diskId);
                        int rowsUpdated = stmt.executeUpdate();

                        if (rowsUpdated == 0) {
                            throw new SQLException("Failed to update disk network association - disk not found");
                        }
                        plugin.debugLog("Associated disk " + diskId + " with valid network " + networkId);
                    }
                } else if (isOrphanedNetwork) {
                    plugin.getLogger().info("Disk " + diskId + " placed in orphaned network, preserving data for potential restoration");
                } else {
                    plugin.getLogger().info("Network " + networkId + " is standalone/invalid, not associating disk " + diskId + " with it");
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE storage_disks SET used_cells = (SELECT COUNT(*) FROM storage_items WHERE disk_id = ?), updated_at = CURRENT_TIMESTAMP WHERE disk_id = ?")) {
                    stmt.setString(1, diskId);
                    stmt.setString(2, diskId);
                    stmt.executeUpdate();
                }
            });

            if (isNetworkValid()) {
                plugin.getGUIManager().refreshNetworkTerminals(networkId);
                plugin.debugLog("Refreshed terminals for valid network " + networkId + " after disk placement");
            } else {
                plugin.getLogger().info("Network " + (networkId != null ? networkId : "null") + " is not valid, skipping terminal refresh");
            }

            player.sendMessage(Component.text("Storage disk inserted successfully!", NamedTextColor.GREEN));
            plugin.debugLog("Successfully placed disk " + diskId + " in slot " + slotIndex);
            return true;
        } catch (Exception e) {
            player.sendMessage(Component.text("Error inserting disk: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error inserting disk " + diskId + ": " + e.getMessage());
            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
            return false;
        }
    }

    private boolean removeDiskFromSlot(Player player, int slotIndex) {
        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND slot_number = ?")) {
                    stmt.setString(1, driveBayLocation.getWorld().getName());
                    stmt.setInt(2, driveBayLocation.getBlockX());
                    stmt.setInt(3, driveBayLocation.getBlockY());
                    stmt.setInt(4, driveBayLocation.getBlockZ());
                    stmt.setInt(5, slotIndex);

                    try (ResultSet rs = stmt.executeQuery()) {
                        rs.next();
                    }
                }

                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND slot_number = ?")) {
                    stmt.setString(1, driveBayLocation.getWorld().getName());
                    stmt.setInt(2, driveBayLocation.getBlockX());
                    stmt.setInt(3, driveBayLocation.getBlockY());
                    stmt.setInt(4, driveBayLocation.getBlockZ());
                    stmt.setInt(5, slotIndex);
                    stmt.executeUpdate();
                }

            });

            if (isNetworkValid()) {
                plugin.getGUIManager().refreshNetworkTerminals(networkId);
            }

            player.sendMessage(Component.text("Storage disk removed successfully!", NamedTextColor.YELLOW));
            return true;
        } catch (Exception e) {
            player.sendMessage(Component.text("Error removing disk: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error removing disk: " + e.getMessage());
            return false;
        }
    }

    private boolean swapDisksInSlot(Player player, int slotIndex, ItemStack newDisk) {
        if (removeDiskFromSlot(player, slotIndex)) {
            return placeDiskInSlot(player, slotIndex, newDisk);
        }
        return false;
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        for (int slot : event.getRawSlots()) {
            if (slot < inventory.getSize()) {
                int[] driveSlots = getDriveSlots();

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

                if (!plugin.getItemManager().isStorageDisk(event.getOldCursor())) {
                    event.setCancelled(true);
                    event.getWhoClicked().sendMessage(Component.text("Only storage disks can be placed in drive bays!", NamedTextColor.RED));
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        InventoryClickEvent.getHandlerList().unregister(this);
        InventoryDragEvent.getHandlerList().unregister(this);
        InventoryCloseEvent.getHandlerList().unregister(this);

        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().closeGUI(player);
        }
    }

    public void refreshDiskDisplay() {
        loadDrives();
        updateTitleItem();
    }
}