package org.jamesphbennett.massstorageserver.network;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class DisksManager {

    private final MassStorageServer plugin;

    public DisksManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Drop drive bay contents when the drive bay is part of a network
     */
    public void dropDriveBayContents(Location location, String networkId) {
        plugin.getLogger().info("Dropping drive bay contents at " + location + " for network " + networkId);

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id IS NOT NULL")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            List<String> diskIds = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    diskIds.add(rs.getString("disk_id"));
                }
            }

            plugin.getLogger().info("Found " + diskIds.size() + " disks to drop from drive bay");

            // Drop each disk and remove from database
            for (String diskId : diskIds) {
                // Get disk info for recreation
                try (PreparedStatement diskStmt = conn.prepareStatement(
                        "SELECT crafter_uuid, crafter_name, used_cells, max_cells FROM storage_disks WHERE disk_id = ?")) {
                    diskStmt.setString(1, diskId);

                    try (ResultSet diskRs = diskStmt.executeQuery()) {
                        if (diskRs.next()) {
                            String crafterUUID = diskRs.getString("crafter_uuid");
                            String crafterName = diskRs.getString("crafter_name");
                            int usedCells = diskRs.getInt("used_cells");
                            int maxCells = diskRs.getInt("max_cells");

                            // Create disk item with correct ID
                            ItemStack disk = plugin.getItemManager().createStorageDiskWithId(diskId, crafterUUID, crafterName);
                            disk = plugin.getItemManager().updateStorageDiskLore(disk, usedCells, maxCells);

                            // Drop the disk
                            location.getWorld().dropItemNaturally(location, disk);
                            plugin.getLogger().info("Dropped disk " + diskId + " with " + usedCells + "/" + maxCells + " cells used");
                        }
                    }
                }

                // Remove from drive bay slots (but keep disk data in storage_disks and storage_items)
                try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id = ?")) {
                    deleteStmt.setString(1, location.getWorld().getName());
                    deleteStmt.setInt(2, location.getBlockX());
                    deleteStmt.setInt(3, location.getBlockY());
                    deleteStmt.setInt(4, location.getBlockZ());
                    deleteStmt.setString(5, diskId);
                    deleteStmt.executeUpdate();
                }
            }

            // Refresh all terminals in the network after drive bay destruction
            if (!diskIds.isEmpty()) {
                plugin.getGUIManager().refreshNetworkTerminals(networkId);
                plugin.getLogger().info("Refreshed terminals after drive bay destruction containing " + diskIds.size() + " disks");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error dropping drive bay contents: " + e.getMessage());
            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * Drop drive bay contents when network association is unknown or invalid
     */
    public void dropDriveBayContentsWithoutNetwork(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT disk_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id IS NOT NULL")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            List<String> diskIds = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    diskIds.add(rs.getString("disk_id"));
                }
            }

            plugin.getLogger().info("Found " + diskIds.size() + " disks to drop from networkless drive bay");

            // Drop each disk and remove from database
            for (String diskId : diskIds) {
                // Get disk info for recreation
                try (PreparedStatement diskStmt = conn.prepareStatement(
                        "SELECT crafter_uuid, crafter_name, used_cells, max_cells FROM storage_disks WHERE disk_id = ?")) {
                    diskStmt.setString(1, diskId);

                    try (ResultSet diskRs = diskStmt.executeQuery()) {
                        if (diskRs.next()) {
                            String crafterUUID = diskRs.getString("crafter_uuid");
                            String crafterName = diskRs.getString("crafter_name");
                            int usedCells = diskRs.getInt("used_cells");
                            int maxCells = diskRs.getInt("max_cells");

                            // Create disk item with correct ID
                            ItemStack disk = plugin.getItemManager().createStorageDiskWithId(diskId, crafterUUID, crafterName);
                            disk = plugin.getItemManager().updateStorageDiskLore(disk, usedCells, maxCells);

                            // Drop the disk
                            location.getWorld().dropItemNaturally(location, disk);
                            plugin.getLogger().info("Dropped disk " + diskId + " with " + usedCells + "/" + maxCells + " cells used");
                        }
                    }
                }

                // Remove from drive bay slots
                try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id = ?")) {
                    deleteStmt.setString(1, location.getWorld().getName());
                    deleteStmt.setInt(2, location.getBlockX());
                    deleteStmt.setInt(3, location.getBlockY());
                    deleteStmt.setInt(4, location.getBlockZ());
                    deleteStmt.setString(5, diskId);
                    deleteStmt.executeUpdate();
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error dropping networkless drive bay contents: " + e.getMessage());
            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * Find network ID associated with a drive bay location from database
     * First tries to find an active network, then falls back to orphaned networks
     */
    public String findDriveBayNetworkId(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {

            // First try to find an active network ID
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT DISTINCT network_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id NOT LIKE 'orphaned_%' LIMIT 1")) {

                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString("network_id");
                    }
                }
            }

            // If no active network found, look for orphaned drive bay slots
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT DISTINCT network_id FROM drive_bay_slots WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND network_id LIKE 'orphaned_%' LIMIT 1")) {

                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String orphanedNetworkId = rs.getString("network_id");
                        plugin.getLogger().info("Found orphaned drive bay slots with network ID: " + orphanedNetworkId);
                        return orphanedNetworkId;
                    }
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error finding drive bay network ID: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if any drive bays at the given locations have restored content
     * Used to determine if a network restoration message should be shown to players
     */
    public boolean checkForRestoredContent(java.util.Set<Location> driveBayLocations) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            for (Location location : driveBayLocations) {
                // Check if this drive bay has any disks with stored items
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT COUNT(*) FROM drive_bay_slots dbs " +
                                "JOIN storage_disks sd ON dbs.disk_id = sd.disk_id " +
                                "JOIN storage_items si ON sd.disk_id = si.disk_id " +
                                "WHERE dbs.world_name = ? AND dbs.x = ? AND dbs.y = ? AND dbs.z = ? " +
                                "AND dbs.disk_id IS NOT NULL")) {

                    stmt.setString(1, location.getWorld().getName());
                    stmt.setInt(2, location.getBlockX());
                    stmt.setInt(3, location.getBlockY());
                    stmt.setInt(4, location.getBlockZ());

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            return true; // Found stored items in this drive bay
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking for restored content: " + e.getMessage());
        }
        return false;
    }

}