package org.jamesphbennett.massstorageserver.managers;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.jamesphbennett.massstorageserver.MassStorageServer;

public class RecipeManager {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;

    public RecipeManager(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
    }

    public void registerRecipes() {
        registerStorageServerRecipe();
        registerDriveBayRecipe();
        registerMSSTerminalRecipe();
        registerStorageDiskRecipe();

        plugin.getLogger().info("Registered " + 4 + " custom recipes");
    }

    private void registerStorageServerRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "storage_server");
        ItemStack result = itemManager.createStorageServer();

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(
                "GSG",
                "SCS",
                "GSG"
        );

        recipe.setIngredient('G', Material.GLASS);
        recipe.setIngredient('S', Material.STONE);
        recipe.setIngredient('C', Material.CHEST);

        plugin.getServer().addRecipe(recipe);
    }

    private void registerDriveBayRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "drive_bay");
        ItemStack result = itemManager.createDriveBay();

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(
                "SGS",
                "GHG",
                "SGS"
        );

        recipe.setIngredient('S', Material.STONE);
        recipe.setIngredient('G', Material.GLASS);
        recipe.setIngredient('H', Material.HOPPER);

        plugin.getServer().addRecipe(recipe);
    }

    private void registerMSSTerminalRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "mss_terminal");
        ItemStack result = itemManager.createMSSTerminal();

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(
                "SRS",
                "RGR",
                "SRS"
        );

        recipe.setIngredient('S', Material.STONE);
        recipe.setIngredient('R', Material.REDSTONE);
        recipe.setIngredient('G', Material.GLASS_PANE);

        plugin.getServer().addRecipe(recipe);
    }

    private void registerStorageDiskRecipe() {
        NamespacedKey key = new NamespacedKey(plugin, "storage_disk");

        // Create a template disk for the recipe preview - use generic info
        ItemStack result = itemManager.createStorageDisk("00000000-0000-0000-0000-000000000000", "Unknown");

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(
                "IGI",
                "GDG",
                "IGI"
        );

        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('G', Material.GLASS);
        recipe.setIngredient('D', Material.DIAMOND);

        plugin.getServer().addRecipe(recipe);
    }
}