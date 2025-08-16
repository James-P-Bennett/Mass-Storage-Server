package org.jamesphbennett.massstorageserver.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RecipeManager {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;
    private final ConfigManager configManager;

    private int registeredRecipeCount = 0;

    // Store recipes that use custom components (these won't be registered as vanilla recipes)
    private final Map<String, CustomRecipeData> customComponentRecipes = new HashMap<>();

    public RecipeManager(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.configManager = plugin.getConfigManager();
    }

    /**
     * Data class to store custom recipe information
     *
     * @param ingredients Object can be Material or CustomComponent
     */
        public record CustomRecipeData(String[] shape, Map<Character, Object> ingredients, String resultType,
                                       int resultAmount, String description) {
    }

    /**
         * Represents a custom MSS component ingredient
         */
        public record CustomComponent(String type, String tier) {

        @Override
            public String toString() {
                return "mss:" + type + ":" + tier;
            }
        }

    public void registerRecipes() {
        if (!configManager.areRecipesEnabled()) {
            plugin.getLogger().info("Recipes are disabled in configuration - skipping recipe registration");
            return;
        }

        registeredRecipeCount = 0;
        customComponentRecipes.clear();
        Set<String> recipeNames = configManager.getRecipeNames();

        plugin.getLogger().info("Found " + recipeNames.size() + " recipes in configuration");

        for (String recipeName : recipeNames) {
            if (configManager.isRecipeEnabled(recipeName)) {
                try {
                    if (hasCustomComponents(recipeName)) {
                        registerCustomComponentRecipe(recipeName);
                        plugin.getLogger().info("Registered custom component recipe: " + recipeName);
                    } else {
                        registerVanillaRecipe(recipeName);
                        plugin.getLogger().info("Registered vanilla recipe: " + recipeName);
                    }
                    registeredRecipeCount++;
                } catch (Exception e) {
                    plugin.getLogger().severe("Failed to register recipe '" + recipeName + "': " + e.getMessage());
                    plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
                }
            } else {
                plugin.getLogger().info("Recipe '" + recipeName + "' is disabled in configuration");
            }
        }

        plugin.getLogger().info("Successfully registered " + registeredRecipeCount + " recipes (" +
                customComponentRecipes.size() + " custom component recipes, " +
                (registeredRecipeCount - customComponentRecipes.size()) + " vanilla recipes)");
    }

    /**
     * Check if a recipe uses custom MSS components
     */
    private boolean hasCustomComponents(String recipeName) {
        ConfigurationSection recipeSection = configManager.getRecipeSection(recipeName);
        if (recipeSection == null) return false;

        ConfigurationSection ingredientsSection = recipeSection.getConfigurationSection("ingredients");
        if (ingredientsSection == null) return false;

        for (String key : ingredientsSection.getKeys(false)) {
            String materialName = ingredientsSection.getString(key);
            if (materialName != null && materialName.startsWith("mss:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Register a recipe that uses custom components (stored for manual validation)
     */
    private void registerCustomComponentRecipe(String recipeName) throws Exception {
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
        String description = recipeSection.getString("description", "No description");

        // Get shape
        List<String> shapeList = recipeSection.getStringList("shape");
        if (shapeList.size() != 3) {
            throw new IllegalArgumentException("Recipe shape must have exactly 3 rows for: " + recipeName);
        }
        String[] shape = shapeList.toArray(new String[3]);

        // Parse ingredients (mix of vanilla materials and custom components)
        Map<Character, Object> ingredients = new HashMap<>();
        ConfigurationSection ingredientsSection = recipeSection.getConfigurationSection("ingredients");
        if (ingredientsSection == null) {
            throw new IllegalArgumentException("Recipe ingredients section not found for: " + recipeName);
        }

        for (String key : ingredientsSection.getKeys(false)) {
            String materialName = ingredientsSection.getString(key);
            if (materialName == null) continue;

            char ingredientChar = key.charAt(0);

            if (materialName.startsWith("mss:")) {
                // Parse custom component
                CustomComponent component = parseCustomComponent(materialName, recipeName, key);
                ingredients.put(ingredientChar, component);
            } else {
                // Parse vanilla material
                Material material = parseVanillaMaterial(materialName, recipeName, key);
                ingredients.put(ingredientChar, material);
            }
        }

        // Store the custom recipe data
        CustomRecipeData recipeData = new CustomRecipeData(shape, ingredients, resultItemType, resultAmount, description);
        customComponentRecipes.put(recipeName, recipeData);

        // ALSO register a display-only vanilla recipe for the recipe book
        registerDisplayRecipe(recipeName, recipeData);
    }

    /**
     * Register a vanilla recipe (no custom components)
     */
    private void registerVanillaRecipe(String recipeName) throws Exception {
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

        // Get and set ingredients (all vanilla for this method)
        ConfigurationSection ingredientsSection = recipeSection.getConfigurationSection("ingredients");
        if (ingredientsSection == null) {
            throw new IllegalArgumentException("Recipe ingredients section not found for: " + recipeName);
        }

        for (String key2 : ingredientsSection.getKeys(false)) {
            String materialName = ingredientsSection.getString(key2);
            if (materialName == null) continue;

            Material material = parseVanillaMaterial(materialName, recipeName, key2);
            if (material != null) {
                recipe.setIngredient(key2.charAt(0), material);
            }
        }

        // Register the recipe with the server
        plugin.getServer().addRecipe(recipe);

        String description = recipeSection.getString("description", "No description");
        plugin.getLogger().info("Registered vanilla recipe '" + recipeName + "' (" + description + ") -> " + resultAmount + "x " + resultItemType);
    }

    /**
     * Register a display-only recipe for the recipe book using placeholder materials
     */
    private void registerDisplayRecipe(String recipeName, CustomRecipeData recipeData) {
        // Create result item
        ItemStack result = createResultItem(recipeData.resultType, recipeData.resultAmount, recipeName);
        if (result == null) {
            throw new IllegalArgumentException("Could not create result item for display recipe: " + recipeName);
        }

        // Create display recipe with "_display" suffix to avoid conflicts
        NamespacedKey key = new NamespacedKey(plugin, recipeName + "_display");
        ShapedRecipe displayRecipe = new ShapedRecipe(key, result);

        // Set the shape
        displayRecipe.shape(recipeData.shape[0], recipeData.shape[1], recipeData.shape[2]);

        // Convert ingredients to recipe choices that accept both placeholder and custom components
        for (Map.Entry<Character, Object> entry : recipeData.ingredients.entrySet()) {
            char ingredientChar = entry.getKey();
            Object ingredient = entry.getValue();

            if (ingredient instanceof Material material) {
                // Use vanilla material as-is
                displayRecipe.setIngredient(ingredientChar, material);
            } else if (ingredient instanceof CustomComponent component) {
                // Create recipe choice that accepts both placeholder and actual component
                org.bukkit.inventory.RecipeChoice choice = createComponentChoice(component);
                displayRecipe.setIngredient(ingredientChar, choice);
            }
        }

        // Register the display recipe with the server
        plugin.getServer().addRecipe(displayRecipe);

        plugin.getLogger().info("Registered display recipe for '" + recipeName + "' (shows actual components in recipe book)");
    }

    /**
     * Get placeholder material for custom components in display recipes
     */
    private Material getPlaceholderMaterial(CustomComponent component) {
        return switch (component.type) {
            case "disk_platter" ->
                // Use the actual material for the disk platter tier so recipe book shows correct item
                    switch (component.tier != null ? component.tier : "1k") {
                        case "4k" -> Material.GOLD_NUGGET;
                        case "16k" -> Material.CONDUIT;
                        case "64k" -> Material.HEART_OF_THE_SEA;
                        default -> Material.STONE_BUTTON;
                    };
            case "storage_disk_housing" -> Material.BAMBOO_PRESSURE_PLATE;
            default -> Material.BARRIER; // Fallback for unknown components
        };
    }

    /**
     * Create a recipe choice that only accepts the actual custom component
     */
    private org.bukkit.inventory.RecipeChoice createComponentChoice(CustomComponent component) {
        // Create the actual custom component item
        ItemStack customItem = createCustomComponentItem(component);

        if (customItem != null) {
            // Only accept the custom component - no cycling with vanilla materials
            return new org.bukkit.inventory.RecipeChoice.ExactChoice(customItem);
        } else {
            // Fallback to placeholder material if custom item creation fails
            Material placeholderMaterial = getPlaceholderMaterial(component);
            return new org.bukkit.inventory.RecipeChoice.MaterialChoice(placeholderMaterial);
        }
    }

    /**
     * Create the actual custom component item for recipe choices
     */
    private ItemStack createCustomComponentItem(CustomComponent component) {
        return switch (component.type) {
            case "disk_platter" -> itemManager.createDiskPlatter(component.tier != null ? component.tier : "1k");
            case "storage_disk_housing" -> itemManager.createStorageDiskHousing();
            default -> null;
        };
    }
    private CustomComponent parseCustomComponent(String componentString, String recipeName, String ingredientKey) throws Exception {
        String[] parts = componentString.split(":");
        if (parts.length < 2 || !parts[0].equals("mss")) {
            throw new Exception("Invalid custom component format '" + componentString + "' for ingredient '" + ingredientKey + "' in recipe '" + recipeName + "'");
        }

        if (parts.length == 2) {
            // Format: "mss:storage_disk_housing"
            return new CustomComponent(parts[1], null);
        } else if (parts.length == 3) {
            // Format: "mss:disk_platter:1k"
            return new CustomComponent(parts[1], parts[2]);
        } else {
            throw new Exception("Invalid custom component format '" + componentString + "' for ingredient '" + ingredientKey + "' in recipe '" + recipeName + "'");
        }
    }

    /**
     * Parse vanilla material
     */
    private Material parseVanillaMaterial(String materialName, String recipeName, String ingredientKey) throws Exception {
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new Exception("Invalid material '" + materialName + "' for ingredient '" + ingredientKey + "' in recipe '" + recipeName + "'");
        }
    }

    /**
     * Check if a custom component recipe matches the given crafting matrix
     */
    public String matchCustomComponentRecipe(ItemStack[] craftingMatrix) {
        for (Map.Entry<String, CustomRecipeData> entry : customComponentRecipes.entrySet()) {
            if (matchesCustomRecipe(craftingMatrix, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Check if the crafting matrix matches a specific custom recipe
     */
    private boolean matchesCustomRecipe(ItemStack[] craftingMatrix, CustomRecipeData recipeData) {
        // Convert 9-slot matrix to 3x3 for easier checking
        ItemStack[][] grid = new ItemStack[3][3];
        for (int i = 0; i < 9; i++) {
            grid[i / 3][i % 3] = craftingMatrix[i];
        }

        // Check each position in the recipe shape
        for (int row = 0; row < 3; row++) {
            String shapeRow = recipeData.shape[row];
            for (int col = 0; col < 3; col++) {
                char expectedChar = col < shapeRow.length() ? shapeRow.charAt(col) : ' ';
                ItemStack actualItem = grid[row][col];

                if (expectedChar == ' ') {
                    // Expecting empty slot
                    if (actualItem != null && !actualItem.getType().isAir()) {
                        return false;
                    }
                } else {
                    // Expecting specific ingredient
                    Object expectedIngredient = recipeData.ingredients.get(expectedChar);
                    if (expectedIngredient == null) {
                        return false; // Unknown ingredient character
                    }

                    if (!matchesIngredient(actualItem, expectedIngredient)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Check if an item matches an ingredient (vanilla material or custom component)
     */
    private boolean matchesIngredient(ItemStack item, Object ingredient) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        if (ingredient instanceof Material material) {
            return item.getType() == material;
        } else if (ingredient instanceof CustomComponent component) {
            return matchesCustomComponent(item, component);
        }

        return false;
    }

    /**
     * Check if an item matches a custom component specification
     */
    private boolean matchesCustomComponent(ItemStack item, CustomComponent component) {
        return switch (component.type) {
            case "disk_platter" -> itemManager.isDiskPlatter(item) &&
                    (component.tier == null || component.tier.equals(itemManager.getDiskPlatterTier(item)));
            case "storage_disk_housing" -> itemManager.isStorageDiskHousing(item);
            default -> false;
        };
    }

    /**
     * Get the result item for a custom component recipe
     */
    public ItemStack getCustomRecipeResult(String recipeName) {
        CustomRecipeData recipeData = customComponentRecipes.get(recipeName);
        if (recipeData == null) return null;

        return createResultItem(recipeData.resultType, recipeData.resultAmount, recipeName);
    }

    /**
     * Create result items for both vanilla and custom recipes
     */
    private ItemStack createResultItem(String itemType, int amount, String recipeName) {
        ItemStack result;

        switch (itemType.toLowerCase()) {
            // Network blocks
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

            // Storage disks
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

            // Components
            case "disk_platter_1k":
                result = itemManager.createDiskPlatter("1k");
                break;
            case "disk_platter_4k":
                result = itemManager.createDiskPlatter("4k");
                break;
            case "disk_platter_16k":
                result = itemManager.createDiskPlatter("16k");
                break;
            case "disk_platter_64k":
                result = itemManager.createDiskPlatter("64k");
                break;
            case "storage_disk_housing":
                result = itemManager.createStorageDiskHousing();
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
     * Check if a recipe is a custom component recipe
     */
    public boolean isCustomComponentRecipe(String recipeName) {
        return customComponentRecipes.containsKey(recipeName);
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
        // Check custom component recipes first
        CustomRecipeData customRecipe = customComponentRecipes.get(recipeName);
        if (customRecipe != null) {
            return customRecipe.description;
        }

        // Fall back to config
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
        boolean isCustom = isCustomComponentRecipe(recipeName);

        ConfigurationSection resultSection = recipeSection.getConfigurationSection("result");
        String resultItem = resultSection != null ? resultSection.getString("item", "unknown") : "unknown";
        int resultAmount = resultSection != null ? resultSection.getInt("amount", 1) : 1;

        player.sendMessage(Component.text("=== Recipe: " + recipeName + " ===", NamedTextColor.GOLD));
        player.sendMessage(Component.text("Status: " + (enabled ? "Enabled" : "Disabled"),
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED));
        player.sendMessage(Component.text("Type: " + (isCustom ? "Custom Component Recipe" : "Vanilla Recipe"),
                isCustom ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
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
                String displayMaterial = (material != null && material.startsWith("mss:")) ?
                        Component.text(material, NamedTextColor.LIGHT_PURPLE).content() :
                        (material != null ? material : "null");
                player.sendMessage(Component.text("  " + key + " = " + displayMaterial, NamedTextColor.WHITE));
            }
        }

        if (isCustom) {
            player.sendMessage(Component.text("Note: This recipe uses custom components and requires manual crafting validation.", NamedTextColor.GRAY));
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
        player.sendMessage(Component.text("Registered: " + registeredRecipeCount + "/" + recipeNames.size() +
                " (" + customComponentRecipes.size() + " custom)", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("", NamedTextColor.WHITE));

        for (String recipeName : recipeNames) {
            boolean enabled = configManager.isRecipeEnabled(recipeName);
            boolean isCustom = isCustomComponentRecipe(recipeName);
            String description = getRecipeDescription(recipeName);

            Component message = Component.text("• " + recipeName + ": ", NamedTextColor.WHITE)
                    .append(Component.text(enabled ? "Enabled" : "Disabled",
                            enabled ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .append(Component.text(isCustom ? " [Custom]" : " [Vanilla]",
                            isCustom ? NamedTextColor.LIGHT_PURPLE : NamedTextColor.YELLOW))
                    .append(Component.text(" - " + description, NamedTextColor.GRAY));

            player.sendMessage(message);
        }

        player.sendMessage(Component.text("", NamedTextColor.WHITE));
        player.sendMessage(Component.text("Use '/mss recipe <name>' for detailed recipe information", NamedTextColor.YELLOW));
    }
}