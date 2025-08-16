package org.jamesphbennett.massstorageserver.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class MSSCommand implements CommandExecutor, TabCompleter {

    private final MassStorageServer plugin;

    public MSSCommand(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "help":
                sendHelpMessage(sender);
                break;

            case "recovery":
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /mss recovery <disk_id>", NamedTextColor.RED));
                    return true;
                }
                handleRecovery(sender, args[1]);
                break;

            case "give":
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /mss give <item_type> [player]", NamedTextColor.RED));
                    return true;
                }
                handleGive(sender, args);
                break;

            case "info":
                handleInfo(sender);
                break;

            case "cleanup":
                handleCleanup(sender);
                break;

            case "recipes":
                handleRecipes(sender);
                break;

            case "recipe":
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /mss recipe <recipe_name>", NamedTextColor.RED));
                    return true;
                }
                handleRecipeInfo(sender, args[1]);
                break;

            case "reload":
                handleReload(sender, args);
                break;

            default:
                sender.sendMessage(Component.text("Unknown command. Use /mss help for available commands.", NamedTextColor.RED));
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(Component.text("=== Mass Storage Server Commands ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/mss help - Show this help message", NamedTextColor.YELLOW));

        if (sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(Component.text("/mss recovery <disk_id> - Recover a storage disk by ID", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/mss give <item> [player] - Give MSS items", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/mss info - Show plugin information", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/mss cleanup - Clean up expired data", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/mss recipes - List all recipes", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/mss recipe <name> - Show detailed recipe info", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/mss reload [config|recipes] - Reload configurations", NamedTextColor.YELLOW));
        }
    }

    private void handleRecovery(CommandSender sender, String diskId) {
        if (!sender.hasPermission("massstorageserver.recovery")) {
            sender.sendMessage(Component.text("You don't have permission to use recovery commands.", NamedTextColor.RED));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use recovery commands.", NamedTextColor.RED));
            return;
        }

        try {
            // Look up disk in database
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT crafter_uuid, crafter_name, used_cells, max_cells FROM storage_disks WHERE disk_id = ?")) {

                stmt.setString(1, diskId.toUpperCase());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sender.sendMessage(Component.text("Storage disk with ID '" + diskId + "' not found.", NamedTextColor.RED));
                        return;
                    }

                    String crafterUUID = rs.getString("crafter_uuid");
                    String crafterName = rs.getString("crafter_name");
                    int usedCells = rs.getInt("used_cells");
                    int maxCells = rs.getInt("max_cells");

                    // Create the storage disk
                    ItemStack recoveredDisk = plugin.getItemManager().createStorageDisk(crafterUUID, crafterName);
                    recoveredDisk = plugin.getItemManager().updateStorageDiskLore(recoveredDisk, usedCells, maxCells);

                    // Give to player
                    if (player.getInventory().firstEmpty() == -1) {
                        player.getWorld().dropItemNaturally(player.getLocation(), recoveredDisk);
                        sender.sendMessage(Component.text("Recovery successful! Disk dropped at your location (inventory full).", NamedTextColor.GREEN));
                    } else {
                        player.getInventory().addItem(recoveredDisk);
                        sender.sendMessage(Component.text("Recovery successful! Disk added to your inventory.", NamedTextColor.GREEN));
                    }

                    sender.sendMessage(Component.text("Disk ID: " + diskId, NamedTextColor.GRAY));
                    sender.sendMessage(Component.text("Original Crafter: " + crafterName, NamedTextColor.GRAY));
                    sender.sendMessage(Component.text("Cells Used: " + usedCells + "/" + maxCells, NamedTextColor.GRAY));
                }
            }

        } catch (Exception e) {
            sender.sendMessage(Component.text("Error during recovery: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error during disk recovery: " + e.getMessage());
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use give commands.", NamedTextColor.RED));
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = plugin.getServer().getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(Component.text("Player '" + args[2] + "' not found.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(Component.text("You must specify a player when using this command from console.", NamedTextColor.RED));
            return;
        }

        ItemStack item = switch (args[1].toLowerCase()) {
            // Network blocks
            case "server", "storage_server" -> plugin.getItemManager().createStorageServer();
            case "bay", "drive_bay" -> plugin.getItemManager().createDriveBay();
            case "terminal", "mss_terminal" -> plugin.getItemManager().createMSSTerminal();
            case "cable", "network_cable" -> plugin.getItemManager().createNetworkCable();
            case "exporter" -> plugin.getItemManager().createExporter();

            // Storage disks
            case "disk", "storage_disk", "disk1k" -> plugin.getItemManager().createStorageDisk(
                    target.getUniqueId().toString(), target.getName());
            case "disk4k" -> plugin.getItemManager().createStorageDisk4k(
                    target.getUniqueId().toString(), target.getName());
            case "disk16k" -> plugin.getItemManager().createStorageDisk16k(
                    target.getUniqueId().toString(), target.getName());
            case "disk64k" -> plugin.getItemManager().createStorageDisk64k(
                    target.getUniqueId().toString(), target.getName());

            // Components
            case "housing", "disk_housing" -> plugin.getItemManager().createStorageDiskHousing();
            case "platter1k", "platter_1k" -> plugin.getItemManager().createDiskPlatter("1k");
            case "platter4k", "platter_4k" -> plugin.getItemManager().createDiskPlatter("4k");
            case "platter16k", "platter_16k" -> plugin.getItemManager().createDiskPlatter("16k");
            case "platter64k", "platter_64k" -> plugin.getItemManager().createDiskPlatter("64k");

            default -> null;
        };

        if (item == null) {
            sender.sendMessage(Component.text("Invalid item type. Available:", NamedTextColor.RED));
            sender.sendMessage(Component.text("Blocks: server, bay, terminal, cable, exporter", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Disks: disk1k, disk4k, disk16k, disk64k", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("Components: housing, platter1k, platter4k, platter16k, platter64k", NamedTextColor.YELLOW));
            return;
        }

        if (target.getInventory().firstEmpty() == -1) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
            sender.sendMessage(Component.text("Item given to " + target.getName() + " (dropped due to full inventory).", NamedTextColor.GREEN));
        } else {
            target.getInventory().addItem(item);
            sender.sendMessage(Component.text("Item given to " + target.getName() + ".", NamedTextColor.GREEN));
        }

        if (!sender.equals(target)) {
            target.sendMessage(Component.text("You received a " + args[1] + " from " + sender.getName() + ".", NamedTextColor.GREEN));
        }
    }

    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use info commands.", NamedTextColor.RED));
            return;
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Count networks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM networks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int networkCount = rs.getInt(1);
                sender.sendMessage(Component.text("Active Networks: " + networkCount, NamedTextColor.YELLOW));
            }

            // Count storage disks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM storage_disks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int diskCount = rs.getInt(1);
                sender.sendMessage(Component.text("Total Storage Disks: " + diskCount, NamedTextColor.YELLOW));
            }

            // Count stored items
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*), SUM(quantity) FROM storage_items");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int itemTypes = rs.getInt(1);
                long totalItems = rs.getLong(2);
                sender.sendMessage(Component.text("Item Types Stored: " + itemTypes, NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("Total Items Stored: " + totalItems, NamedTextColor.YELLOW));
            }

            // Count network cables
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM network_blocks WHERE block_type = 'NETWORK_CABLE'");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int cableCount = rs.getInt(1);
                sender.sendMessage(Component.text("Network Cables Placed: " + cableCount, NamedTextColor.YELLOW));
            }

            // Count exporters
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM exporters");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int exporterCount = rs.getInt(1);
                sender.sendMessage(Component.text("Exporters Placed: " + exporterCount, NamedTextColor.YELLOW));
            }

            // Recipe information
            int recipeCount = plugin.getRecipeManager().getRegisteredRecipeCount();
            Set<String> totalRecipes = plugin.getConfigManager().getRecipeNames();
            boolean recipesEnabled = plugin.getConfigManager().areRecipesEnabled();

            sender.sendMessage(Component.text("Recipes: " +
                            (recipesEnabled ? "Enabled" : "Disabled") +
                            " (" + recipeCount + "/" + totalRecipes.size() + " registered)",
                    recipesEnabled ? NamedTextColor.GREEN : NamedTextColor.RED));

        } catch (Exception e) {
            sender.sendMessage(Component.text("Error retrieving information: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleCleanup(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use cleanup commands.", NamedTextColor.RED));
            return;
        }

        try {

            // Clean up orphaned storage items (items without valid disks)
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM storage_items WHERE disk_id NOT IN (SELECT disk_id FROM storage_disks)")) {
                int deletedItems = stmt.executeUpdate();
                sender.sendMessage(Component.text("Cleaned up " + deletedItems + " orphaned storage items.", NamedTextColor.GREEN));
            }

            // Clean up empty storage disks with no items
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE storage_disks SET used_cells = 0 WHERE disk_id NOT IN (SELECT DISTINCT disk_id FROM storage_items)")) {
                int updatedDisks = stmt.executeUpdate();
                sender.sendMessage(Component.text("Reset " + updatedDisks + " empty storage disk cell counts.", NamedTextColor.GREEN));
            }

            sender.sendMessage(Component.text("Cleanup completed successfully!", NamedTextColor.GREEN));

        } catch (Exception e) {
            sender.sendMessage(Component.text("Error during cleanup: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void handleRecipes(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use recipe commands.", NamedTextColor.RED));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }

        plugin.getRecipeManager().listRecipes(player);
    }

    private void handleRecipeInfo(CommandSender sender, String recipeName) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use recipe commands.", NamedTextColor.RED));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }

        plugin.getRecipeManager().sendRecipeInfo(player, recipeName);
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use reload commands.", NamedTextColor.RED));
            return;
        }

        String what = args.length > 1 ? args[1].toLowerCase() : "all";

        switch (what) {
            case "config":
                try {
                    plugin.getConfigManager().loadConfig();
                    sender.sendMessage(Component.text("Main configuration reloaded successfully!", NamedTextColor.GREEN));
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Error reloading config: " + e.getMessage(), NamedTextColor.RED));
                }
                break;

            case "recipes":
                try {
                    plugin.getRecipeManager().reloadRecipes();
                    sender.sendMessage(Component.text("Recipes configuration reloaded successfully!", NamedTextColor.GREEN));
                    sender.sendMessage(Component.text("Note: Server restart may be required for recipe changes to take full effect.", NamedTextColor.YELLOW));
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Error reloading recipes: " + e.getMessage(), NamedTextColor.RED));
                }
                break;

            case "all":
                try {
                    plugin.getConfigManager().reloadConfig();
                    plugin.getRecipeManager().reloadRecipes();
                    sender.sendMessage(Component.text("All configurations reloaded successfully!", NamedTextColor.GREEN));
                    sender.sendMessage(Component.text("Note: Server restart may be required for recipe changes to take full effect.", NamedTextColor.YELLOW));
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Error reloading configurations: " + e.getMessage(), NamedTextColor.RED));
                }
                break;

            default:
                sender.sendMessage(Component.text("Usage: /mss reload [config|recipes|all]", NamedTextColor.RED));
                break;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> commands = List.of("help");
            if (sender.hasPermission("massstorageserver.admin")) {
                commands = Arrays.asList("help", "recovery", "give", "info", "cleanup", "recipes", "recipe", "reload");
            }

            for (String cmd : commands) {
                if (cmd.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(cmd);
                }
            }
        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "give":
                    List<String> items = Arrays.asList(
                            // Network blocks
                            "server", "bay", "terminal", "cable", "exporter",
                            // Storage disks
                            "disk1k", "disk4k", "disk16k", "disk64k",
                            // Components
                            "housing", "platter1k", "platter4k", "platter16k", "platter64k"
                    );
                    for (String item : items) {
                        if (item.toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(item);
                        }
                    }
                    break;

                case "recipe":
                    if (sender.hasPermission("massstorageserver.admin")) {
                        Set<String> recipeNames = plugin.getConfigManager().getRecipeNames();
                        for (String recipeName : recipeNames) {
                            if (recipeName.toLowerCase().startsWith(args[1].toLowerCase())) {
                                completions.add(recipeName);
                            }
                        }
                    }
                    break;

                case "reload":
                    List<String> reloadOptions = Arrays.asList("config", "recipes", "all");
                    for (String option : reloadOptions) {
                        if (option.toLowerCase().startsWith(args[1].toLowerCase())) {
                            completions.add(option);
                        }
                    }
                    break;
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            // Player name completion
            plugin.getServer().getOnlinePlayers().forEach(player -> {
                if (player.getName().toLowerCase().startsWith(args[2].toLowerCase())) {
                    completions.add(player.getName());
                }
            });
        }

        return completions;
    }
}