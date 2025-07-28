// Enhanced GUIManager with comprehensive network change handling and search input

package org.jamesphbennett.massstorageserver.gui;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.List;

public class GUIManager {

    private final MassStorageServer plugin;
    private final Map<UUID, String> playerCurrentGUI = new ConcurrentHashMap<>();
    private final Map<UUID, Location> playerGUILocation = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerGUINetworkId = new ConcurrentHashMap<>();
    private final Map<UUID, Object> playerGUIInstance = new ConcurrentHashMap<>();

    // Track when networks have been modified
    private final Set<String> modifiedNetworks = ConcurrentHashMap.newKeySet();

    // Search input handling
    private final Map<UUID, TerminalGUI> playersAwaitingSearchInput = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> searchTimeoutTasks = new ConcurrentHashMap<>();
    private static final int SEARCH_TIMEOUT_SECONDS = 10;

    // Terminal-specific search state storage (per location)
    private final Map<String, String> terminalSearchTerms = new ConcurrentHashMap<>();

    // Terminal-specific sorting state storage (per location)
    private final Map<String, Boolean> terminalQuantitySort = new ConcurrentHashMap<>();

    public GUIManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Mark a network as modified (when disks are added/removed)
     */
    public void markNetworkModified(String networkId) {
        modifiedNetworks.add(networkId);
        plugin.getLogger().info("Marked network " + networkId + " as modified");
    }

    /**
     * Check if a network has been modified since last terminal refresh
     */
    public boolean isNetworkModified(String networkId) {
        return modifiedNetworks.contains(networkId);
    }

    /**
     * Clear the modified flag for a network (after terminal refresh)
     */
    public void clearNetworkModified(String networkId) {
        modifiedNetworks.remove(networkId);
        plugin.getLogger().info("Cleared modified flag for network " + networkId);
    }

    /**
     * Open a Drive Bay GUI for a player
     */
    public void openDriveBayGUI(Player player, Location driveBayLocation, String networkId) {
        try {
            // Validate network is still valid
            if (!isNetworkValid(networkId)) {
                player.sendMessage(ChatColor.RED + "This drive bay is no longer connected to a valid network.");
                return;
            }

            DriveBayGUI gui = new DriveBayGUI(plugin, driveBayLocation, networkId);
            gui.open(player);

            playerCurrentGUI.put(player.getUniqueId(), "DRIVE_BAY");
            playerGUILocation.put(player.getUniqueId(), driveBayLocation);
            playerGUINetworkId.put(player.getUniqueId(), networkId);
            playerGUIInstance.put(player.getUniqueId(), gui);
        } catch (Exception e) {
            player.sendMessage("§cError opening Drive Bay GUI: " + e.getMessage());
            plugin.getLogger().severe("Error opening Drive Bay GUI: " + e.getMessage());
        }
    }

    /**
     * Generate a unique key for a terminal location
     */
    private String getTerminalKey(Location location) {
        return location.getWorld().getName() + "_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
    }

    /**
     * Get saved search term for a terminal location
     */
    public String getTerminalSearchTerm(Location terminalLocation) {
        return terminalSearchTerms.get(getTerminalKey(terminalLocation));
    }

