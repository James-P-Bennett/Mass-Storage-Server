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
    private int maxItemsPerCell;
    private int defaultCellsPerDisk;
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
    }

    private void loadNetworkSettings() {
        maxNetworkBlocks = config.getInt("network.max_blocks", 128);
        operationCooldown = config.getLong("network.operation_cooldown", 100);
    }

    private void loadStorageSettings() {
        maxItemsPerCell = config.getInt("storage.max_items_per_cell", 1024);
        defaultCellsPerDisk = config.getInt("storage.default_cells_per_disk", 27);
        maxDriveBaySlots = config.getInt("storage.drive_bay_slots", 8);
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

    public int getMaxItemsPerCell() {
        return maxItemsPerCell;
    }

    public int getDefaultCellsPerDisk() {
        return defaultCellsPerDisk;
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