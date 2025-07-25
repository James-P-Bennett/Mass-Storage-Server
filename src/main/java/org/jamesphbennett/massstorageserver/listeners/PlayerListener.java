package org.jamesphbennett.massstorageserver.listeners;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
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

    @EventHandler
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof ShapedRecipe shapedRecipe)) {
            return;
        }

        // Check if this is our storage disk recipe
        if (isStorageDiskRecipe(shapedRecipe)) {
            if (plugin.getConfigManager().isRequireCraftPermission() && !player.hasPermission("massstorageserver.craft")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You don't have permission to craft Mass Storage items.");
                return;
            }

            // Create the actual storage disk with player data
            ItemStack storageDisk = itemManager.createStorageDisk(
                    player.getUniqueId().toString(),
                    player.getName()
            );

            // Replace the result with our custom item
            event.setCurrentItem(storageDisk);

            // Register the disk in the database
            try {
                registerStorageDisk(storageDisk, player);
                player.sendMessage(ChatColor.GREEN + "Storage Disk crafted successfully!");
            } catch (SQLException e) {
                player.sendMessage(ChatColor.RED + "Error registering storage disk: " + e.getMessage());
                plugin.getLogger().severe("Error registering storage disk: " + e.getMessage());
            }
        }

        // Check other MSS recipes and validate permissions
        else if (isMSSRecipe(shapedRecipe)) {
            if (plugin.getConfigManager().isRequireCraftPermission() && !player.hasPermission("massstorageserver.craft")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You don't have permission to craft Mass Storage items.");
                return;
            }

            // For other MSS items, the recipe result should already be correct
            // No need to replace since we're using the actual custom items in recipes
        }
    }

    private boolean isStorageDiskRecipe(ShapedRecipe recipe) {
        return recipe.getKey().getNamespace().equals(plugin.getName().toLowerCase()) &&
                recipe.getKey().getKey().equals("storage_disk");
    }

    private boolean isMSSRecipe(ShapedRecipe recipe) {
        return recipe.getKey().getNamespace().equals(plugin.getName().toLowerCase());
    }

    private void registerStorageDisk(ItemStack disk, Player player) throws SQLException {
        String diskId = itemManager.getStorageDiskId(disk);
        if (diskId == null) {
            throw new SQLException("Storage disk missing ID");
        }

        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO storage_disks (disk_id, crafter_uuid, crafter_name, max_cells, used_cells) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setString(1, diskId);
                stmt.setString(2, player.getUniqueId().toString());
                stmt.setString(3, player.getName());
                stmt.setInt(4, plugin.getConfigManager().getDefaultCellsPerDisk());
                stmt.setInt(5, 0);
                stmt.executeUpdate();
            }
        });
    }
}