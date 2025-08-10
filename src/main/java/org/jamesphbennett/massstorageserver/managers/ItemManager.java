package org.jamesphbennett.massstorageserver.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ItemManager {

    private final MassStorageServer plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer;

    // Namespace keys for identifying custom items
    private final NamespacedKey STORAGE_SERVER_KEY;
    private final NamespacedKey DRIVE_BAY_KEY;
    private final NamespacedKey MSS_TERMINAL_KEY;
    private final NamespacedKey NETWORK_CABLE_KEY;
    private final NamespacedKey STORAGE_DISK_KEY;
    private final NamespacedKey EXPORTER_KEY;
    private final NamespacedKey DISK_ID_KEY;
    private final NamespacedKey DISK_CRAFTER_UUID_KEY;
    private final NamespacedKey DISK_CRAFTER_NAME_KEY;
    private final NamespacedKey DISK_USED_CELLS_KEY;
    private final NamespacedKey DISK_MAX_CELLS_KEY;
    private final NamespacedKey DISK_TIER_KEY;

    // Component namespace keys
    private final NamespacedKey DISK_PLATTER_KEY;
    private final NamespacedKey DISK_PLATTER_TIER_KEY;
    private final NamespacedKey STORAGE_DISK_HOUSING_KEY;

    public ItemManager(MassStorageServer plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.legacySerializer = LegacyComponentSerializer.legacySection();

        STORAGE_SERVER_KEY = new NamespacedKey(plugin, "storage_server");
        DRIVE_BAY_KEY = new NamespacedKey(plugin, "drive_bay");
        MSS_TERMINAL_KEY = new NamespacedKey(plugin, "mss_terminal");
        NETWORK_CABLE_KEY = new NamespacedKey(plugin, "network_cable");
        STORAGE_DISK_KEY = new NamespacedKey(plugin, "storage_disk");
        EXPORTER_KEY = new NamespacedKey(plugin, "exporter");
        DISK_ID_KEY = new NamespacedKey(plugin, "disk_id");
        DISK_CRAFTER_UUID_KEY = new NamespacedKey(plugin, "disk_crafter_uuid");
        DISK_CRAFTER_NAME_KEY = new NamespacedKey(plugin, "disk_crafter_name");
        DISK_USED_CELLS_KEY = new NamespacedKey(plugin, "disk_used_cells");
        DISK_MAX_CELLS_KEY = new NamespacedKey(plugin, "disk_max_cells");
        DISK_TIER_KEY = new NamespacedKey(plugin, "disk_tier");

        // Component keys
        DISK_PLATTER_KEY = new NamespacedKey(plugin, "disk_platter");
        DISK_PLATTER_TIER_KEY = new NamespacedKey(plugin, "disk_platter_tier");
        STORAGE_DISK_HOUSING_KEY = new NamespacedKey(plugin, "storage_disk_housing");
    }

    public ItemStack createStorageServer() {
        ItemStack item = new ItemStack(Material.CHISELED_TUFF);
        ItemMeta meta = item.getItemMeta();

        Component displayName = miniMessage.deserialize("<gold>Storage Server");
        meta.setDisplayName(legacySerializer.serialize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>The core of the Mass Storage Network")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Place adjacent to Drive Bays and Terminals")));
        meta.setLore(lore);

        meta.setCustomModelData(1001);
        meta.getPersistentDataContainer().set(STORAGE_SERVER_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createDriveBay() {
        ItemStack item = new ItemStack(Material.CHISELED_TUFF_BRICKS);
        ItemMeta meta = item.getItemMeta();

        Component displayName = miniMessage.deserialize("<aqua>Drive Bay");
        meta.setDisplayName(legacySerializer.serialize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Holds up to 7 storage disks")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Must be connected to a Storage Server")));
        meta.setLore(lore);

        meta.setCustomModelData(1002);
        meta.getPersistentDataContainer().set(DRIVE_BAY_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createMSSTerminal() {
        ItemStack item = new ItemStack(Material.CRAFTER);
        ItemMeta meta = item.getItemMeta();

        Component displayName = miniMessage.deserialize("<green>MSS Terminal");
        meta.setDisplayName(legacySerializer.serialize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Access items stored in the network")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Right-click to open storage interface")));
        meta.setLore(lore);

        meta.setCustomModelData(1003);
        meta.getPersistentDataContainer().set(MSS_TERMINAL_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createNetworkCable() {
        ItemStack item = new ItemStack(Material.HEAVY_CORE);
        ItemMeta meta = item.getItemMeta();

        Component displayName = miniMessage.deserialize("<blue>Network Cable");
        meta.setDisplayName(legacySerializer.serialize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Connects network components over distance")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Place to extend your network reach")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<yellow>Does not count toward block limit")));
        meta.setLore(lore);

        meta.setCustomModelData(1005);
        meta.getPersistentDataContainer().set(NETWORK_CABLE_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createExporter() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();

        Component displayName = miniMessage.deserialize("<dark_purple>Exporter");
        meta.setDisplayName(legacySerializer.serialize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Automatically exports items to containers")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Place adjacent to any inventory")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<yellow>Connects: North, South, East, West, or Down")));
        meta.setLore(lore);

        meta.setCustomModelData(1011);
        meta.getPersistentDataContainer().set(EXPORTER_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    // ==================== COMPONENT CREATION METHODS ====================

    /**
     * Create a Disk Platter for the specified tier
     */
    public ItemStack createDiskPlatter(String tier) {
        Material material = getDiskPlatterMaterial(tier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // Set display name with tier color
        Component displayName = switch (tier.toLowerCase()) {
            case "1k" -> miniMessage.deserialize("<white>Disk Platter [1K]");
            case "4k" -> miniMessage.deserialize("<yellow>Disk Platter [4K]");
            case "16k" -> miniMessage.deserialize("<aqua>Disk Platter [16K]");
            case "64k" -> miniMessage.deserialize("<light_purple>Disk Platter [64K]");
            default -> miniMessage.deserialize("<white>Disk Platter [1K]");
        };
        meta.setDisplayName(legacySerializer.serialize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Component for crafting storage disks")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Tier: " + getTierDisplayName(tier))));
        lore.add("");
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<dark_gray>Mass Storage Component")));
        meta.setLore(lore);

        meta.setCustomModelData(1006 + getDiskPlatterModelOffset(tier));

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(DISK_PLATTER_KEY, PersistentDataType.BOOLEAN, true);
        pdc.set(DISK_PLATTER_TIER_KEY, PersistentDataType.STRING, tier);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Create a Storage Disk Housing
     */
    public ItemStack createStorageDiskHousing() {
        ItemStack item = new ItemStack(Material.BAMBOO_PRESSURE_PLATE);
        ItemMeta meta = item.getItemMeta();

        Component displayName = miniMessage.deserialize("<gray>Storage Disk Housing");
        meta.setDisplayName(legacySerializer.serialize(displayName));

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Component for crafting storage disks")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Houses the disk platter and circuitry")));
        lore.add("");
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<dark_gray>Mass Storage Component")));
        meta.setLore(lore);

        meta.setCustomModelData(1010);
        meta.getPersistentDataContainer().set(STORAGE_DISK_HOUSING_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    /**
     * Get material for disk platter based on tier
     */
    private Material getDiskPlatterMaterial(String tier) {
        return switch (tier.toLowerCase()) {
            case "1k" -> Material.STONE_BUTTON;
            case "4k" -> Material.GOLD_NUGGET;
            case "16k" -> Material.CONDUIT;
            case "64k" -> Material.HEART_OF_THE_SEA;
            default -> Material.STONE_BUTTON;
        };
    }

    /**
     * Get model data offset for disk platter based on tier
     */
    private int getDiskPlatterModelOffset(String tier) {
        return switch (tier.toLowerCase()) {
            case "1k" -> 0;
            case "4k" -> 1;
            case "16k" -> 2;
            case "64k" -> 3;
            default -> 0;
        };
    }

    // ==================== COMPONENT IDENTIFICATION METHODS ====================

    /**
     * Check if an item is a disk platter component
     */
    public boolean isDiskPlatter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(DISK_PLATTER_KEY, PersistentDataType.BOOLEAN);
    }

    /**
     * Check if an item is a storage disk housing component
     */
    public boolean isStorageDiskHousing(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(STORAGE_DISK_HOUSING_KEY, PersistentDataType.BOOLEAN);
    }

    /**
     * Check if an item is any MSS component
     */
    public boolean isMSSComponent(ItemStack item) {
        return isDiskPlatter(item) || isStorageDiskHousing(item);
    }

    /**
     * Get the tier of a disk platter
     */
    public String getDiskPlatterTier(ItemStack item) {
        if (!isDiskPlatter(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(DISK_PLATTER_TIER_KEY, PersistentDataType.STRING);
    }

    /**
     * Check if an item is a specific tier disk platter
     */
    public boolean isDiskPlatterOfTier(ItemStack item, String tier) {
        if (!isDiskPlatter(item)) return false;
        String itemTier = getDiskPlatterTier(item);
        return tier.equalsIgnoreCase(itemTier);
    }

    // ==================== TIERED DISK CREATION METHODS ====================

    /**
     * Create a 1K storage disk (base tier)
     */
    public ItemStack createStorageDisk(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "1k");
    }

    /**
     * Create a 4K storage disk
     */
    public ItemStack createStorageDisk4k(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "4k");
    }

    /**
     * Create a 16K storage disk
     */
    public ItemStack createStorageDisk16k(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "16k");
    }

    /**
     * Create a 64K storage disk
     */
    public ItemStack createStorageDisk64k(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "64k");
    }

    /**
     * Core method to create any tier of storage disk
     */
    private ItemStack createStorageDiskWithTier(String crafterUUID, String crafterName, String tier) {
        String diskId = generateDiskId();
        return createStorageDiskWithIdAndTier(diskId, crafterUUID, crafterName, tier);
    }

    /**
     * Enhanced createStorageDiskWithId - now determines tier from database or defaults to 1k
     */
    public ItemStack createStorageDiskWithId(String diskId, String crafterUUID, String crafterName) {
        // Try to get tier from database first
        String tier = getTierFromDatabase(diskId);
        if (tier == null) {
            tier = "1k"; // Default fallback
        }
        return createStorageDiskWithIdAndTier(diskId, crafterUUID, crafterName, tier);
    }

    /**
     * Create disk with specific ID and tier
     */
    private ItemStack createStorageDiskWithIdAndTier(String diskId, String crafterUUID, String crafterName, String tier) {
        Material material = getMaterialForTier(tier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // HARDCODED: All disks have 64 cells
        int defaultCells = 64;

        // Set display name with tier color
        Component displayName = switch (tier) {
            case "1k" -> miniMessage.deserialize("<white>Storage Disk [1K]");
            case "4k" -> miniMessage.deserialize("<yellow>Storage Disk [4K]");
            case "16k" -> miniMessage.deserialize("<aqua>Storage Disk [16K]");
            case "64k" -> miniMessage.deserialize("<light_purple>Storage Disk [64K]");
            default -> miniMessage.deserialize("<white>Storage Disk [1K]");
        };
        meta.setDisplayName(legacySerializer.serialize(displayName));

        // Calculate capacity info
        int itemsPerCell = getItemsPerCellForTier(tier);
        int totalCapacity = defaultCells * itemsPerCell;

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Capacity: " + String.format("%,d", itemsPerCell) + " items per cell")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<yellow>Cells Used: 0/" + defaultCells)));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<aqua>Total Capacity: " + String.format("%,d", totalCapacity) + " items")));
        lore.add("");
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Tier: " + getTierDisplayName(tier))));
        lore.add("");
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<dark_gray>Crafted by: " + crafterName)));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<dark_gray>ID: " + diskId)));
        meta.setLore(lore);

        meta.setCustomModelData(1004 + getTierModelOffset(tier));

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
     * Updated updateStorageDiskLore method with tier support
     */
    public ItemStack updateStorageDiskLore(ItemStack disk, int usedCells, int maxCells) {
        if (!isStorageDisk(disk)) return disk;

        ItemStack newDisk = disk.clone();
        ItemMeta meta = newDisk.getItemMeta();

        String crafterName = meta.getPersistentDataContainer().get(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING);
        String diskId = meta.getPersistentDataContainer().get(DISK_ID_KEY, PersistentDataType.STRING);
        String tier = getDiskTier(disk);

        // Calculate capacity info
        int itemsPerCell = getItemsPerCellForTier(tier);
        int totalCapacity = maxCells * itemsPerCell;

        // Color coding based on usage
        String usageColor = (usedCells >= maxCells) ? "<red>" :
                (usedCells >= maxCells * 0.8) ? "<yellow>" : "<green>";

        List<String> lore = new ArrayList<>();
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Capacity: " + String.format("%,d", itemsPerCell) + " items per cell")));
        lore.add(legacySerializer.serialize(miniMessage.deserialize(usageColor + "Cells Used: " + usedCells + "/" + maxCells)));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<aqua>Total Capacity: " + String.format("%,d", totalCapacity) + " items")));
        lore.add("");
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<gray>Tier: " + getTierDisplayName(tier))));
        lore.add("");
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<dark_gray>Crafted by: " + crafterName)));
        lore.add(legacySerializer.serialize(miniMessage.deserialize("<dark_gray>ID: " + diskId)));
        meta.setLore(lore);

        // Update persistent data
        meta.getPersistentDataContainer().set(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, usedCells);
        meta.getPersistentDataContainer().set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, maxCells);

        newDisk.setItemMeta(meta);
        return newDisk;
    }

    // ==================== TIER SYSTEM HELPER METHODS ====================

    /**
     * Get the tier of a storage disk
     */
    public String getDiskTier(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        String tier = disk.getItemMeta().getPersistentDataContainer().get(DISK_TIER_KEY, PersistentDataType.STRING);
        return tier != null ? tier : "1k"; // Default to 1k if not set
    }

    /**
     * Get items per cell for a specific tier
     * HARDCODED VALUES:
     * 1K = 127 items per cell
     * 4K = 508 items per cell
     * 16K = 2,032 items per cell
     * 64K = 8,128 items per cell
     */
    public int getItemsPerCellForTier(String tier) {
        if (tier == null) return 127;
        return switch (tier.toLowerCase()) {
            case "1k" -> 127;
            case "4k" -> 508;
            case "16k" -> 2032;
            case "64k" -> 8128;
            default -> 127;
        };
    }

    /**
     * Get material for tier
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
     * Get tier display name with color - returns MiniMessage string for embedding
     */
    public String getTierDisplayName(String tier) {
        return switch (tier.toLowerCase()) {
            case "1k" -> "<white>1K";
            case "4k" -> "<yellow>4K";
            case "16k" -> "<aqua>16K";
            case "64k" -> "<light_purple>64K";
            default -> "<white>1K";
        };
    }

    /**
     * Get model data offset for tier (for custom model data)
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

    /**
     * Get tier from database
     */
    private String getTierFromDatabase(String diskId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT tier FROM storage_disks WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("tier");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting tier from database for disk " + diskId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Get maximum items per cell for a disk by checking its tier
     */
    public int getMaxItemsPerCell(ItemStack disk) {
        String tier = getDiskTier(disk);
        return getItemsPerCellForTier(tier);
    }

    /**
     * Get maximum items per cell for a disk ID (for database operations)
     */
    public int getMaxItemsPerCellForDiskId(String diskId) throws Exception {
        String tier = getTierFromDatabase(diskId);
        return getItemsPerCellForTier(tier);
    }

    /**
     * Check if item is a specific tier disk
     */
    public boolean isDiskOfTier(ItemStack item, String tier) {
        if (!isStorageDisk(item)) return false;
        String diskTier = getDiskTier(item);
        return tier.equalsIgnoreCase(diskTier);
    }

    /**
     * Get all available tiers
     */
    public String[] getAllTiers() {
        return new String[]{"1k", "4k", "16k", "64k"};
    }

    /**
     * Check if a tier is valid
     */
    public boolean isValidTier(String tier) {
        if (tier == null) return false;
        for (String validTier : getAllTiers()) {
            if (validTier.equalsIgnoreCase(tier)) return true;
        }
        return false;
    }

    // ==================== EXISTING METHODS (UPDATED) ====================

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

    public boolean isNetworkCable(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(NETWORK_CABLE_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isStorageDisk(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(STORAGE_DISK_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isExporter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(EXPORTER_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isNetworkBlock(ItemStack item) {
        return isStorageServer(item) || isDriveBay(item) || isMSSTerminal(item) || isNetworkCable(item) || isExporter(item);
    }

    /**
     * Check if an item is any MSS item (blocks, disks, or components)
     */
    public boolean isMSSItem(ItemStack item) {
        return isNetworkBlock(item) || isStorageDisk(item) || isMSSComponent(item);
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
        // HARDCODED: Always return 64 regardless of what's stored - for migration purposes
        return 64;
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
        // Block all MSS items (disks, blocks, and components)
        if (isMSSItem(item)) {
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

        // Block ALL bundles with contents (including all colored variants)
        if (item.getType().name().contains("BUNDLE")) {
            if (item.hasItemMeta() && item.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta bundleMeta) {
                // Allow empty bundles only
                if (!bundleMeta.getItems().isEmpty()) {
                    return false;
                }
            }
        }

        // All other items are allowed by default unless in config blacklist
        return true;
    }
}