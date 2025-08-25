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
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.usage.recovery"));
                    return true;
                }
                handleRecovery(sender, args[1]);
                break;

            case "give":
                if (args.length < 2) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.usage.give"));
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
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.usage.recipe"));
                    return true;
                }
                handleRecipeInfo(sender, args[1]);
                break;

            case "reload":
                handleReload(sender, args);
                break;

            default:
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.unknown-command"));
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        Player player = sender instanceof Player ? (Player) sender : null;
        
        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.header"));
        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.main-help"));

        if (sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.recovery"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.give"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.info"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.cleanup"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.recipes"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.recipe"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.reload"));
        }
    }

    private void handleRecovery(CommandSender sender, String diskId) {
        if (!sender.hasPermission("massstorageserver.recovery")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.no-permission-recovery"));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(null, "commands.players-only"));
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
                        sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.recovery.not-found", "disk_id", diskId));
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
                        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.success-dropped"));
                    } else {
                        player.getInventory().addItem(recoveredDisk);
                        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.success-inventory"));
                    }

                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.disk-info", "disk_id", diskId));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.crafter-info", "crafter", crafterName));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.cells-info", "used", usedCells, "max", maxCells));
                }
            }

        } catch (Exception e) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.recovery.error", "error", e.getMessage()));
            plugin.getLogger().severe("Error during disk recovery: " + e.getMessage());
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.no-permission"));
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = plugin.getServer().getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.player-not-found", "player", args[2]));
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(null, "commands.give.console-needs-player"));
            return;
        }

        ItemStack item = switch (args[1].toLowerCase()) {
            // Network blocks
            case "server", "storage_server" -> plugin.getItemManager().createStorageServer();
            case "bay", "drive_bay" -> plugin.getItemManager().createDriveBay();
            case "terminal", "mss_terminal" -> plugin.getItemManager().createMSSTerminal();
            case "cable", "network_cable" -> plugin.getItemManager().createNetworkCable();
            case "exporter" -> plugin.getItemManager().createExporter();
            case "importer" -> plugin.getItemManager().createImporter();
            case "security", "security_terminal" -> plugin.getItemManager().createSecurityTerminal();

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
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.invalid-item"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.available-blocks"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.available-disks"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.available-components"));
            return;
        }

        if (target.getInventory().firstEmpty() == -1) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.success-dropped", "player", target.getName()));
        } else {
            target.getInventory().addItem(item);
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.give.success-inventory", "player", target.getName()));
        }

        if (!sender.equals(target)) {
            target.sendMessage(plugin.getMessageManager().getMessageComponent(target, "commands.give.received", "item", args[1], "sender", sender.getName()));
        }
    }

    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.info.no-permission"));
            return;
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Count networks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM networks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int networkCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.info.networks", "count", networkCount));
            }

            // Count storage disks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM storage_disks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int diskCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.info.disks", "count", diskCount));
            }

            // Count stored items
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*), SUM(quantity) FROM storage_items");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int itemTypes = rs.getInt(1);
                long totalItems = rs.getLong(2);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.info.item-types", "types", itemTypes));
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.info.total-items", "total", totalItems));
            }

            // Count network cables
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM network_blocks WHERE block_type = 'NETWORK_CABLE'");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int cableCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.info.cables", "count", cableCount));
            }

            // Count exporters
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM exporters");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int exporterCount = rs.getInt(1);
                sender.sendMessage(Component.text("Exporters Placed: " + exporterCount, NamedTextColor.YELLOW));
            }

            // Count importers
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM importers");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int importerCount = rs.getInt(1);
                sender.sendMessage(Component.text("Importers Placed: " + importerCount, NamedTextColor.YELLOW));
            }

            // Recipe information
            int recipeCount = plugin.getRecipeManager().getRegisteredRecipeCount();
            Set<String> totalRecipes = plugin.getConfigManager().getRecipeNames();
            boolean recipesEnabled = plugin.getConfigManager().areRecipesEnabled();

            String recipeKey = recipesEnabled ? "commands.info.recipes-enabled" : "commands.info.recipes-disabled";
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, recipeKey, "registered", recipeCount, "total", totalRecipes.size()));

        } catch (Exception e) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.info.error", "error", e.getMessage()));
        }
    }

    private void handleCleanup(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.cleanup.no-permission"));
            return;
        }

        try {

            // Clean up orphaned storage items (items without valid disks)
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM storage_items WHERE disk_id NOT IN (SELECT disk_id FROM storage_disks)")) {
                int deletedItems = stmt.executeUpdate();
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.cleanup.orphaned-items", "count", deletedItems));
            }

            // Clean up empty storage disks with no items
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE storage_disks SET used_cells = 0 WHERE disk_id NOT IN (SELECT DISTINCT disk_id FROM storage_items)")) {
                int updatedDisks = stmt.executeUpdate();
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.cleanup.reset-disks", "count", updatedDisks));
            }

            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.cleanup.success"));

        } catch (Exception e) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.cleanup.error", "error", e.getMessage()));
        }
    }

    private void handleRecipes(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.recipes.no-permission"));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(null, "commands.recipes.players-only"));
            return;
        }

        plugin.getRecipeManager().listRecipes(player);
    }

    private void handleRecipeInfo(CommandSender sender, String recipeName) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.recipes.no-permission"));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(null, "commands.recipes.players-only"));
            return;
        }

        plugin.getRecipeManager().sendRecipeInfo(player, recipeName);
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.no-permission"));
            return;
        }

        String what = args.length > 1 ? args[1].toLowerCase() : "all";

        switch (what) {
            case "config":
                try {
                    plugin.getConfigManager().loadConfig();
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.config-success"));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.config-error", "error", e.getMessage()));
                }
                break;

            case "recipes":
                try {
                    plugin.getRecipeManager().reloadRecipes();
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.recipes-success"));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.recipes-note"));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.recipes-error", "error", e.getMessage()));
                }
                break;

            case "all":
                try {
                    plugin.getConfigManager().reloadConfig();
                    plugin.getRecipeManager().reloadRecipes();
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.all-success"));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.recipes-note"));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.all-error", "error", e.getMessage()));
                }
                break;

            default:
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(sender instanceof Player ? (Player) sender : null, "commands.reload.usage"));
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
                            "server", "bay", "terminal", "cable", "exporter", "importer", "security",
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