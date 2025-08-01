package org.jamesphbennett.massstorageserver.commands;

import net.kyori.adventure.text.minimessage.MiniMessage;
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

public class MSSCommand implements CommandExecutor, TabCompleter {

    private final MassStorageServer plugin;
    private final MiniMessage miniMessage;

    public MSSCommand(MassStorageServer plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
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
        sender.sendMessage(miniMessage.deserialize("<gold>=== Mass Storage Server Commands ==="));
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
            // Look up disk in database
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT crafter_uuid, crafter_name, used_cells, max_cells FROM storage_disks WHERE disk_id = ?")) {

                stmt.setString(1, diskId.toUpperCase());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        sender.sendMessage(miniMessage.deserialize("<red>Storage disk with ID '" + diskId + "' not found."));
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
                        sender.sendMessage(miniMessage.deserialize("<green>Recovery successful! Disk dropped at your location (inventory full)."));
                    } else {
                        player.getInventory().addItem(recoveredDisk);
                        sender.sendMessage(miniMessage.deserialize("<green>Recovery successful! Disk added to your inventory."));
                    }

                    sender.sendMessage(miniMessage.deserialize("<gray>Disk ID: " + diskId));
                    sender.sendMessage(miniMessage.deserialize("<gray>Original Crafter: " + crafterName));
                    sender.sendMessage(miniMessage.deserialize("<gray>Cells Used: " + usedCells + "/" + maxCells));
                }
            }

        } catch (Exception e) {
            sender.sendMessage(miniMessage.deserialize("<red>Error during recovery: " + e.getMessage()));
            plugin.getLogger().severe("Error during disk recovery: " + e.getMessage());
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
                sender.sendMessage(miniMessage.deserialize("<red>Player '" + args[2] + "' not found."));
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
            case "cable", "network_cable" -> plugin.getItemManager().createNetworkCable();
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
            sender.sendMessage(miniMessage.deserialize("<yellow>Blocks: server, bay, terminal, cable"));
            sender.sendMessage(miniMessage.deserialize("<yellow>Disks: disk1k, disk4k, disk16k, disk64k"));
            return;
        }

        if (target.getInventory().firstEmpty() == -1) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
            sender.sendMessage(miniMessage.deserialize("<green>Item given to " + target.getName() + " (dropped due to full inventory)."));
        } else {
            target.getInventory().addItem(item);
            sender.sendMessage(miniMessage.deserialize("<green>Item given to " + target.getName() + "."));
        }

        if (!sender.equals(target)) {
            target.sendMessage(miniMessage.deserialize("<green>You received a " + args[1] + " from " + sender.getName() + "."));
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
                sender.sendMessage(miniMessage.deserialize("<yellow>Active Networks: " + networkCount));
            }

            // Count storage disks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM storage_disks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int diskCount = rs.getInt(1);
                sender.sendMessage(miniMessage.deserialize("<yellow>Total Storage Disks: " + diskCount));
            }

            // Count stored items
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*), SUM(quantity) FROM storage_items");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int itemTypes = rs.getInt(1);
                long totalItems = rs.getLong(2);
                sender.sendMessage(miniMessage.deserialize("<yellow>Item Types Stored: " + itemTypes));
                sender.sendMessage(miniMessage.deserialize("<yellow>Total Items Stored: " + totalItems));
            }

            // Count network cables
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM network_blocks WHERE block_type = 'NETWORK_CABLE'");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int cableCount = rs.getInt(1);
                sender.sendMessage(miniMessage.deserialize("<yellow>Network Cables Placed: " + cableCount));
            }

        } catch (Exception e) {
            sender.sendMessage(miniMessage.deserialize("<red>Error retrieving information: " + e.getMessage()));
        }
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
                sender.sendMessage(miniMessage.deserialize("<green>Cleaned up " + deletedItems + " orphaned storage items."));
            }

            // Clean up empty storage disks with no items
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE storage_disks SET used_cells = 0 WHERE disk_id NOT IN (SELECT DISTINCT disk_id FROM storage_items)")) {
                int updatedDisks = stmt.executeUpdate();
                sender.sendMessage(miniMessage.deserialize("<green>Reset " + updatedDisks + " empty storage disk cell counts."));
            }

            sender.sendMessage(miniMessage.deserialize("<green>Cleanup completed successfully!"));

        } catch (Exception e) {
            sender.sendMessage(miniMessage.deserialize("<red>Error during cleanup: " + e.getMessage()));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> commands = List.of("help");
            if (sender.hasPermission("massstorageserver.admin")) {
                commands = Arrays.asList("help", "recovery", "give", "info", "cleanup", "test");
            }

            for (String cmd : commands) {
                if (cmd.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(cmd);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> items = Arrays.asList("server", "bay", "terminal", "cable", "disk1k", "disk4k", "disk16k", "disk64k");
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