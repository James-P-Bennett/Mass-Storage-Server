package org.jamesphbennett.massstorageserver.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MSSCommand implements CommandExecutor, TabCompleter {

    private final MassStorageServer plugin;
    private final MiniMessage miniMessage;

    public MSSCommand(MassStorageServer plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
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
                    sender.sendMessage(miniMessage.deserialize("<red>Usage: /mss recovery <disk_id>"));
                    return true;
                }
                handleRecovery(sender, args[1]);
                break;

            case "give":
                if (args.length < 2) {
                    sender.sendMessage(miniMessage.deserialize("<red>Usage: /mss give <item_type> [player]"));
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

            default:
                sender.sendMessage(miniMessage.deserialize("<red>Unknown command. Use /mss help for available commands."));
                break;
        }

        return true;
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage(miniMessage.deserialize("<gold><bold>=== Mass Storage Server Commands ===</bold></gold>"));
        sender.sendMessage(miniMessage.deserialize("<yellow>/mss help - Show this help message"));

        if (sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(miniMessage.deserialize("<yellow>/mss recovery <disk_id> - Recover a storage disk by ID"));
            sender.sendMessage(miniMessage.deserialize("<yellow>/mss give <item> [player] - Give MSS items"));
            sender.sendMessage(miniMessage.deserialize("<yellow>/mss info - Show plugin information"));
            sender.sendMessage(miniMessage.deserialize("<yellow>/mss cleanup - Clean up expired data"));
        }
    }

    private void handleRecovery(CommandSender sender, String diskId) {
        if (!sender.hasPermission("massstorageserver.recovery")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use recovery commands."));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(miniMessage.deserialize("<red>Only players can use recovery commands."));
            return;
        }

        try {
            // Look up disk in database with tier information
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT crafter_uuid, crafter_name, used_cells, max_cells, tier FROM storage_disks WHERE disk_id = ?")) {

                stmt.setString(1, diskId.toUpperCase());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sender.sendMessage(miniMessage.deserialize("<red>Storage disk with ID '<yellow>" + diskId + "</yellow>' not found."));
                        return;
                    }

                    String crafterUUID = rs.getString("crafter_uuid");
                    String crafterName = rs.getString("crafter_name");
                    int usedCells = rs.getInt("used_cells");
                    int maxCells = rs.getInt("max_cells");
                    String tier = rs.getString("tier");

                    // CRITICAL FIX: Default to 1k if tier is null/empty from database
                    if (tier == null || tier.trim().isEmpty()) {
                        tier = "1k";
                        plugin.getLogger().warning("Disk " + diskId + " had no tier in database, defaulting to 1k for recovery");
                    }

                    // FIXED: Create the storage disk with the correct tier from database
                    ItemStack recoveredDisk;
                    switch (tier.toLowerCase()) {
                        case "1k" -> recoveredDisk = plugin.getItemManager().createStorageDisk(crafterUUID, crafterName);
                        case "4k" -> recoveredDisk = plugin.getItemManager().createStorageDisk4k(crafterUUID, crafterName);
                        case "16k" -> recoveredDisk = plugin.getItemManager().createStorageDisk16k(crafterUUID, crafterName);
                        case "64k" -> recoveredDisk = plugin.getItemManager().createStorageDisk64k(crafterUUID, crafterName);
                        default -> {
                            plugin.getLogger().warning("Unknown tier '" + tier + "' for disk " + diskId + ", defaulting to 1k");
                            recoveredDisk = plugin.getItemManager().createStorageDisk(crafterUUID, crafterName);
                            tier = "1k";
                        }
                    }

                    // CRITICAL: Use createStorageDiskWithId to preserve the exact disk ID
                    recoveredDisk = plugin.getItemManager().createStorageDiskWithId(diskId, crafterUUID, crafterName);

                    // Update the lore with current stats
                    recoveredDisk = plugin.getItemManager().updateStorageDiskLore(recoveredDisk, usedCells, maxCells);

                    // Give to player
                    if (player.getInventory().firstEmpty() == -1) {
                        player.getWorld().dropItemNaturally(player.getLocation(), recoveredDisk);
                        sender.sendMessage(miniMessage.deserialize("<green>Recovery successful! Disk dropped at your location (inventory full)."));
                    } else {
                        player.getInventory().addItem(recoveredDisk);
                        sender.sendMessage(miniMessage.deserialize("<green>Recovery successful! Disk added to your inventory."));
                    }

                    // Calculate and show capacity info
                    int itemsPerCell = plugin.getItemManager().getItemsPerCellForTier(tier);
                    int totalCapacity = maxCells * itemsPerCell;
                    String tierDisplayName = convertTierDisplayToMiniMessage(tier);

                    sender.sendMessage(miniMessage.deserialize("<gray>Disk ID: <white>" + diskId));
                    sender.sendMessage(miniMessage.deserialize("<gray>Tier: " + tierDisplayName));
                    sender.sendMessage(miniMessage.deserialize("<gray>Original Crafter: <white>" + crafterName));
                    sender.sendMessage(miniMessage.deserialize("<gray>Cells Used: <yellow>" + usedCells + "</yellow>/<white>" + maxCells));
                    sender.sendMessage(miniMessage.deserialize("<gray>Total Capacity: <aqua>" + String.format("%,d", totalCapacity) + "</aqua> items"));

                    plugin.getLogger().info("Successfully recovered " + tier.toUpperCase() + " disk " + diskId +
                            " for " + player.getName() + " (" + usedCells + "/" + maxCells + " cells used)");
                }
            }

        } catch (Exception e) {
            sender.sendMessage(miniMessage.deserialize("<red>Error during recovery: " + e.getMessage()));
            plugin.getLogger().severe("Error during disk recovery: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use give commands."));
            return;
        }

        Player target;
        if (args.length >= 3) {
            target = plugin.getServer().getPlayer(args[2]);
            if (target == null) {
                sender.sendMessage(miniMessage.deserialize("<red>Player '<yellow>" + args[2] + "</yellow>' not found."));
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(miniMessage.deserialize("<red>You must specify a player when using this command from console."));
            return;
        }

        ItemStack item = switch (args[1].toLowerCase()) {
            case "server", "storage_server" -> plugin.getItemManager().createStorageServer();
            case "bay", "drive_bay" -> plugin.getItemManager().createDriveBay();
            case "terminal", "mss_terminal" -> plugin.getItemManager().createMSSTerminal();
            case "disk", "storage_disk", "disk1k" -> plugin.getItemManager().createStorageDisk(
                    target.getUniqueId().toString(), target.getName());
            case "disk4k" -> plugin.getItemManager().createStorageDisk4k(
                    target.getUniqueId().toString(), target.getName());
            case "disk16k" -> plugin.getItemManager().createStorageDisk16k(
                    target.getUniqueId().toString(), target.getName());
            case "disk64k" -> plugin.getItemManager().createStorageDisk64k(
                    target.getUniqueId().toString(), target.getName());
            default -> null;
        };

        if (item == null) {
            sender.sendMessage(miniMessage.deserialize("<red>Invalid item type. Available:"));
            sender.sendMessage(miniMessage.deserialize("<yellow>Blocks: <white>server, bay, terminal"));
            sender.sendMessage(miniMessage.deserialize("<yellow>Disks: <white>disk1k, disk4k, disk16k, disk64k"));
            return;
        }

        if (target.getInventory().firstEmpty() == -1) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
            sender.sendMessage(miniMessage.deserialize("<green>Item given to <yellow>" + target.getName() + "</yellow> (dropped due to full inventory)."));
        } else {
            target.getInventory().addItem(item);
            sender.sendMessage(miniMessage.deserialize("<green>Item given to <yellow>" + target.getName() + "</yellow>."));
        }

        if (!sender.equals(target)) {
            target.sendMessage(miniMessage.deserialize("<green>You received a <aqua>" + args[1] + "</aqua> from <yellow>" + sender.getName() + "</yellow>."));
        }
    }

    private void handleInfo(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use info commands."));
            return;
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Count networks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM networks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int networkCount = rs.getInt(1);
                sender.sendMessage(miniMessage.deserialize("<yellow>Active Networks: <white>" + networkCount));
            }

            // Count storage disks by tier
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT tier, COUNT(*) as count FROM storage_disks GROUP BY tier ORDER BY tier");
                 ResultSet rs = stmt.executeQuery()) {

                sender.sendMessage(miniMessage.deserialize("<yellow>Storage Disks by Tier:"));
                int totalDisks = 0;
                while (rs.next()) {
                    String tier = rs.getString("tier");
                    int count = rs.getInt("count");
                    totalDisks += count;

                    // Display tier with proper formatting - convert ChatColor to MiniMessage
                    String tierDisplay = convertTierDisplayToMiniMessage(tier != null ? tier : "1k");
                    sender.sendMessage(miniMessage.deserialize("<gray>  " + tierDisplay + " <gray>: <white>" + count));
                }
                sender.sendMessage(miniMessage.deserialize("<yellow>Total Storage Disks: <white>" + totalDisks));
            }

            // Count stored items
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*), SUM(quantity) FROM storage_items");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int itemTypes = rs.getInt(1);
                long totalItems = rs.getLong(2);
                sender.sendMessage(miniMessage.deserialize("<yellow>Item Types Stored: <white>" + itemTypes));
                sender.sendMessage(miniMessage.deserialize("<yellow>Total Items Stored: <white>" + totalItems));
            }

        } catch (Exception e) {
            sender.sendMessage(miniMessage.deserialize("<red>Error retrieving information: " + e.getMessage()));
        }
    }

    /**
     * Convert tier display name to MiniMessage format
     */
    private String convertTierDisplayToMiniMessage(String tier) {
        return switch (tier.toLowerCase()) {
            case "1k" -> "<white>1K";
            case "4k" -> "<yellow>4K";
            case "16k" -> "<aqua>16K";
            case "64k" -> "<light_purple>64K";
            default -> "<white>1K";
        };
    }

    private void handleCleanup(CommandSender sender) {
        if (!sender.hasPermission("massstorageserver.admin")) {
            sender.sendMessage(miniMessage.deserialize("<red>You don't have permission to use cleanup commands."));
            return;
        }

        try {
            // Clean up expired cooldowns
            plugin.getCooldownManager().cleanupExpiredCooldowns();

            // Clean up orphaned storage items (items without valid disks)
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM storage_items WHERE disk_id NOT IN (SELECT disk_id FROM storage_disks)")) {
                int deletedItems = stmt.executeUpdate();
                sender.sendMessage(miniMessage.deserialize("<green>Cleaned up <yellow>" + deletedItems + "</yellow> orphaned storage items."));
            }

            // Clean up empty storage disks with no items
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE storage_disks SET used_cells = 0 WHERE disk_id NOT IN (SELECT DISTINCT disk_id FROM storage_items)")) {
                int updatedDisks = stmt.executeUpdate();
                sender.sendMessage(miniMessage.deserialize("<green>Reset <yellow>" + updatedDisks + "</yellow> empty storage disk cell counts."));
            }

            sender.sendMessage(miniMessage.deserialize("<green><bold>Cleanup completed successfully!</bold>"));

        } catch (Exception e) {
            sender.sendMessage(miniMessage.deserialize("<red>Error during cleanup: " + e.getMessage()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> commands = Arrays.asList("help");
            if (sender.hasPermission("massstorageserver.admin")) {
                commands = Arrays.asList("help", "recovery", "give", "info", "cleanup", "test");
            }

            for (String cmd : commands) {
                if (cmd.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(cmd);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> items = Arrays.asList("server", "bay", "terminal", "disk1k", "disk4k", "disk16k", "disk64k");
            for (String item : items) {
                if (item.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(item);
                }
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