package org.jamesphbennett.massstorageserver.managers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private final MassStorageServer plugin;
    private FileConfiguration config;
    private FileConfiguration recipesConfig;

    // Cached configuration values
    private int maxNetworkBlocks;
    private int maxNetworkCables;
    private long operationCooldown;
    private int maxDriveBaySlots;
    private Set<Material> blacklistedItems;
    private boolean requireUsePermission;
    private boolean requireCraftPermission;

    // Recipe configuration values
    private boolean recipesEnabled;
    private boolean showUnlockMessages;

    // HARDCODED: All disks have 64 cells - no longer configurable
    private static final int HARDCODED_CELLS_PER_DISK = 64;

    // HARDCODED: Items per cell are tier-specific - no longer configurable
    // 1K = 127, 4K = 508, 16K = 2032, 64K = 8128

    public ConfigManager(MassStorageServer plugin) {
        this.plugin = plugin;
        loadConfig();
        loadRecipesConfig();
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
        plugin.getLogger().info("Maximum network cables per network: " + maxNetworkCables);
    }

    private void loadRecipesConfig() {
        File recipesFile = new File(plugin.getDataFolder(), "recipes.yml");

        // Save default recipes config if it doesn't exist
        if (!recipesFile.exists()) {
            plugin.saveResource("recipes.yml", false);
        }

        recipesConfig = YamlConfiguration.loadConfiguration(recipesFile);

        // Load defaults from jar if available
        InputStream defConfigStream = plugin.getResource("recipes.yml");
        if (defConfigStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream));
            recipesConfig.setDefaults(defConfig);
        }

        // Load recipe settings
        loadRecipeSettings();

        plugin.getLogger().info("Recipes configuration loaded successfully!");
        plugin.getLogger().info("Recipes enabled: " + recipesEnabled);
        plugin.getLogger().info("Recipe unlock messages: " + showUnlockMessages);
    }

    private void loadRecipeSettings() {
        recipesEnabled = recipesConfig.getBoolean("settings.enabled", true);
        showUnlockMessages = recipesConfig.getBoolean("settings.show_unlock_messages", false);
    }

    private void loadNetworkSettings() {
        maxNetworkBlocks = config.getInt("network.max_blocks", 128);
        maxNetworkCables = config.getInt("network.max_cables", 800);
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
                Material material = Material.valueOf(itemName.toUpperCase());
                blacklistedItems.add(material);
                plugin.getLogger().info("Blacklisted item: " + material.name());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in blacklist: " + itemName);
            }
        }

        plugin.getLogger().info("Loaded " + blacklistedItems.size() + " blacklisted items from config");
    }

    private void loadPermissionSettings() {
        requireUsePermission = config.getBoolean("permissions.require_use_permission", true);
        requireCraftPermission = config.getBoolean("permissions.require_craft_permission", true);
    }

    private void loadLoggingSettings() {
    }

    private void loadDebugSettings() {
    }

    // Getter methods for configuration values
    public int getMaxNetworkBlocks() {
        return maxNetworkBlocks;
    }

    public int getMaxNetworkCables() {
        return maxNetworkCables;
    }

    public long getOperationCooldown() {
        return operationCooldown;
    }

    /*
      REMOVED: Items per cell are now tier-specific and hardcoded
      1K = 127, 4K = 508, 16K = 2032, 64K = 8128
      Use ItemManager.getItemsPerCellForTier(tier) instead
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

    public boolean isItemBlacklisted(Material material) {
        return blacklistedItems.contains(material);
    }

    public boolean isRequireUsePermission() {
        return requireUsePermission;
    }

    public boolean isRequireCraftPermission() {
        return requireCraftPermission;
    }

    // Recipe configuration getters
    public boolean areRecipesEnabled() {
        return recipesEnabled;
    }

    /**
     * Check if a specific recipe is enabled
     */
    public boolean isRecipeEnabled(String recipeName) {
        if (!recipesEnabled) {
            return false;
        }
        return recipesConfig.getBoolean("recipes." + recipeName + ".enabled", false);
    }

    /**
     * Get recipe configuration section
     */
    public ConfigurationSection getRecipeSection(String recipeName) {
        return recipesConfig.getConfigurationSection("recipes." + recipeName);
    }

    /**
     * Get all recipe names from the configuration
     */
    public Set<String> getRecipeNames() {
        Set<String> recipeNames = new HashSet<>();
        ConfigurationSection recipesSection = recipesConfig.getConfigurationSection("recipes");
        if (recipesSection != null) {
            recipeNames.addAll(recipesSection.getKeys(false));
        }
        return recipeNames;
    }

    /**
     * Reload both main configuration and recipes configuration from file
     */
    public void reloadConfig() {
        loadConfig();
        loadRecipesConfig();
    }

}