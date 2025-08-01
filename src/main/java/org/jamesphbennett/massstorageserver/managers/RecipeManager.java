package org.jamesphbennett.massstorageserver.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.util.List;
import java.util.Set;

public class RecipeManager {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;
    private final ConfigManager configManager;

    private int registeredRecipeCount = 0;

    public RecipeManager(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.configManager = plugin.getConfigManager();
    }

    public void registerRecipes() {
        if (!configManager.areRecipesEnabled()) {
            plugin.getLogger().info("Recipes are disabled in configuration - skipping recipe registration");
            return;
        }

        registeredRecipeCount = 0;
        Set<String> recipeNames = configManager.getRecipeNames();

        plugin.getLogger().info("Found " + recipeNames.size() + " recipes in configuration");

        for (String recipeName : recipeNames) {
            if (configManager.isRecipeEnabled(recipeName)) {
                try {
                    registerSingleRecipe(recipeName);
                    registeredRecipeCount++;
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to register recipe '" + recipeName + "': " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                plugin.getLogger().info("Recipe '" + recipeName + "' is disabled in configuration");
            }
        }

        plugin.getLogger().info("Successfully registered " + registeredRecipeCount + " recipes out of " + recipeNames.size() + " configured recipes");
    }

    private void registerSingleRecipe(String recipeName) throws Exception {
        ConfigurationSection recipeSection = configManager.getRecipeSection(recipeName);
        if (recipeSection == null) {
            throw new IllegalArgumentException("Recipe section not found for: " + recipeName);
        }

        // Get recipe result configuration
        ConfigurationSection resultSection = recipeSection.getConfigurationSection("result");
        if (resultSection == null) {
            throw new IllegalArgumentException("Recipe result section not found for: " + recipeName);
        }

        String resultItemType = resultSection.getString("item");
        int resultAmount = resultSection.getInt("amount", 1);

        if (resultItemType == null) {
            throw new IllegalArgumentException("Recipe result item not specified for: " + recipeName);
        }

        // Create the result ItemStack
        ItemStack result = createResultItem(resultItemType, resultAmount, recipeName);
        if (result == null) {
            throw new IllegalArgumentException("Could not create result item for recipe: " + recipeName);
        }

        // Create the recipe
        NamespacedKey key = new NamespacedKey(plugin, recipeName);
        ShapedRecipe recipe = new ShapedRecipe(key, result);

        // Get and set the shape
        List<String> shape = recipeSection.getStringList("shape");
        if (shape.size() != 3) {
            throw new IllegalArgumentException("Recipe shape must have exactly 3 rows for: " + recipeName);
        }

        recipe.shape(shape.get(0), shape.get(1), shape.get(2));

        // Get and set ingredients
        ConfigurationSection ingredientsSection = recipeSection.getConfigurationSection("ingredients");
        if (ingredientsSection == null) {
            throw new IllegalArgumentException("Recipe ingredients section not found for: " + recipeName);
        }

        for (String key2 : ingredientsSection.getKeys(false)) {
            String materialName = ingredientsSection.getString(key2);
            if (materialName == null) {
                continue;
            }

            Material material = parseMaterial(materialName, recipeName, key2);
            if (material != null) {
                recipe.setIngredient(key2.charAt(0), material);
            }
        }

        // Register the recipe with the server
        plugin.getServer().addRecipe(recipe);

        String description = recipeSection.getString("description", "No description");
        plugin.getLogger().info("Registered recipe '" + recipeName + "' (" + description + ") -> " + resultAmount + "x " + resultItemType);
    }

    private Material parseMaterial(String materialName, String recipeName, String ingredientKey) throws Exception {
        // Handle special cases for MSS items
        switch (materialName) {
            case "ACACIA_PRESSURE_PLATE" -> {
                // This might be referring to a 1K disk in disk upgrade recipes
                return Material.ACACIA_PRESSURE_PLATE;
            }
            case "HEAVY_WEIGHTED_PRESSURE_PLATE" -> {
                // This might be referring to a 4K disk in disk upgrade recipes
                return Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
            }
            case "LIGHT_WEIGHTED_PRESSURE_PLATE" -> {
                // This might be referring to a 16K disk in disk upgrade recipes
                return Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
            }
        }

        // Try to parse as regular material
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid material '" + materialName + "' for ingredient '" + ingredientKey + "' in recipe '" + recipeName + "'");
        }
    }

