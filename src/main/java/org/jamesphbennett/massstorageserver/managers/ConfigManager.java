package org.jamesphbennett.massstorageserver.managers;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private final MassStorageServer plugin;
    private FileConfiguration config;

    // Cached configuration values
    private int maxNetworkBlocks;
    private long operationCooldown;
    private int maxDriveBaySlots;
    private Set<Material> blacklistedItems;
    private boolean requireUsePermission;
    private boolean requireCraftPermission;
    private boolean requireAdminPermission;
    private boolean logNetworkOperations;
    private boolean logStorageOperations;
    private boolean logDatabaseOperations;
    private boolean debugEnabled;
    private boolean debugVerbose;

    // HARDCODED: All disks have 64 cells - no longer configurable
    private static final int HARDCODED_CELLS_PER_DISK = 64;

    // HARDCODED: Items per cell are tier-specific - no longer configurable
    // 1K = 127, 4K = 508, 16K = 2032, 64K = 8128

    public ConfigManager(MassStorageServer plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        // Save default config if it doesn't exist
        plugin.saveDefaultConfig();

        // Reload config from file
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Load and cache all configuration values
        loadNetworkSettings();
        loadStorageSettings();
        loadBlacklistedItems();
        loadPermissionSettings();
        loadLoggingSettings();
        loadDebugSettings();

        plugin.getLogger().info("Configuration loaded successfully!");
        plugin.getLogger().info("All storage disks hardcoded to " + HARDCODED_CELLS_PER_DISK + " cells");
        plugin.getLogger().info("Items per cell are tier-specific: 1K=127, 4K=508, 16K=2032, 64K=8128");
    }

    private void loadNetworkSettings() {
        maxNetworkBlocks = config.getInt("network.max_blocks", 128);
        operationCooldown = config.getLong("network.operation_cooldown", 100);
    }

    private void loadStorageSettings() {
        maxDriveBaySlots = config.getInt("storage.drive_bay_slots", 7);

        // NOTE: default_cells_per_disk config option is now IGNORED - hardcoded to 64
        if (config.contains("storage.default_cells_per_disk")) {
            plugin.getLogger().warning("Config option 'default_cells_per_disk' is deprecated and ignored. All disks now have " + HARDCODED_CELLS_PER_DISK + " cells.");
        }

        // NOTE: max_items_per_cell config option is now IGNORED - tier-specific values are hardcoded
        if (config.contains("storage.max_items_per_cell")) {
            plugin.getLogger().warning("Config option 'max_items_per_cell' is deprecated and ignored. Items per cell are now tier-specific and hardcoded.");
        }
    }

    private void loadBlacklistedItems() {
        blacklistedItems = new HashSet<>();
        List<String> blacklistedItemNames = config.getStringList("blacklisted_items");

        for (String itemName : blacklistedItemNames) {
            try {
                // Skip MSS items as they're handled separately
                if (itemName.startsWith("massstorageserver:")) {
                    continue;
                }

                Material material = Material.valueOf(itemName.toUpperCase());
                blacklistedItems.add(material);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in blacklist: " + itemName);
            }
        }
    }

    private void loadPermissionSettings() {
        requireUsePermission = config.getBoolean("permissions.require_use_permission", true);
        requireCraftPermission = config.getBoolean("permissions.require_craft_permission", true);
        requireAdminPermission = config.getBoolean("permissions.require_admin_permission", true);
    }

    private void loadLoggingSettings() {
        logNetworkOperations = config.getBoolean("logging.log_network_operations", false);
        logStorageOperations = config.getBoolean("logging.log_storage_operations", false);
        logDatabaseOperations = config.getBoolean("logging.log_database_operations", false);
    }

    private void loadDebugSettings() {
        debugEnabled = config.getBoolean("debug.enabled", false);
        debugVerbose = config.getBoolean("debug.verbose", false);
    }

    // Getter methods for configuration values
    public int getMaxNetworkBlocks() {
        return maxNetworkBlocks;
    }

    public long getOperationCooldown() {
        return operationCooldown;
    }

    /**
     * REMOVED: Items per cell are now tier-specific and hardcoded
     * 1K = 127, 4K = 508, 16K = 2032, 64K = 8128
     * Use ItemManager.getItemsPerCellForTier(tier) instead
     */

    /**
     * HARDCODED: All storage disks have exactly 64 cells
     * This method now ignores any config value and always returns 64
     */
    public int getDefaultCellsPerDisk() {
        return HARDCODED_CELLS_PER_DISK;
    }

    public int getMaxDriveBaySlots() {
        return maxDriveBaySlots;
    }

    public Set<Material> getBlacklistedItems() {
        return new HashSet<>(blacklistedItems);
    }

    public boolean isItemBlacklisted(Material material) {
        return blacklistedItems.contains(material);
    }

    public boolean isRequireUsePermission() {
        return requireUsePermission;
    }

    public boolean isRequireCraftPermission() {
        return requireCraftPermission;
    }

    public boolean isRequireAdminPermission() {
        return requireAdminPermission;
    }

    public boolean isLogNetworkOperations() {
        return logNetworkOperations;
    }

    public boolean isLogStorageOperations() {
        return logStorageOperations;
    }

    public boolean isLogDatabaseOperations() {
        return logDatabaseOperations;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public boolean isDebugVerbose() {
        return debugVerbose;
    }

    // Database configuration getters
    public int getDatabaseMaxPoolSize() {
        return config.getInt("database.connection_pool.maximum_pool_size", 10);
    }

    public int getDatabaseMinIdle() {
        return config.getInt("database.connection_pool.minimum_idle", 2);
    }

    public long getDatabaseConnectionTimeout() {
        return config.getLong("database.connection_pool.connection_timeout", 30000);
    }

    public long getDatabaseIdleTimeout() {
        return config.getLong("database.connection_pool.idle_timeout", 600000);
    }

    public long getDatabaseMaxLifetime() {
        return config.getLong("database.connection_pool.max_lifetime", 1800000);
    }

    public String getDatabaseJournalMode() {
        return config.getString("database.sqlite.journal_mode", "WAL");
    }

    public String getDatabaseSynchronous() {
        return config.getString("database.sqlite.synchronous", "NORMAL");
    }

    public long getDatabaseBusyTimeout() {
        return config.getLong("database.sqlite.busy_timeout", 30000);
    }

    public int getDatabaseCacheSize() {
        return config.getInt("database.sqlite.cache_size", 10000);
    }

    /**
     * Reload the configuration from file
     */
    public void reloadConfig() {
        loadConfig();
    }

    /**
     * Save the current configuration to file
     */
    public void saveConfig() {
        plugin.saveConfig();
    }
}