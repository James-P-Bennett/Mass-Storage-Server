
package org.jamesphbennett.massstorageserver.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
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
    private final Map<UUID, String> playerGUINetworkId = new ConcurrentHashMap<>();
    private final Map<UUID, Object> playerGUIInstance = new ConcurrentHashMap<>();

    private final Set<String> modifiedNetworks = ConcurrentHashMap.newKeySet();
    private final Map<UUID, TerminalGUI> playersAwaitingSearchInput = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitRunnable> searchTimeoutTasks = new ConcurrentHashMap<>();
    private static final int SEARCH_TIMEOUT_SECONDS = 10;
    private final Map<String, String> terminalSearchTerms = new ConcurrentHashMap<>();
    private final Map<String, Boolean> terminalQuantitySort = new ConcurrentHashMap<>();

    public GUIManager(MassStorageServer plugin) {
        this.plugin = plugin;
    }

    /**
     * Mark a network as modified (when disks are added/removed)
     */
    public void markNetworkModified(String networkId) {
        modifiedNetworks.add(networkId);
        plugin.debugLog("Marked network " + networkId + " as modified");
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
        plugin.debugLog("Cleared modified flag for network " + networkId);
    }

    /**
     * Open an Exporter GUI for a player (context-aware based on target container)
     */
    public void openExporterGUI(Player player, Location exporterLocation, String exporterId, String networkId) {
        try {
            // Validate network is still valid
            if (!isNetworkValid(networkId)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.invalid"));
                return;
            }

            // Check if exporter is still physically connected to its network
            if (!plugin.getExporterManager().isExporterConnectedToItsNetwork(exporterId)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.not-connected-assigned", "device", "exporter"));
                return;
            }

            plugin.debugLog("Opening exporter GUI for player " + player.getName() +
                    " at " + exporterLocation + " with exporter ID: " + exporterId);

            // Detect target container type and open appropriate GUI
            Container targetContainer = getTargetContainer(exporterLocation);
            Object gui;
            
            if (targetContainer != null && isFurnaceType(targetContainer)) {
                // Open furnace-specific GUI
                plugin.debugLog("Detected furnace target - opening FurnaceExporterGUI");
                FurnaceExporterGUI furnaceGUI = new FurnaceExporterGUI(plugin, exporterLocation, exporterId, networkId);
                furnaceGUI.open(player);
                gui = furnaceGUI;
                playerCurrentGUI.put(player.getUniqueId(), "FURNACE_EXPORTER");
            } else {
                // Open generic exporter GUI
                plugin.debugLog("Using generic ExporterGUI");
                ExporterGUI exporterGUI = new ExporterGUI(plugin, exporterLocation, exporterId, networkId);
                exporterGUI.open(player);
                gui = exporterGUI;
                playerCurrentGUI.put(player.getUniqueId(), "EXPORTER");
            }

            playerGUINetworkId.put(player.getUniqueId(), networkId);
            playerGUIInstance.put(player.getUniqueId(), gui);

            plugin.debugLog("Successfully opened exporter GUI for player " + player.getName());
        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.gui.error-opening", "gui", "Exporter", "error", e.getMessage()));
            plugin.getLogger().severe("Error opening Exporter GUI: " + e.getMessage());
            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * Open the appropriate importer GUI for a player
     */
    public void openImporterGUI(Player player, Location importerLocation, String importerId, String networkId) {
        try {
            // Validate network is still valid
            if (!isNetworkValid(networkId)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.invalid"));
                return;
            }

            // Check if importer is still physically connected to any network
            if (!plugin.getImporterManager().isImporterConnectedToAnyNetwork(importerId)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.not-connected-any", "device", "importer"));
                return;
            }

            plugin.debugLog("Opening importer GUI for player " + player.getName() +
                    " at " + importerLocation + " with importer ID: " + importerId);

            // For importers, we use the same ImporterGUI for all container types
            // The GUI itself detects the target type and adapts accordingly
            ImporterGUI importerGUI = new ImporterGUI(plugin, importerLocation, importerId, networkId);
            importerGUI.open(player);

            // Track the GUI
            playerCurrentGUI.put(player.getUniqueId(), "IMPORTER");
            playerGUINetworkId.put(player.getUniqueId(), networkId);
            playerGUIInstance.put(player.getUniqueId(), importerGUI);

            plugin.debugLog("Successfully opened importer GUI for player " + player.getName());
        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.gui.error-opening", "gui", "Importer", "error", e.getMessage()));
            plugin.getLogger().severe("Error opening Importer GUI: " + e.getMessage());
            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * Get the target container that the exporter is physically attached to
     */
    private Container getTargetContainer(Location exporterLocation) {
        try {
            Block exporterBlock = exporterLocation.getBlock();
            Block attachedBlock = null;

            if (exporterBlock.getType() == Material.PLAYER_HEAD) {
                // Floor mounted head - check block below
                attachedBlock = exporterBlock.getRelative(org.bukkit.block.BlockFace.DOWN);
            } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
                // Wall mounted head - check block it's attached to
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) exporterBlock.getBlockData();
                org.bukkit.block.BlockFace facing = directional.getFacing();
                // The block the wall head is attached to is in the opposite direction
                attachedBlock = exporterBlock.getRelative(facing.getOppositeFace());
            }

            // Check if the attached block is a container
            if (attachedBlock != null && attachedBlock.getState() instanceof Container container) {
                return container;
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking attached block for exporter: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if a container is a furnace-type container
     */
    private boolean isFurnaceType(Container container) {
        Material type = container.getBlock().getType();
        return type == Material.FURNACE || 
               type == Material.BLAST_FURNACE || 
               type == Material.SMOKER;
    }

    /**
     * Open a Drive Bay GUI for a player (UPDATED to allow standalone access)
     */
    public void openDriveBayGUI(Player player, Location driveBayLocation, String networkId) {
        try {
            // Don't validate network - allow access to drive bays regardless of network status
            // The drive bay GUI will show the network status and allow disk management
            plugin.debugLog("Opening drive bay GUI for player " + player.getName() +
                    " at " + driveBayLocation + " with network ID: " + networkId);

            DriveBayGUI gui = new DriveBayGUI(plugin, driveBayLocation, networkId);
            gui.open(player);

            playerCurrentGUI.put(player.getUniqueId(), "DRIVE_BAY");
            if (networkId != null) {
                playerGUINetworkId.put(player.getUniqueId(), networkId);
            }
            playerGUIInstance.put(player.getUniqueId(), gui);

            plugin.debugLog("Successfully opened drive bay GUI for player " + player.getName());
        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.gui.error-opening", "gui", "Drive Bay", "error", e.getMessage()));
            plugin.getLogger().severe("Error opening Drive Bay GUI: " + e.getMessage());
            plugin.getLogger().severe("Stack trace: " + java.util.Arrays.toString(e.getStackTrace()));
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
            plugin.debugLog("Cleared search term for terminal at " + key);
        } else {
            terminalSearchTerms.put(key, searchTerm.trim());
            plugin.debugLog("Saved search term '" + searchTerm + "' for terminal at " + key);
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
            plugin.debugLog("Saved quantity sort setting for terminal at " + key);
        } else {
            terminalQuantitySort.remove(key);
            plugin.debugLog("Cleared quantity sort setting for terminal at " + key + " (using default alphabetical)");
        }
    }

    public void openTerminalGUI(Player player, Location terminalLocation, String networkId) {
        try {
            // Cancel any pending search input when opening a terminal
            if (isAwaitingSearchInput(player)) {
                cancelSearchInput(player);
                plugin.debugLog("Cancelled pending search input for player " + player.getName() + " when opening terminal");
            }

            // Validate network is still valid
            if (!isNetworkValid(networkId)) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.invalid"));
                return;
            }

            TerminalGUI gui = new TerminalGUI(plugin, terminalLocation, networkId);

            // If network was modified while terminal was closed, refresh immediately
            if (isNetworkModified(networkId)) {
                plugin.debugLog("Network " + networkId + " was modified, refreshing terminal on open");
                gui.refresh();
                clearNetworkModified(networkId);
            }

            gui.open(player);

            playerCurrentGUI.put(player.getUniqueId(), "TERMINAL");
            playerGUINetworkId.put(player.getUniqueId(), networkId);
            playerGUIInstance.put(player.getUniqueId(), gui);

            plugin.debugLog("Opened terminal GUI for player " + player.getName() + " in network " + networkId);
        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.gui.error-opening", "gui", "Terminal", "error", e.getMessage()));
            plugin.getLogger().severe("Error opening Terminal GUI: " + e.getMessage());
        }
    }

    public void openSecurityTerminalGUI(Player player, Location terminalLocation, String terminalId, String ownerUuid) {
        try {
            SecurityTerminalGUI gui = new SecurityTerminalGUI(plugin, terminalLocation, terminalId, ownerUuid, player);
            gui.open(player);

            playerCurrentGUI.put(player.getUniqueId(), "SECURITY_TERMINAL");
            playerGUIInstance.put(player.getUniqueId(), gui);

            plugin.debugLog("Opened security terminal GUI for player " + player.getName());
        } catch (Exception e) {
            player.sendMessage(Component.text("Error opening security terminal: " + e.getMessage(), NamedTextColor.RED));
            plugin.getLogger().severe("Error opening Security Terminal GUI: " + e.getMessage());
        }
    }

    // Player input handling for security terminal
    private final Map<UUID, SecurityTerminalGUI> playersAwaitingPlayerInput = new ConcurrentHashMap<>();

    public void registerPlayerInput(Player player, SecurityTerminalGUI securityGUI) {
        playersAwaitingPlayerInput.put(player.getUniqueId(), securityGUI);
        plugin.debugLog("Registered player " + player.getName() + " for player input");
    }

    public boolean isAwaitingPlayerInput(Player player) {
        return playersAwaitingPlayerInput.containsKey(player.getUniqueId());
    }

    public void handlePlayerInput(Player player, String input) {
        SecurityTerminalGUI gui = playersAwaitingPlayerInput.remove(player.getUniqueId());
        if (gui != null) {
            gui.addTrustedPlayer(input);
            // Reopen the GUI
            gui.open(player);
        }
    }

    public void cancelPlayerInput(Player player) {
        playersAwaitingPlayerInput.remove(player.getUniqueId());
        player.sendMessage(Component.text("Player input cancelled.", NamedTextColor.YELLOW));
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
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.search.timeout"));
                    }
                }
            }
        };

        // Schedule timeout task
        timeoutTask.runTaskLater(plugin, SEARCH_TIMEOUT_SECONDS * 20L);
        searchTimeoutTasks.put(playerId, timeoutTask);

        plugin.debugLog("Registered player " + player.getName() + " for search input with " + SEARCH_TIMEOUT_SECONDS + "s timeout");
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

        plugin.debugLog("Received search input from player " + player.getName() + ": '" + message + "'");

        // Save search term to the specific terminal location
        Location terminalLocation = terminalGUI.getTerminalLocation();

        if (message.equalsIgnoreCase("cancel")) {
            // Clear search for this terminal
            setTerminalSearchTerm(terminalLocation, null);
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.search.cancelled"));
        } else if (message.trim().isEmpty()) {
            // Clear search for this terminal
            setTerminalSearchTerm(terminalLocation, null);
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.search.cleared"));
        } else {
            // Save search term for this terminal
            setTerminalSearchTerm(terminalLocation, message.trim());
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.search.saved", "term", message.trim()));
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
            plugin.debugLog("Cancelled search input for player " + player.getName());
        }
    }

    /**
     * Close any open MSS GUI for a player
     */
    public void closeGUI(Player player) {
        String guiType = playerCurrentGUI.remove(player.getUniqueId());
        String networkId = playerGUINetworkId.remove(player.getUniqueId());
        playerGUIInstance.remove(player.getUniqueId());

        // Also cancel any pending search input
        cancelSearchInput(player);

        if (guiType != null) {
            plugin.debugLog("Closed " + guiType + " GUI for player " + player.getName() +
                    (networkId != null ? " (was in network " + networkId + ")" : ""));
        }
    }

    /**
     * Force close a specific player's GUI with a message
     */
    public void forceCloseGUI(Player player, String reason) {
        if (hasGUIOpen(player)) {
            player.closeInventory();
            player.sendMessage(Component.text(reason, NamedTextColor.YELLOW));
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
        plugin.debugLog("Starting comprehensive refresh of terminals for network: " + networkId);
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
                            plugin.debugLog("Closing terminal for player " + player.getName() + " - network invalid");
                        } else {
                            // Network is valid, refresh the terminal
                            Object guiInstance = playerGUIInstance.get(entry.getKey());
                            if (guiInstance instanceof TerminalGUI terminalGUI) {
                                terminalGUI.refresh();
                                refreshCount++;
                                plugin.debugLog("Refreshed terminal for player " + player.getName() + " in network " + networkId);
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

        plugin.debugLog("Refreshed " + refreshCount + " terminals for network " + networkId +
                (!playersToClose.isEmpty() ? " (closed " + playersToClose.size() + " invalid terminals)" : ""));
    }

    /**
     * Handle network invalidation (when network is broken/dissolved)
     * This should be called from NetworkManager when a network is unregistered
     */
    public void handleNetworkInvalidated(String networkId) {
        plugin.debugLog("Handling network invalidation for: " + networkId);

        List<Player> terminalsToClose = new ArrayList<>();
        List<Player> driveBaysToNotify = new ArrayList<>();

        // Find all players with GUIs open for this network
        for (Map.Entry<UUID, String> entry : playerGUINetworkId.entrySet()) {
            if (networkId.equals(entry.getValue())) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    String guiType = playerCurrentGUI.get(entry.getKey());

                    if ("TERMINAL".equals(guiType)) {
                        // Close terminals for invalid networks
                        terminalsToClose.add(player);
                    } else if ("DRIVE_BAY".equals(guiType)) {
                        // Keep drive bays open but notify about network status
                        driveBaysToNotify.add(player);
                    }
                }
            }
        }

        // Close terminal GUIs for this network
        for (Player player : terminalsToClose) {
            forceCloseGUI(player, "Storage network dissolved - blocks have been disconnected.");
        }

        // Notify drive bay users but keep their GUIs open
        for (Player player : driveBaysToNotify) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.dissolved-drive-bay"));
            // Refresh the drive bay to show updated network status
            refreshPlayerDriveBay(player);
        }

        // Handle exporter disconnections when network is invalidated
        plugin.getExporterManager().handleNetworkInvalidated(networkId);
        plugin.getImporterManager().handleNetworkInvalidated(networkId);

        // Clear the modified flag for this network
        clearNetworkModified(networkId);

        plugin.debugLog("Closed " + terminalsToClose.size() + " terminal GUIs and notified " +
                driveBaysToNotify.size() + " drive bay users for invalidated network " + networkId);
    }

    /**
     * Check if a network is still valid (UPDATED to handle standalone networks)
     */
    private boolean isNetworkValid(String networkId) {
        // Standalone networks are never "valid" in the traditional sense, but shouldn't cause errors
        if (networkId != null && (networkId.startsWith("standalone_") || networkId.startsWith("orphaned_"))) {
            return false; // Standalone/orphaned networks are not valid networks, but they're allowed for drive bay access
        }

        try {
            return plugin.getNetworkManager().isNetworkValid(networkId);
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking network validity for " + networkId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Periodic validation of open GUIs (UPDATED for standalone support)
     */
    public void validateOpenGUIs() {
        List<Player> terminalsToClose = new ArrayList<>();

        for (Map.Entry<UUID, String> entry : playerGUINetworkId.entrySet()) {
            String networkId = entry.getValue();
            Player player = plugin.getServer().getPlayer(entry.getKey());

            if (player != null && player.isOnline()) {
                String guiType = playerCurrentGUI.get(entry.getKey());

                // Only validate terminals - drive bays can stay open regardless of network status
                if ("TERMINAL".equals(guiType) && !isNetworkValid(networkId)) {
                    terminalsToClose.add(player);
                }
            }
        }

        for (Player player : terminalsToClose) {
            forceCloseGUI(player, "Storage network is no longer valid.");
        }

        if (!terminalsToClose.isEmpty()) {
            plugin.debugLog("Validated and closed " + terminalsToClose.size() + " invalid terminal GUIs");
        }
    }
    /**
     * Refresh exporter GUIs for a specific exporter
     */
    public void refreshExporterGUIs(String exporterId) {
        plugin.debugLog("Refreshing exporter GUIs for exporter " + exporterId);

        // Find any players with the exporter GUI open for this specific exporter
        for (Map.Entry<UUID, Object> entry : playerGUIInstance.entrySet()) {
            UUID playerId = entry.getKey();
            Object guiInstance = entry.getValue();

            if (guiInstance instanceof ExporterGUI exporterGUI) {
                // Check if this GUI is for the specific exporter
                if (exporterId.equals(exporterGUI.getExporterId())) {
                    Player player = plugin.getServer().getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        plugin.debugLog("Refreshing exporter GUI for player " + player.getName());

                        // Call the public setupGUI() method to refresh the display
                        exporterGUI.setupGUI();

                        plugin.debugLog("Successfully refreshed exporter GUI for player " + player.getName());
                    }
                }
            }
        }
    }

    /**
     * Refresh all open importer GUIs for a specific importer
     */
    public void refreshImporterGUIs(String importerId) {
        plugin.debugLog("Refreshing importer GUIs for importer " + importerId);

        // Find any players with the importer GUI open for this specific importer
        for (Map.Entry<UUID, Object> entry : playerGUIInstance.entrySet()) {
            UUID playerId = entry.getKey();
            Object guiInstance = entry.getValue();

            if (guiInstance instanceof ImporterGUI importerGUI) {
                // Check if this GUI is for the specific importer
                if (importerId.equals(importerGUI.getImporterId())) {
                    Player player = plugin.getServer().getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        plugin.debugLog("Refreshing importer GUI for player " + player.getName());

                        // Call the public setupGUI() method to refresh the display
                        importerGUI.setupGUI();

                        plugin.debugLog("Successfully refreshed importer GUI for player " + player.getName());
                    }
                }
            }
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
        playerGUINetworkId.clear();
        playerGUIInstance.clear();
        modifiedNetworks.clear();
        playersAwaitingSearchInput.clear();
        searchTimeoutTasks.clear();
        terminalSearchTerms.clear();
        terminalQuantitySort.clear();
    }
}