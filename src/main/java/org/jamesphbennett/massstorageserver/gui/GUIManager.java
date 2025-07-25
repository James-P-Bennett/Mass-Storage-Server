package org.jamesphbennett.massstorageserver.gui;

import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GUIManager {

    private final MassStorageServer plugin;
    private final Map<UUID, String> playerCurrentGUI = new ConcurrentHashMap<>();
    private final Map<UUID, Location> playerGUILocation = new ConcurrentHashMap<>();
    private final Map<UUID, Object> playerGUIInstance = new ConcurrentHashMap<>();

    public GUIManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Open a Drive Bay GUI for a player
     */
    public void openDriveBayGUI(Player player, Location driveBayLocation, String networkId) {
        try {
            DriveBayGUI gui = new DriveBayGUI(plugin, driveBayLocation, networkId);
            gui.open(player);

            playerCurrentGUI.put(player.getUniqueId(), "DRIVE_BAY");
            playerGUILocation.put(player.getUniqueId(), driveBayLocation);
            playerGUIInstance.put(player.getUniqueId(), gui);
        } catch (Exception e) {
            player.sendMessage("§cError opening Drive Bay GUI: " + e.getMessage());
            plugin.getLogger().severe("Error opening Drive Bay GUI: " + e.getMessage());
        }
    }

    /**
     * Open an MSS Terminal GUI for a player
     */
    public void openTerminalGUI(Player player, Location terminalLocation, String networkId) {
        try {
            TerminalGUI gui = new TerminalGUI(plugin, terminalLocation, networkId);
            gui.open(player);

            playerCurrentGUI.put(player.getUniqueId(), "TERMINAL");
            playerGUILocation.put(player.getUniqueId(), terminalLocation);
            playerGUIInstance.put(player.getUniqueId(), gui);
        } catch (Exception e) {
            player.sendMessage("§cError opening Terminal GUI: " + e.getMessage());
            plugin.getLogger().severe("Error opening Terminal GUI: " + e.getMessage());
        }
    }

    /**
     * Close any open MSS GUI for a player
     */
    public void closeGUI(Player player) {
        playerCurrentGUI.remove(player.getUniqueId());
        playerGUILocation.remove(player.getUniqueId());
        playerGUIInstance.remove(player.getUniqueId());
    }

    /**
     * Check if a player has an MSS GUI open
     */
    public boolean hasGUIOpen(Player player) {
        return playerCurrentGUI.containsKey(player.getUniqueId());
    }

    /**
     * Get the type of GUI a player has open
     */
    public String getOpenGUIType(Player player) {
        return playerCurrentGUI.get(player.getUniqueId());
    }

    /**
     * Get the location of the block the player's GUI is associated with
     */
    public Location getGUILocation(Player player) {
        return playerGUILocation.get(player.getUniqueId());
    }

    /**
     * Refresh a player's GUI if it's a terminal (used when items are added/removed)
     */
    public void refreshPlayerTerminal(Player player) {
        if ("TERMINAL".equals(getOpenGUIType(player))) {
            Object guiInstance = playerGUIInstance.get(player.getUniqueId());
            if (guiInstance instanceof TerminalGUI terminalGUI) {
                terminalGUI.refresh();
            }
        }
    }

    /**
     * Refresh a player's GUI if it's a drive bay (used when disk states change)
     */
    public void refreshPlayerDriveBay(Player player) {
        if ("DRIVE_BAY".equals(getOpenGUIType(player))) {
            Object guiInstance = playerGUIInstance.get(player.getUniqueId());
            if (guiInstance instanceof DriveBayGUI driveBayGUI) {
                driveBayGUI.refreshDiskDisplay();
            }
        }
    }

    /**
     * Refresh all terminal GUIs for a specific network (useful when items are stored/retrieved)
     */
    public void refreshNetworkTerminals(String networkId) {
        for (Map.Entry<UUID, String> entry : playerCurrentGUI.entrySet()) {
            if ("TERMINAL".equals(entry.getValue())) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    Object guiInstance = playerGUIInstance.get(entry.getKey());
                    if (guiInstance instanceof TerminalGUI terminalGUI) {
                        // Check if this terminal is for the same network
                        // You might want to add network validation here
                        terminalGUI.refresh();
                    }
                }
            }
        }
    }

    /**
     * Refresh all drive bay GUIs for a specific network (useful when disk states change)
     */
    public void refreshNetworkDriveBays(String networkId) {
        for (Map.Entry<UUID, String> entry : playerCurrentGUI.entrySet()) {
            if ("DRIVE_BAY".equals(entry.getValue())) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    Object guiInstance = playerGUIInstance.get(entry.getKey());
                    if (guiInstance instanceof DriveBayGUI driveBayGUI) {
                        // Check if this drive bay is for the same network
                        // You might want to add network validation here
                        driveBayGUI.refreshDiskDisplay();
                    }
                }
            }
        }
    }

    /**
     * Force close all GUIs (for plugin shutdown)
     */
    public void closeAllGUIs() {
        playerCurrentGUI.clear();
        playerGUILocation.clear();
        playerGUIInstance.clear();
    }
}