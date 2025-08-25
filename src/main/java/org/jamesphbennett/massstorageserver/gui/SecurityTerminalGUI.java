package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SecurityTerminalGUI implements Listener {

    private final MassStorageServer plugin;
    private final Location terminalLocation;
    private final String terminalId;
    private final String ownerUuid;
    private final Player viewer;
    private final Inventory inventory;
    
    private List<TrustedPlayer> trustedPlayers = new ArrayList<>();
    private int currentPage = 0;
    private final int playersPerPage = 36; // 4 rows of 9 slots

    public SecurityTerminalGUI(MassStorageServer plugin, Location terminalLocation, String terminalId, String ownerUuid, Player viewer) {
        this.plugin = plugin;
        this.terminalLocation = terminalLocation;
        this.terminalId = terminalId;
        this.ownerUuid = ownerUuid;
        this.viewer = viewer;
        
        this.inventory = Bukkit.createInventory(null, 54, Component.text("Security Terminal", NamedTextColor.RED));
        
        setupGUI();
        loadTrustedPlayers();
    }

    private void setupGUI() {
        // Fill bottom two rows with background
        ItemStack background = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta backgroundMeta = background.getItemMeta();
        backgroundMeta.displayName(Component.text(" "));
        background.setItemMeta(backgroundMeta);

        // Fill bottom two rows (slots 36-53)
        for (int i = 36; i < 54; i++) {
            inventory.setItem(i, background);
        }

        updateNavigationItems();
        updateControlItems();
    }

    private void updateNavigationItems() {
        int maxPages = getMaxPages();

        // Ensure currentPage is within valid bounds
        if (currentPage < 0) {
            currentPage = 0;
        } else if (currentPage >= maxPages) {
            currentPage = Math.max(0, maxPages - 1);
        }

        // Previous page button
        ItemStack prevPage = new ItemStack(Material.ARROW);
        ItemMeta prevMeta = prevPage.getItemMeta();
        prevMeta.displayName(Component.text("Previous Page", NamedTextColor.YELLOW));
        List<Component> prevLore = new ArrayList<>();
        prevLore.add(Component.text("Page " + (currentPage + 1) + "/" + maxPages, NamedTextColor.GRAY));
        if (currentPage > 0) {
            prevLore.add(Component.text("Click to go to previous page", NamedTextColor.GREEN));
        } else {
            prevLore.add(Component.text("Already on first page", NamedTextColor.RED));
        }
        prevMeta.lore(prevLore);
        prevPage.setItemMeta(prevMeta);
        inventory.setItem(45, prevPage);

        // Next page button
        ItemStack nextPage = new ItemStack(Material.ARROW);
        ItemMeta nextMeta = nextPage.getItemMeta();
        nextMeta.displayName(Component.text("Next Page", NamedTextColor.YELLOW));
        List<Component> nextLore = new ArrayList<>();
        nextLore.add(Component.text("Page " + (currentPage + 1) + "/" + maxPages, NamedTextColor.GRAY));
        if (currentPage < maxPages - 1) {
            nextLore.add(Component.text("Click to go to next page", NamedTextColor.GREEN));
        } else {
            nextLore.add(Component.text("Already on last page", NamedTextColor.RED));
        }
        nextMeta.lore(nextLore);
        nextPage.setItemMeta(nextMeta);
        inventory.setItem(53, nextPage);
    }

    private void updateControlItems() {
        // Add player button
        ItemStack addPlayer = new ItemStack(Material.EMERALD);
        ItemMeta addMeta = addPlayer.getItemMeta();
        addMeta.displayName(Component.text("Add Player", NamedTextColor.GREEN));
        List<Component> addLore = new ArrayList<>();
        addLore.add(Component.text("Click to add a trusted player", NamedTextColor.GRAY));
        addLore.add(Component.text("You can add offline players", NamedTextColor.YELLOW));
        addMeta.lore(addLore);
        addPlayer.setItemMeta(addMeta);
        inventory.setItem(48, addPlayer);

        // Info item
        ItemStack info = new ItemStack(Material.OBSERVER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text("Security Terminal", NamedTextColor.RED));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(Component.text("Owner: " + getOwnerName(), NamedTextColor.GRAY));
        infoLore.add(Component.text("Trusted Players: " + trustedPlayers.size(), NamedTextColor.GRAY));
        infoLore.add(Component.empty());
        infoLore.add(Component.text("Permissions:", NamedTextColor.YELLOW));
        infoLore.add(Component.text("• Drive Bay Access", NamedTextColor.AQUA));
        infoLore.add(Component.text("• Block Modification", NamedTextColor.AQUA));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);
    }

    private void loadTrustedPlayers() {
        trustedPlayers.clear();
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT player_uuid, player_name, drive_bay_access, block_modification_access " +
                     "FROM security_terminal_players WHERE terminal_id = ? ORDER BY player_name")) {
            
            stmt.setString(1, terminalId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String playerUuid = rs.getString("player_uuid");
                    String playerName = rs.getString("player_name");
                    boolean driveBayAccess = rs.getBoolean("drive_bay_access");
                    boolean blockModAccess = rs.getBoolean("block_modification_access");
                    
                    trustedPlayers.add(new TrustedPlayer(playerUuid, playerName, driveBayAccess, blockModAccess));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading trusted players: " + e.getMessage());
        }
        
        updateDisplayedPlayers();
    }

    private void updateDisplayedPlayers() {
        // Clear current player display (first 4 rows)
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, null);
        }

        // Calculate start and end indices for current page
        int startIndex = currentPage * playersPerPage;
        int endIndex = Math.min(startIndex + playersPerPage, trustedPlayers.size());

        // Display players for current page
        for (int i = startIndex; i < endIndex; i++) {
            TrustedPlayer trustedPlayer = trustedPlayers.get(i);
            int slot = i - startIndex;

            ItemStack playerSkull = createPlayerSkull(trustedPlayer);
            inventory.setItem(slot, playerSkull);
        }

        updateNavigationItems();
        updateControlItems();
    }

    private ItemStack createPlayerSkull(TrustedPlayer trustedPlayer) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        
        // Set player profile for skull texture
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(trustedPlayer.uuid));
        meta.setOwningPlayer(offlinePlayer);
        
        meta.displayName(Component.text(trustedPlayer.name, NamedTextColor.GREEN));
        
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(Component.text("Permissions:", NamedTextColor.YELLOW));
        
        // Drive Bay Access
        if (trustedPlayer.driveBayAccess) {
            lore.add(Component.text("✓ Drive Bay Access", NamedTextColor.GREEN));
        } else {
            lore.add(Component.text("✗ Drive Bay Access", NamedTextColor.RED));
        }
        
        // Block Modification Access
        if (trustedPlayer.blockModAccess) {
            lore.add(Component.text("✓ Block Modification", NamedTextColor.GREEN));
        } else {
            lore.add(Component.text("✗ Block Modification", NamedTextColor.RED));
        }
        
        lore.add(Component.empty());
        lore.add(Component.text("Left-click: Toggle Drive Bay Access", NamedTextColor.AQUA));
        lore.add(Component.text("Right-click: Toggle Block Modification", NamedTextColor.AQUA));
        lore.add(Component.text("Shift-click: Remove Player", NamedTextColor.RED));
        
        meta.lore(lore);
        skull.setItemMeta(meta);
        
        return skull;
    }

    private int getMaxPages() {
        if (trustedPlayers.isEmpty()) {
            return 1;
        }
        return (int) Math.ceil((double) trustedPlayers.size() / playersPerPage);
    }

    private String getOwnerName() {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(UUID.fromString(ownerUuid));
        return owner.getName() != null ? owner.getName() : "Unknown";
    }

    public void open(Player player) {
        player.openInventory(inventory);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();

        // Handle navigation clicks
        if (slot == 45) { // Previous page
            if (currentPage > 0) {
                currentPage--;
                updateDisplayedPlayers();
            }
            return;
        }
        
        if (slot == 53) { // Next page
            int maxPages = getMaxPages();
            if (currentPage < maxPages - 1) {
                currentPage++;
                updateDisplayedPlayers();
            }
            return;
        }

        // Handle add player button
        if (slot == 48) {
            player.closeInventory();
            player.sendMessage(Component.text("Type the name of the player you want to add:", NamedTextColor.YELLOW));
            plugin.getGUIManager().registerPlayerInput(player, this);
            return;
        }

        // Handle player skull clicks (slots 0-35)
        if (slot >= 0 && slot < 36) {
            int playerIndex = currentPage * playersPerPage + slot;
            if (playerIndex < trustedPlayers.size()) {
                TrustedPlayer trustedPlayer = trustedPlayers.get(playerIndex);
                ClickType clickType = event.getClick();
                
                if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                    // Remove player
                    removeTrustedPlayer(trustedPlayer);
                } else if (clickType == ClickType.LEFT) {
                    // Toggle drive bay access
                    toggleDriveBayAccess(trustedPlayer);
                } else if (clickType == ClickType.RIGHT) {
                    // Toggle block modification access
                    toggleBlockModAccess(trustedPlayer);
                }
            }
        }
    }

    private void toggleDriveBayAccess(TrustedPlayer trustedPlayer) {
        trustedPlayer.driveBayAccess = !trustedPlayer.driveBayAccess;
        updatePlayerPermissions(trustedPlayer);
        updateDisplayedPlayers();
        
        viewer.sendMessage(Component.text(
            (trustedPlayer.driveBayAccess ? "Granted" : "Revoked") + " drive bay access for " + trustedPlayer.name,
            trustedPlayer.driveBayAccess ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
    }

    private void toggleBlockModAccess(TrustedPlayer trustedPlayer) {
        trustedPlayer.blockModAccess = !trustedPlayer.blockModAccess;
        updatePlayerPermissions(trustedPlayer);
        updateDisplayedPlayers();
        
        viewer.sendMessage(Component.text(
            (trustedPlayer.blockModAccess ? "Granted" : "Revoked") + " block modification access for " + trustedPlayer.name,
            trustedPlayer.blockModAccess ? NamedTextColor.GREEN : NamedTextColor.RED
        ));
    }

    private void updatePlayerPermissions(TrustedPlayer trustedPlayer) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE security_terminal_players SET drive_bay_access = ?, block_modification_access = ? " +
                     "WHERE terminal_id = ? AND player_uuid = ?")) {
            
            stmt.setBoolean(1, trustedPlayer.driveBayAccess);
            stmt.setBoolean(2, trustedPlayer.blockModAccess);
            stmt.setString(3, terminalId);
            stmt.setString(4, trustedPlayer.uuid);
            
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error updating player permissions: " + e.getMessage());
        }
    }

    private void removeTrustedPlayer(TrustedPlayer trustedPlayer) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM security_terminal_players WHERE terminal_id = ? AND player_uuid = ?")) {
            
            stmt.setString(1, terminalId);
            stmt.setString(2, trustedPlayer.uuid);
            
            stmt.executeUpdate();
            
            trustedPlayers.remove(trustedPlayer);
            
            // Adjust current page if needed
            int maxPages = getMaxPages();
            if (currentPage >= maxPages && currentPage > 0) {
                currentPage = maxPages - 1;
            }
            
            updateDisplayedPlayers();
            
            viewer.sendMessage(Component.text("Removed " + trustedPlayer.name + " from trusted players", NamedTextColor.YELLOW));
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Error removing trusted player: " + e.getMessage());
        }
    }

    public void addTrustedPlayer(String playerName) {
        @SuppressWarnings("deprecation")
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        
        if (offlinePlayer.getUniqueId() == null) {
            viewer.sendMessage(Component.text("Player not found: " + playerName, NamedTextColor.RED));
            return;
        }
        
        String playerUuid = offlinePlayer.getUniqueId().toString();
        
        // Check if player is already trusted
        for (TrustedPlayer trusted : trustedPlayers) {
            if (trusted.uuid.equals(playerUuid)) {
                viewer.sendMessage(Component.text(playerName + " is already a trusted player", NamedTextColor.YELLOW));
                return;
            }
        }
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO security_terminal_players (terminal_id, player_uuid, player_name, drive_bay_access, block_modification_access) " +
                     "VALUES (?, ?, ?, ?, ?)")) {
            
            stmt.setString(1, terminalId);
            stmt.setString(2, playerUuid);
            stmt.setString(3, offlinePlayer.getName() != null ? offlinePlayer.getName() : playerName);
            stmt.setBoolean(4, false); // Default to no access
            stmt.setBoolean(5, false);
            
            stmt.executeUpdate();
            
            loadTrustedPlayers(); // Reload to get the new player
            
            viewer.sendMessage(Component.text("Added " + playerName + " as a trusted player", NamedTextColor.GREEN));
            
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE constraint failed")) {
                viewer.sendMessage(Component.text(playerName + " is already a trusted player", NamedTextColor.YELLOW));
            } else {
                plugin.getLogger().severe("Error adding trusted player: " + e.getMessage());
                viewer.sendMessage(Component.text("Error adding player: " + e.getMessage(), NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        // Unregister this listener
        InventoryClickEvent.getHandlerList().unregister(this);
        InventoryCloseEvent.getHandlerList().unregister(this);

        // Remove from GUI manager
        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().closeGUI(player);
        }
    }

    public Location getTerminalLocation() {
        return terminalLocation;
    }

    public String getTerminalId() {
        return terminalId;
    }

    private static class TrustedPlayer {
        String uuid;
        String name;
        boolean driveBayAccess;
        boolean blockModAccess;

        TrustedPlayer(String uuid, String name, boolean driveBayAccess, boolean blockModAccess) {
            this.uuid = uuid;
            this.name = name;
            this.driveBayAccess = driveBayAccess;
            this.blockModAccess = blockModAccess;
        }
    }
}