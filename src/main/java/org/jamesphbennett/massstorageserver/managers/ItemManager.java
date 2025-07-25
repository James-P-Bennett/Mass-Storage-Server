package org.jamesphbennett.massstorageserver.managers;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ItemManager {

    private final MassStorageServer plugin;

    // Namespace keys for identifying custom items
    private final NamespacedKey STORAGE_SERVER_KEY;
    private final NamespacedKey DRIVE_BAY_KEY;
    private final NamespacedKey MSS_TERMINAL_KEY;
    private final NamespacedKey STORAGE_DISK_KEY;
    private final NamespacedKey DISK_ID_KEY;
    private final NamespacedKey DISK_CRAFTER_UUID_KEY;
    private final NamespacedKey DISK_CRAFTER_NAME_KEY;
    private final NamespacedKey DISK_USED_CELLS_KEY;
    private final NamespacedKey DISK_MAX_CELLS_KEY;

    public ItemManager(MassStorageServer plugin) {
        this.plugin = plugin;

        STORAGE_SERVER_KEY = new NamespacedKey(plugin, "storage_server");
        DRIVE_BAY_KEY = new NamespacedKey(plugin, "drive_bay");
        MSS_TERMINAL_KEY = new NamespacedKey(plugin, "mss_terminal");
        STORAGE_DISK_KEY = new NamespacedKey(plugin, "storage_disk");
        DISK_ID_KEY = new NamespacedKey(plugin, "disk_id");
        DISK_CRAFTER_UUID_KEY = new NamespacedKey(plugin, "disk_crafter_uuid");
        DISK_CRAFTER_NAME_KEY = new NamespacedKey(plugin, "disk_crafter_name");
        DISK_USED_CELLS_KEY = new NamespacedKey(plugin, "disk_used_cells");
        DISK_MAX_CELLS_KEY = new NamespacedKey(plugin, "disk_max_cells");
    }

    public ItemStack createStorageServer() {
        ItemStack item = new ItemStack(Material.CHISELED_TUFF);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.GOLD + "Storage Server");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "The core of the Mass Storage Network");
        lore.add(ChatColor.GRAY + "Place adjacent to Drive Bays and Terminals");
        meta.setLore(lore);

        meta.setCustomModelData(1001);
        meta.getPersistentDataContainer().set(STORAGE_SERVER_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createDriveBay() {
        ItemStack item = new ItemStack(Material.CHISELED_TUFF_BRICKS);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.AQUA + "Drive Bay");

        List<String> lore = new ArrayList<>();
        int maxSlots = plugin.getConfigManager() != null ? plugin.getConfigManager().getMaxDriveBaySlots() : 8;
        lore.add(ChatColor.GRAY + "Holds up to " + maxSlots + " storage disks");
        lore.add(ChatColor.GRAY + "Must be connected to a Storage Server");
        meta.setLore(lore);

        meta.setCustomModelData(1002);
        meta.getPersistentDataContainer().set(DRIVE_BAY_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createMSSTerminal() {
        ItemStack item = new ItemStack(Material.CRAFTER);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.GREEN + "MSS Terminal");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Access items stored in the network");
        lore.add(ChatColor.GRAY + "Right-click to open storage interface");
        meta.setLore(lore);

        meta.setCustomModelData(1003);
        meta.getPersistentDataContainer().set(MSS_TERMINAL_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createStorageDisk(String crafterUUID, String crafterName) {
        ItemStack item = new ItemStack(Material.ACACIA_PRESSURE_PLATE);
        ItemMeta meta = item.getItemMeta();

        String diskId = generateDiskId();
        int defaultCells = plugin.getConfigManager() != null ?
                plugin.getConfigManager().getDefaultCellsPerDisk() : 27;

        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Storage Disk");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Capacity: 1024 items per cell");
        lore.add(ChatColor.YELLOW + "Cells Used: 0/" + defaultCells);
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Crafted by: " + crafterName);
        lore.add(ChatColor.DARK_GRAY + "ID: " + diskId);
        meta.setLore(lore);

        meta.setCustomModelData(1004);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(STORAGE_DISK_KEY, PersistentDataType.BOOLEAN, true);
        pdc.set(DISK_ID_KEY, PersistentDataType.STRING, diskId);
        pdc.set(DISK_CRAFTER_UUID_KEY, PersistentDataType.STRING, crafterUUID);
        pdc.set(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING, crafterName);
        pdc.set(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, 0);
        pdc.set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, defaultCells);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createStorageDiskWithId(String diskId, String crafterUUID, String crafterName) {
        ItemStack item = new ItemStack(Material.ACACIA_PRESSURE_PLATE);
        ItemMeta meta = item.getItemMeta();

        int defaultCells = plugin.getConfigManager() != null ?
                plugin.getConfigManager().getDefaultCellsPerDisk() : 27;

        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Storage Disk");

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Capacity: 1024 items per cell");
        lore.add(ChatColor.YELLOW + "Cells Used: 0/" + defaultCells);
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Crafted by: " + crafterName);
        lore.add(ChatColor.DARK_GRAY + "ID: " + diskId);
        meta.setLore(lore);

        meta.setCustomModelData(1004);

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(STORAGE_DISK_KEY, PersistentDataType.BOOLEAN, true);
        pdc.set(DISK_ID_KEY, PersistentDataType.STRING, diskId); // Use provided ID
        pdc.set(DISK_CRAFTER_UUID_KEY, PersistentDataType.STRING, crafterUUID);
        pdc.set(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING, crafterName);
        pdc.set(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, 0);
        pdc.set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, defaultCells);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack updateStorageDiskLore(ItemStack disk, int usedCells, int maxCells) {
        if (!isStorageDisk(disk)) return disk;

        ItemStack newDisk = disk.clone();
        ItemMeta meta = newDisk.getItemMeta();

        String crafterName = meta.getPersistentDataContainer().get(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING);
        String diskId = meta.getPersistentDataContainer().get(DISK_ID_KEY, PersistentDataType.STRING);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Capacity: 1024 items per cell");
        lore.add((usedCells == maxCells ? ChatColor.RED : ChatColor.YELLOW) + "Cells Used: " + usedCells + "/" + maxCells);
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Crafted by: " + crafterName);
        lore.add(ChatColor.DARK_GRAY + "ID: " + diskId);
        meta.setLore(lore);

        // Update persistent data
        meta.getPersistentDataContainer().set(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, usedCells);
        meta.getPersistentDataContainer().set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, maxCells);

        newDisk.setItemMeta(meta);
        return newDisk;
    }

    public String getDiskCrafterName(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        return disk.getItemMeta().getPersistentDataContainer().get(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING);
    }

    // Identification methods
    public boolean isStorageServer(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(STORAGE_SERVER_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isDriveBay(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(DRIVE_BAY_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isMSSTerminal(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(MSS_TERMINAL_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isStorageDisk(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(STORAGE_DISK_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isNetworkBlock(ItemStack item) {
        return isStorageServer(item) || isDriveBay(item) || isMSSTerminal(item);
    }

    public String getStorageDiskId(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        return disk.getItemMeta().getPersistentDataContainer().get(DISK_ID_KEY, PersistentDataType.STRING);
    }

    public String getDiskCrafterUUID(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        return disk.getItemMeta().getPersistentDataContainer().get(DISK_CRAFTER_UUID_KEY, PersistentDataType.STRING);
    }

    public int getDiskUsedCells(ItemStack disk) {
        if (!isStorageDisk(disk)) return 0;
        Integer cells = disk.getItemMeta().getPersistentDataContainer().get(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER);
        return cells != null ? cells : 0;
    }

    public int getDiskMaxCells(ItemStack disk) {
        if (!isStorageDisk(disk)) return 0;
        Integer cells = disk.getItemMeta().getPersistentDataContainer().get(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER);
        return cells != null ? cells : (plugin.getConfigManager() != null ?
                plugin.getConfigManager().getDefaultCellsPerDisk() : 27);
    }

    private String generateDiskId() {
        try {
            String input = UUID.randomUUID().toString() + System.currentTimeMillis();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString().substring(0, 16).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to UUID if SHA-256 is not available
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        }
    }

    /**
     * Generate a unique hash for an ItemStack including all metadata
     */
    public String generateItemHash(ItemStack item) {
        try {
            StringBuilder builder = new StringBuilder();

            // Material type
            builder.append(item.getType().name());

            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();

                // Display name
                if (meta.hasDisplayName()) {
                    builder.append("|displayName:").append(meta.getDisplayName());
                }

                // Lore
                if (meta.hasLore()) {
                    builder.append("|lore:").append(meta.getLore().toString());
                }

                // Custom model data
                if (meta.hasCustomModelData()) {
                    builder.append("|customModelData:").append(meta.getCustomModelData());
                }

                // Enchantments
                if (meta.hasEnchants()) {
                    builder.append("|enchants:").append(meta.getEnchants().toString());
                }

                // Persistent data (excluding our own MSS keys to prevent issues)
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                for (NamespacedKey key : pdc.getKeys()) {
                    if (!key.getNamespace().equals(plugin.getName().toLowerCase())) {
                        builder.append("|pdc:").append(key.toString()).append("=");
                        // Try different data types
                        if (pdc.has(key, PersistentDataType.STRING)) {
                            builder.append(pdc.get(key, PersistentDataType.STRING));
                        } else if (pdc.has(key, PersistentDataType.INTEGER)) {
                            builder.append(pdc.get(key, PersistentDataType.INTEGER));
                        } else if (pdc.has(key, PersistentDataType.BOOLEAN)) {
                            builder.append(pdc.get(key, PersistentDataType.BOOLEAN));
                        }
                    }
                }
            }

            // Durability/damage
            if (item.getType().getMaxDurability() > 0) {
                builder.append("|durability:").append(item.getDurability());
            }

            // Generate SHA-256 hash
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(builder.toString().getBytes());

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple string hash
            return String.valueOf(item.toString().hashCode());
        }
    }

    /**
     * Check if an item is allowed to be stored in the network
     */
    public boolean isItemAllowed(ItemStack item) {
        // Block storage disks
        if (isStorageDisk(item)) {
            return false;
        }

        // Check configuration blacklist
        if (plugin.getConfigManager() != null &&
                plugin.getConfigManager().isItemBlacklisted(item.getType())) {
            return false;
        }

        // Block shulker boxes with contents
        if (item.getType().name().contains("SHULKER_BOX")) {
            if (item.hasItemMeta() && item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta blockMeta) {
                if (blockMeta.getBlockState() instanceof org.bukkit.block.ShulkerBox shulkerBox) {
                    // Allow empty shulker boxes only
                    for (ItemStack content : shulkerBox.getInventory().getContents()) {
                        if (content != null && !content.getType().isAir()) {
                            return false;
                        }
                    }
                }
            }
        }

        // Block bundles with contents
        if (item.getType() == Material.BUNDLE) {
            if (item.hasItemMeta() && item.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta bundleMeta) {
                // Allow empty bundles only
                if (!bundleMeta.getItems().isEmpty()) {
                    return false;
                }
            }
        }

        // Block other problematic container items (hardcoded for safety)
        return switch (item.getType()) {
            case CHEST, TRAPPED_CHEST, BARREL, HOPPER, DROPPER, DISPENSER, ENDER_CHEST -> false;
            default -> true;
        };
    }
}