    /**
     * Save search term for a terminal location
     */
    public void setTerminalSearchTerm(Location terminalLocation, String searchTerm) {
        String key = getTerminalKey(terminalLocation);
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            terminalSearchTerms.remove(key);
            plugin.getLogger().info("Cleared search term for terminal at " + key);
        } else {
            terminalSearchTerms.put(key, searchTerm.trim());
            plugin.getLogger().info("Saved search term '" + searchTerm + "' for terminal at " + key);
        }
    }

    /**
     * Get saved quantity sort setting for a terminal location
     */
    public boolean getTerminalQuantitySort(Location terminalLocation) {
        return terminalQuantitySort.getOrDefault(getTerminalKey(terminalLocation), false);
    }

    /**
     * Save quantity sort setting for a terminal location
     */
    public void setTerminalQuantitySort(Location terminalLocation, boolean quantitySort) {
        String key = getTerminalKey(terminalLocation);
        if (quantitySort) {
            terminalQuantitySort.put(key, true);
            plugin.getLogger().info("Saved quantity sort setting for terminal at " + key);
        } else {
            terminalQuantitySort.remove(key);
            plugin.getLogger().info("Cleared quantity sort setting for terminal at " + key + " (using default alphabetical)");
        }
    }
    public void openTerminalGUI(Player player, Location terminalLocation, String networkId) {
        try {
            // Validate network is still valid
            if (!isNetworkValid(networkId)) {
                player.sendMessage(ChatColor.RED + "This terminal is no longer connected to a valid network.");
                return;
            }

            TerminalGUI gui = new TerminalGUI(plugin, terminalLocation, networkId);

            // If network was modified while terminal was closed, refresh immediately
            if (isNetworkModified(networkId)) {
                plugin.getLogger().info("Network " + networkId + " was modified, refreshing terminal on open");
                gui.refresh();
                clearNetworkModified(networkId);
            }

            gui.open(player);

            playerCurrentGUI.put(player.getUniqueId(), "TERMINAL");
            playerGUILocation.put(player.getUniqueId(), terminalLocation);
            playerGUINetworkId.put(player.getUniqueId(), networkId);
            playerGUIInstance.put(player.getUniqueId(), gui);

            plugin.getLogger().info("Opened terminal GUI for player " + player.getName() + " in network " + networkId);
        } catch (Exception e) {
            player.sendMessage("§cError opening Terminal GUI: " + e.getMessage());
            plugin.getLogger().severe("Error opening Terminal GUI: " + e.getMessage());
        }
    }

    /**
     * Register a player for search input (when they click the search button)
     */
    public void registerSearchInput(Player player, TerminalGUI terminalGUI) {
        UUID playerId = player.getUniqueId();

        // Cancel any existing timeout task
        BukkitRunnable existingTask = searchTimeoutTasks.remove(playerId);
        if (existingTask != null) {
            existingTask.cancel();
        }

        // Register for search input
        playersAwaitingSearchInput.put(playerId, terminalGUI);

        // Create timeout task
        BukkitRunnable timeoutTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (playersAwaitingSearchInput.remove(playerId) != null) {
                    searchTimeoutTasks.remove(playerId);
                    if (player.isOnline()) {
                        player.sendMessage(ChatColor.RED + "Search timed out.");
                    }
                }
            }
        };

        // Schedule timeout task
        timeoutTask.runTaskLater(plugin, SEARCH_TIMEOUT_SECONDS * 20L);
        searchTimeoutTasks.put(playerId, timeoutTask);

        plugin.getLogger().info("Registered player " + player.getName() + " for search input with " + SEARCH_TIMEOUT_SECONDS + "s timeout");
    }

    /**
     * Handle chat input for search (call this from PlayerListener)
     */
    public boolean handleSearchInput(Player player, String message) {
        UUID playerId = player.getUniqueId();
        TerminalGUI terminalGUI = playersAwaitingSearchInput.remove(playerId);

        if (terminalGUI == null) {
            return false; // Player is not awaiting search input
        }

        // Cancel timeout task
        BukkitRunnable timeoutTask = searchTimeoutTasks.remove(playerId);
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }

        plugin.getLogger().info("Received search input from player " + player.getName() + ": '" + message + "'");

        // Save search term to the specific terminal location
        Location terminalLocation = terminalGUI.getTerminalLocation();

        if (message.equalsIgnoreCase("cancel")) {
            // Clear search for this terminal
            setTerminalSearchTerm(terminalLocation, null);
            player.sendMessage(ChatColor.RED + "Search cancelled.");
        } else if (message.trim().isEmpty()) {
            // Clear search for this terminal
            setTerminalSearchTerm(terminalLocation, null);
            player.sendMessage(ChatColor.YELLOW + "Search cleared.");
        } else {
            // Save search term for this terminal
            setTerminalSearchTerm(terminalLocation, message.trim());
            player.sendMessage(ChatColor.GREEN + "Search saved: '" + message.trim() + "'.");
        }

        // DO NOT auto-reopen the terminal - let the player manually reopen it

        return true; // Chat message was handled and should be cancelled
    }

    /**
     * Check if a player is awaiting search input
     */
    public boolean isAwaitingSearchInput(Player player) {
        return playersAwaitingSearchInput.containsKey(player.getUniqueId());
    }

    /**
     * Cancel search input for a player (if they disconnect or something)
     */
    public void cancelSearchInput(Player player) {
        UUID playerId = player.getUniqueId();

        if (playersAwaitingSearchInput.remove(playerId) != null) {
            BukkitRunnable timeoutTask = searchTimeoutTasks.remove(playerId);
            if (timeoutTask != null) {
                timeoutTask.cancel();
            }
            plugin.getLogger().info("Cancelled search input for player " + player.getName());
        }
    }

    /**
     * Close any open MSS GUI for a player
     */
    public void closeGUI(Player player) {
        String guiType = playerCurrentGUI.remove(player.getUniqueId());
        String networkId = playerGUINetworkId.remove(player.getUniqueId());
        playerGUILocation.remove(player.getUniqueId());
        playerGUIInstance.remove(player.getUniqueId());

        // Also cancel any pending search input
        cancelSearchInput(player);

        if (guiType != null) {
            plugin.getLogger().info("Closed " + guiType + " GUI for player " + player.getName() +
                    (networkId != null ? " (was in network " + networkId + ")" : ""));
        }
    }

    /**
     * Force close a specific player's GUI with a message
     */
    public void forceCloseGUI(Player player, String reason) {
        if (hasGUIOpen(player)) {
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + reason);
            closeGUI(player);
        }
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
     * Get the network ID of the player's currently open GUI
     */
    public String getGUINetworkId(Player player) {
        return playerGUINetworkId.get(player.getUniqueId());
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
     * Refresh all terminal GUIs for a specific network (COMPREHENSIVE VERSION)
     */
    public void refreshNetworkTerminals(String networkId) {
        plugin.getLogger().info("Starting comprehensive refresh of terminals for network: " + networkId);
        int refreshCount = 0;
        List<Player> playersToClose = new ArrayList<>();

        // First check if network is still valid
        boolean networkValid = isNetworkValid(networkId);
        if (!networkValid) {
            plugin.getLogger().warning("Network " + networkId + " is no longer valid!");
        }

        for (Map.Entry<UUID, String> entry : playerCurrentGUI.entrySet()) {
            if ("TERMINAL".equals(entry.getValue())) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    String terminalNetworkId = playerGUINetworkId.get(entry.getKey());

                    if (networkId.equals(terminalNetworkId)) {
                        if (!networkValid) {
                            // Network is invalid, close the terminal
                            playersToClose.add(player);
                            plugin.getLogger().info("Closing terminal for player " + player.getName() + " - network invalid");
                        } else {
                            // Network is valid, refresh the terminal
                            Object guiInstance = playerGUIInstance.get(entry.getKey());
                            if (guiInstance instanceof TerminalGUI terminalGUI) {
                                terminalGUI.refresh();
                                refreshCount++;
                                plugin.getLogger().info("Refreshed terminal for player " + player.getName() + " in network " + networkId);
                            }
                        }
                    }
                }
            }
        }

        // Close invalid terminals
        for (Player player : playersToClose) {
            forceCloseGUI(player, "Network connection lost - please check your storage system.");
        }

        if (networkValid) {
            if (refreshCount == 0) {
                // No terminals were open, mark network as modified for when terminals are opened later
                markNetworkModified(networkId);
            } else {
                // Terminals were refreshed, clear the modified flag
                clearNetworkModified(networkId);
            }
        } else {
            // Network is invalid, clear the modified flag
            clearNetworkModified(networkId);
        }

        plugin.getLogger().info("Refreshed " + refreshCount + " terminals for network " + networkId +
                (playersToClose.size() > 0 ? " (closed " + playersToClose.size() + " invalid terminals)" : ""));
    }

    /**
     * Refresh all drive bay GUIs for a specific network (COMPREHENSIVE VERSION)
     */
    public void refreshNetworkDriveBays(String networkId) {
        plugin.getLogger().info("Starting comprehensive refresh of drive bays for network: " + networkId);
        int refreshCount = 0;
        List<Player> playersToClose = new ArrayList<>();

        // Check if network is still valid
        boolean networkValid = isNetworkValid(networkId);

        for (Map.Entry<UUID, String> entry : playerCurrentGUI.entrySet()) {
            if ("DRIVE_BAY".equals(entry.getValue())) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    String driveBayNetworkId = playerGUINetworkId.get(entry.getKey());

                    if (networkId.equals(driveBayNetworkId)) {
                        if (!networkValid) {
                            // Network is invalid, close the drive bay
                            playersToClose.add(player);
                            plugin.getLogger().info("Closing drive bay for player " + player.getName() + " - network invalid");
                        } else {
                            // Network is valid, refresh the drive bay
                            Object guiInstance = playerGUIInstance.get(entry.getKey());
                            if (guiInstance instanceof DriveBayGUI driveBayGUI) {
                                driveBayGUI.refreshDiskDisplay();
                                refreshCount++;
                                plugin.getLogger().info("Refreshed drive bay for player " + player.getName() + " in network " + networkId);
                            }
                        }
                    }
                }
            }
        }

        // Close invalid drive bays
        for (Player player : playersToClose) {
            forceCloseGUI(player, "Network connection lost - please check your storage system.");
        }

        plugin.getLogger().info("Refreshed " + refreshCount + " drive bays for network " + networkId +
                (playersToClose.size() > 0 ? " (closed " + playersToClose.size() + " invalid drive bays)" : ""));
    }

    /**
     * Handle network invalidation (when network is broken/dissolved)
     * This should be called from NetworkManager when a network is unregistered
     */
    public void handleNetworkInvalidated(String networkId) {
        plugin.getLogger().info("Handling network invalidation for: " + networkId);

        List<Player> playersToClose = new ArrayList<>();

        // Find all players with GUIs open for this network
        for (Map.Entry<UUID, String> entry : playerGUINetworkId.entrySet()) {
            if (networkId.equals(entry.getValue())) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    playersToClose.add(player);
                }
            }
        }

        // Close all GUIs for this network
        for (Player player : playersToClose) {
            forceCloseGUI(player, "Storage network dissolved - blocks have been disconnected.");
        }

        // Clear the modified flag for this network
        clearNetworkModified(networkId);

        plugin.getLogger().info("Closed " + playersToClose.size() + " GUIs for invalidated network " + networkId);
    }

    /**
     * Check if a network is still valid
     */
    private boolean isNetworkValid(String networkId) {
        try {
            return plugin.getNetworkManager().isNetworkValid(networkId);
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking network validity for " + networkId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Periodic validation of open GUIs (call this from a repeating task)
     */
    public void validateOpenGUIs() {
        List<Player> playersToClose = new ArrayList<>();

        for (Map.Entry<UUID, String> entry : playerGUINetworkId.entrySet()) {
            String networkId = entry.getValue();
            Player player = plugin.getServer().getPlayer(entry.getKey());

            if (player != null && player.isOnline()) {
                if (!isNetworkValid(networkId)) {
                    playersToClose.add(player);
                }
            }
        }

        for (Player player : playersToClose) {
            forceCloseGUI(player, "Storage network is no longer valid.");
        }

        if (!playersToClose.isEmpty()) {
            plugin.getLogger().info("Validated and closed " + playersToClose.size() + " invalid GUIs");
        }
    }

    /**
     * Force close all GUIs (for plugin shutdown)
     */
    public void closeAllGUIs() {
        // Close all open GUIs gracefully
        for (UUID playerId : new ArrayList<>(playerCurrentGUI.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.closeInventory();
            }
        }

        // Cancel all search input tasks
        for (BukkitRunnable task : searchTimeoutTasks.values()) {
            task.cancel();
        }

        playerCurrentGUI.clear();
        playerGUILocation.clear();
        playerGUINetworkId.clear();
        playerGUIInstance.clear();
        modifiedNetworks.clear();
        playersAwaitingSearchInput.clear();
        searchTimeoutTasks.clear();
        terminalSearchTerms.clear();
        terminalQuantitySort.clear();
    }
}