    private ItemStack createResultItem(String itemType, int amount, String recipeName) {
        ItemStack result;

        switch (itemType.toLowerCase()) {
            case "storage_server":
                result = itemManager.createStorageServer();
                break;
            case "drive_bay":
                result = itemManager.createDriveBay();
                break;
            case "mss_terminal":
                result = itemManager.createMSSTerminal();
                break;
            case "network_cable":
                result = itemManager.createNetworkCable();
                break;
            case "storage_disk", "storage_disk_1k":
                // Use generic crafter info for recipe preview
                result = itemManager.createStorageDisk("00000000-0000-0000-0000-000000000000", "Unknown");
                break;
            case "storage_disk_4k":
                result = itemManager.createStorageDisk4k("00000000-0000-0000-0000-000000000000", "Unknown");
                break;
            case "storage_disk_16k":
                result = itemManager.createStorageDisk16k("00000000-0000-0000-0000-000000000000", "Unknown");
                break;
            case "storage_disk_64k":
                result = itemManager.createStorageDisk64k("00000000-0000-0000-0000-000000000000", "Unknown");
                break;
            default:
                plugin.getLogger().warning("Unknown item type '" + itemType + "' for recipe '" + recipeName + "'");
                return null;
        }

        if (result != null && amount != 1) {
            result.setAmount(amount);
        }

        return result;
    }

    /**
     * Reload recipes from configuration
     */
    public void reloadRecipes() {
        plugin.getLogger().info("Reloading recipes from configuration...");

        // Clear existing recipes (this is complex, so we'll just log a warning)
        plugin.getLogger().warning("Note: Existing recipes are not cleared - server restart may be required for recipe changes");

        // Re-register recipes
        registerRecipes();
    }

    /**
     * Get the number of successfully registered recipes
     */
    public int getRegisteredRecipeCount() {
        return registeredRecipeCount;
    }

    /**
     * Get recipe description by name
     */
    public String getRecipeDescription(String recipeName) {
        ConfigurationSection recipeSection = configManager.getRecipeSection(recipeName);
        if (recipeSection != null) {
            return recipeSection.getString("description", "No description available");
        }
        return "Recipe not found";
    }

    /**
     * Send recipe information to a player (for admin commands)
     */
    public void sendRecipeInfo(org.bukkit.entity.Player player, String recipeName) {
        ConfigurationSection recipeSection = configManager.getRecipeSection(recipeName);
        if (recipeSection == null) {
            player.sendMessage(Component.text("Recipe '" + recipeName + "' not found!", NamedTextColor.RED));
            return;
        }

        boolean enabled = configManager.isRecipeEnabled(recipeName);
        String description = getRecipeDescription(recipeName);

        ConfigurationSection resultSection = recipeSection.getConfigurationSection("result");
        String resultItem = resultSection != null ? resultSection.getString("item", "unknown") : "unknown";
        int resultAmount = resultSection != null ? resultSection.getInt("amount", 1) : 1;

        player.sendMessage(Component.text("=== Recipe: " + recipeName + " ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Status: " + (enabled ? "Enabled" : "Disabled"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        player.sendMessage(Component.text("Description: " + description, NamedTextColor.GRAY));
        player.sendMessage(Component.text("Result: " + resultAmount + "x " + resultItem, NamedTextColor.YELLOW));

        // Show shape
        List<String> shape = recipeSection.getStringList("shape");
        if (!shape.isEmpty()) {
            player.sendMessage(Component.text("Shape:", NamedTextColor.AQUA));
            for (String row : shape) {
                player.sendMessage(Component.text("  " + row, NamedTextColor.WHITE));
            }
        }

        // Show ingredients
        ConfigurationSection ingredientsSection = recipeSection.getConfigurationSection("ingredients");
        if (ingredientsSection != null) {
            player.sendMessage(Component.text("Ingredients:", NamedTextColor.AQUA));
            for (String key : ingredientsSection.getKeys(false)) {
                String material = ingredientsSection.getString(key);
                player.sendMessage(Component.text("  " + key + " = " + material, NamedTextColor.WHITE));
            }
        }
    }

    /**
     * List all recipes to a player (for admin commands)
     */
    public void listRecipes(org.bukkit.entity.Player player) {
        Set<String> recipeNames = configManager.getRecipeNames();

        player.sendMessage(Component.text("=== Mass Storage Server Recipes ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Recipes system: " + (configManager.areRecipesEnabled() ? "Enabled" : "Disabled"),
                configManager.areRecipesEnabled() ? NamedTextColor.GREEN : NamedTextColor.RED));
        player.sendMessage(Component.text("Registered: " + registeredRecipeCount + "/" + recipeNames.size(), NamedTextColor.YELLOW));
        player.sendMessage(Component.text("", NamedTextColor.WHITE));

        for (String recipeName : recipeNames) {
            boolean enabled = configManager.isRecipeEnabled(recipeName);
            String description = getRecipeDescription(recipeName);

            Component message = Component.text("• " + recipeName + ": ", NamedTextColor.WHITE)
                    .append(Component.text(enabled ? "Enabled" : "Disabled",
                            enabled ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .append(Component.text(" - " + description, NamedTextColor.GRAY));

            player.sendMessage(message);
        }

        player.sendMessage(Component.text("", NamedTextColor.WHITE));
        player.sendMessage(Component.text("Use '/mss recipe <name>' for detailed recipe information", NamedTextColor.YELLOW));
    }
}