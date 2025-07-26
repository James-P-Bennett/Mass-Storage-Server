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

        // Check if this is any of our storage disk recipes
        String recipeKey = shapedRecipe.getKey().getKey();

        if (isStorageDiskRecipe(shapedRecipe)) {
            if (plugin.getConfigManager().isRequireCraftPermission() && !player.hasPermission("massstorageserver.craft")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You don't have permission to craft Mass Storage items.");
                return;
            }

            ItemStack storageDisk = null;
            String diskType = "1k"; // Default

            // Determine which tier is being crafted based on recipe key
            switch (recipeKey) {
                case "storage_disk" -> {
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
                    player.sendMessage(ChatColor.GREEN + "Storage Disk [" + diskType.toUpperCase() + "] crafted successfully!");
                } catch (SQLException e) {
                    player.sendMessage(ChatColor.RED + "Error registering storage disk: " + e.getMessage());
                    plugin.getLogger().severe("Error registering storage disk: " + e.getMessage());
                }
            }
        }
        // Check other MSS recipes and validate permissions
        else if (isMSSRecipe(shapedRecipe)) {
            if (plugin.getConfigManager().isRequireCraftPermission() && !player.hasPermission("massstorageserver.craft")) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You don't have permission to craft Mass Storage items.");
                return;
            }
        }
    }

    private boolean isStorageDiskRecipe(ShapedRecipe recipe) {
        String namespace = recipe.getKey().getNamespace();
        String key = recipe.getKey().getKey();

        return namespace.equals(plugin.getName().toLowerCase()) &&
                (key.equals("storage_disk") || key.equals("storage_disk_4k") ||
                        key.equals("storage_disk_16k") || key.equals("storage_disk_64k"));
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