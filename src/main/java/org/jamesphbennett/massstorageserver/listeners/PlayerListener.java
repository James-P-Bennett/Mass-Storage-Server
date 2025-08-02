package org.jamesphbennett.massstorageserver.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.managers.ItemManager;

import java.sql.SQLException;

public class PlayerListener implements Listener {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;

    public PlayerListener(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
    }

    /**
     * Handle chat events for terminal search input
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().trim();

        // Check if player is awaiting search input
        if (plugin.getGUIManager().isAwaitingSearchInput(player)) {
            plugin.getLogger().info("Player " + player.getName() + " sent search input: '" + message + "'");

            // Cancel the chat event so it doesn't appear in public chat
            event.setCancelled(true);

            // Handle the search input on the main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                boolean handled = plugin.getGUIManager().handleSearchInput(player, message);
                if (!handled) {
                    player.sendMessage(Component.text("Search input expired. Please try again.", NamedTextColor.RED));
                }
            });
        }
    }

    /**
     * Clean up search input when player disconnects
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Cancel any pending search input
        plugin.getGUIManager().cancelSearchInput(player);

        // Close any open GUIs
        plugin.getGUIManager().closeGUI(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe shapedRecipe)) {
            return;
        }

        String recipeKey = shapedRecipe.getKey().getKey();
        String namespace = shapedRecipe.getKey().getNamespace();

        // CRITICAL: Check if any MSS items are being used in non-MSS recipes
        if (!namespace.equals(plugin.getName().toLowerCase())) {
            // This is not an MSS recipe - check if any MSS items are being used
            if (containsMSSItems(event)) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Mass Storage components cannot be used in vanilla recipes!", NamedTextColor.RED));
                plugin.getLogger().info("Prevented player " + player.getName() + " from using MSS items in vanilla recipe: " + recipeKey);
                return;
            }
        }

        // Check if this is any of our MSS recipes
        if (isMSSRecipe(shapedRecipe)) {
            // Check crafting permission
            if (plugin.getConfigManager().isRequireCraftPermission() && !player.hasPermission("massstorageserver.craft")) {
                event.setCancelled(true);
                player.sendMessage(Component.text("You don't have permission to craft Mass Storage items.", NamedTextColor.RED));
                return;
            }

            // Handle storage disk recipes
            if (isStorageDiskRecipe(shapedRecipe)) {
                handleStorageDiskCrafting(event, player, recipeKey);
            }
            // Handle component recipes
            else if (isComponentRecipe(shapedRecipe)) {
                handleComponentCrafting(event, player, recipeKey);
            }
            // Other MSS recipes (blocks, cables, etc.) are handled normally by the recipe system
        }
    }

    /**
     * Check if the crafting matrix contains any MSS items
     */
    private boolean containsMSSItems(CraftItemEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        for (ItemStack item : matrix) {
            if (item != null && itemManager.isMSSItem(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle storage disk crafting with updated recipes
     */
    private void handleStorageDiskCrafting(CraftItemEvent event, Player player, String recipeKey) {
        ItemStack storageDisk = null;
        String diskType = "1k"; // Default

        // Determine which tier is being crafted based on recipe key
        switch (recipeKey) {
            case "storage_disk_1k" -> {
                storageDisk = itemManager.createStorageDisk(player.getUniqueId().toString(), player.getName());
                diskType = "1k";
            }
            case "storage_disk_4k" -> {
                storageDisk = itemManager.createStorageDisk4k(player.getUniqueId().toString(), player.getName());
                diskType = "4k";
            }
            case "storage_disk_16k" -> {
                storageDisk = itemManager.createStorageDisk16k(player.getUniqueId().toString(), player.getName());
                diskType = "16k";
            }
            case "storage_disk_64k" -> {
                storageDisk = itemManager.createStorageDisk64k(player.getUniqueId().toString(), player.getName());
                diskType = "64k";
            }
        }

        if (storageDisk != null) {
            // Replace the result with our custom item
            event.setCurrentItem(storageDisk);

            // Register the disk in the database
            try {
                registerStorageDisk(storageDisk, player, diskType);
                player.sendMessage(Component.text("Storage Disk [" + diskType.toUpperCase() + "] crafted successfully!", NamedTextColor.GREEN));
            } catch (SQLException e) {
                player.sendMessage(Component.text("Error registering storage disk: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error registering storage disk: " + e.getMessage());
            }
        }
    }

    /**
     * Handle component crafting (disk platters, housing, etc.)
     */
    private void handleComponentCrafting(CraftItemEvent event, Player player, String recipeKey) {
        ItemStack component = null;

        switch (recipeKey) {
            case "disk_platter_1k" -> {
                component = itemManager.createDiskPlatter("1k");
                player.sendMessage(Component.text("Disk Platter [1K] crafted successfully!", NamedTextColor.GREEN));
            }
            case "disk_platter_4k" -> {
                component = itemManager.createDiskPlatter("4k");
                player.sendMessage(Component.text("Disk Platter [4K] crafted successfully!", NamedTextColor.GREEN));
            }
            case "disk_platter_16k" -> {
                component = itemManager.createDiskPlatter("16k");
                player.sendMessage(Component.text("Disk Platter [16K] crafted successfully!", NamedTextColor.GREEN));
            }
            case "disk_platter_64k" -> {
                component = itemManager.createDiskPlatter("64k");
                player.sendMessage(Component.text("Disk Platter [64K] crafted successfully!", NamedTextColor.GREEN));
            }
            case "storage_disk_housing" -> {
                component = itemManager.createStorageDiskHousing();
                player.sendMessage(Component.text("Storage Disk Housing crafted successfully!", NamedTextColor.GREEN));
            }
        }

        if (component != null) {
            // Replace the result with our custom component
            event.setCurrentItem(component);
            plugin.getLogger().info("Player " + player.getName() + " crafted component: " + recipeKey);
        }
    }

    private boolean isStorageDiskRecipe(ShapedRecipe recipe) {
        String namespace = recipe.getKey().getNamespace();
        String key = recipe.getKey().getKey();

        return namespace.equals(plugin.getName().toLowerCase()) &&
                (key.equals("storage_disk_1k") || key.equals("storage_disk_4k") ||
                        key.equals("storage_disk_16k") || key.equals("storage_disk_64k"));
    }

    private boolean isComponentRecipe(ShapedRecipe recipe) {
        String namespace = recipe.getKey().getNamespace();
        String key = recipe.getKey().getKey();

        return namespace.equals(plugin.getName().toLowerCase()) &&
                (key.equals("disk_platter_1k") || key.equals("disk_platter_4k") ||
                        key.equals("disk_platter_16k") || key.equals("disk_platter_64k") ||
                        key.equals("storage_disk_housing"));
    }

    private void registerStorageDisk(ItemStack disk, Player player, String diskType) throws SQLException {
        String diskId = itemManager.getStorageDiskId(disk);
        if (diskId == null) {
            throw new SQLException("Storage disk missing ID");
        }

        // Get tier from disk item, fallback to diskType parameter
        String diskTier = itemManager.getDiskTier(disk);
        final String tier = (diskTier != null) ? diskTier : diskType; // Make effectively final

        // CRITICAL FIX: Use 64 cells as the standard for all disks
        final int maxCells = 64;

        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO storage_disks (disk_id, crafter_uuid, crafter_name, tier, max_cells, used_cells) VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, diskId);
                stmt.setString(2, player.getUniqueId().toString());
                stmt.setString(3, player.getName());
                stmt.setString(4, tier); // Now effectively final
                stmt.setInt(5, maxCells); // Always 64 cells
                stmt.setInt(6, 0);
                stmt.executeUpdate();
            }
        });

        plugin.getLogger().info("Registered " + tier + " storage disk " + diskId + " for player " + player.getName() + " with " + maxCells + " cells");
    }

    private boolean isMSSRecipe(ShapedRecipe recipe) {
        return recipe.getKey().getNamespace().equals(plugin.getName().toLowerCase());
    }
}