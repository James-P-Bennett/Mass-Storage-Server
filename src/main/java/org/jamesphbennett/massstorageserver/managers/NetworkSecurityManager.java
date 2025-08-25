package org.jamesphbennett.massstorageserver.managers;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class NetworkSecurityManager {

    private final MassStorageServer plugin;

    public NetworkSecurityManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Create a new security terminal at the specified location
     */
    public String createSecurityTerminal(Location location, Player owner, String networkId) throws SQLException {
        String terminalId = UUID.randomUUID().toString();
        
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO security_terminals (terminal_id, world_name, x, y, z, owner_uuid, owner_name, network_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                
                stmt.setString(1, terminalId);
                stmt.setString(2, location.getWorld().getName());
                stmt.setInt(3, location.getBlockX());
                stmt.setInt(4, location.getBlockY());
                stmt.setInt(5, location.getBlockZ());
                stmt.setString(6, owner.getUniqueId().toString());
                stmt.setString(7, owner.getName());
                stmt.setString(8, networkId);
                
                stmt.executeUpdate();
            }
        });
        
        plugin.getLogger().info("Created security terminal " + terminalId + " at " + location + " for " + owner.getName());
        return terminalId;
    }

    /**
     * Remove a security terminal at the specified location
     */
    public void removeSecurityTerminal(Location location) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM security_terminals WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
                
                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());
                
                stmt.executeUpdate();
            }
        });
        
        plugin.getLogger().info("Removed security terminal at " + location);
    }

    /**
     * Get security terminal data at a location
     */
    public SecurityTerminalData getSecurityTerminal(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT terminal_id, owner_uuid, owner_name, network_id FROM security_terminals " +
                     "WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
            
            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new SecurityTerminalData(
                        rs.getString("terminal_id"),
                        rs.getString("owner_uuid"),
                        rs.getString("owner_name"),
                        rs.getString("network_id")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error getting security terminal: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if a player has permission to access a network resource
     */
    public boolean hasPermission(Player player, String networkId, PermissionType permissionType) {
        // If no network ID, allow access (networks without security terminals are open)
        if (networkId == null) {
            return true;
        }
        
        // Find security terminal for this network
        SecurityTerminalData terminal = getSecurityTerminalForNetwork(networkId);
        if (terminal == null) {
            // No security terminal for this network - allow access
            return true;
        }
        
        // Check if player is the owner
        if (player.getUniqueId().toString().equals(terminal.ownerUuid)) {
            return true;
        }
        
        // Check if player is trusted
        return isPlayerTrusted(terminal.terminalId, player.getUniqueId().toString(), permissionType);
    }

    /**
     * Check if a player is trusted with specific permissions
     */
    private boolean isPlayerTrusted(String terminalId, String playerUuid, PermissionType permissionType) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT drive_bay_access, block_modification_access FROM security_terminal_players " +
                     "WHERE terminal_id = ? AND player_uuid = ?")) {
            
            stmt.setString(1, terminalId);
            stmt.setString(2, playerUuid);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return switch (permissionType) {
                        case DRIVE_BAY_ACCESS -> rs.getBoolean("drive_bay_access");
                        case BLOCK_MODIFICATION -> rs.getBoolean("block_modification_access");
                    };
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error checking player trust: " + e.getMessage());
        }
        return false;
    }

    /**
     * Get the security terminal for a specific network
     */
    private SecurityTerminalData getSecurityTerminalForNetwork(String networkId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT terminal_id, owner_uuid, owner_name, network_id FROM security_terminals " +
                     "WHERE network_id = ?")) {
            
            stmt.setString(1, networkId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new SecurityTerminalData(
                        rs.getString("terminal_id"),
                        rs.getString("owner_uuid"),
                        rs.getString("owner_name"),
                        rs.getString("network_id")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error getting security terminal for network: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if a player is the owner of a security terminal at a location
     */
    public boolean isOwner(Player player, Location location) {
        SecurityTerminalData terminal = getSecurityTerminal(location);
        return terminal != null && player.getUniqueId().toString().equals(terminal.ownerUuid);
    }

    /**
     * Update the network ID for a security terminal
     */
    public void updateTerminalNetwork(Location location, String networkId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE security_terminals SET network_id = ? WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
                
                stmt.setString(1, networkId);
                stmt.setString(2, location.getWorld().getName());
                stmt.setInt(3, location.getBlockX());
                stmt.setInt(4, location.getBlockY());
                stmt.setInt(5, location.getBlockZ());
                
                stmt.executeUpdate();
            }
        });
    }

    public enum PermissionType {
        DRIVE_BAY_ACCESS,
        BLOCK_MODIFICATION
    }

    public static class SecurityTerminalData {
        public final String terminalId;
        public final String ownerUuid;
        public final String ownerName;
        public final String networkId;

        public SecurityTerminalData(String terminalId, String ownerUuid, String ownerName, String networkId) {
            this.terminalId = terminalId;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.networkId = networkId;
        }
    }
}