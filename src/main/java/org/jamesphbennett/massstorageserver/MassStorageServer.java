package org.jamesphbennett.massstorageserver;

import org.bukkit.plugin.java.JavaPlugin;
import org.jamesphbennett.massstorageserver.commands.MSSCommand;
import org.jamesphbennett.massstorageserver.database.DatabaseManager;
import org.jamesphbennett.massstorageserver.listeners.BlockListener;
import org.jamesphbennett.massstorageserver.listeners.PlayerListener;
import org.jamesphbennett.massstorageserver.listeners.PistonListener;
import org.jamesphbennett.massstorageserver.managers.*;
import org.jamesphbennett.massstorageserver.storage.StorageManager;
import org.jamesphbennett.massstorageserver.network.NetworkManager;
import org.jamesphbennett.massstorageserver.network.DisksManager;
import org.jamesphbennett.massstorageserver.network.CableManager;
import org.jamesphbennett.massstorageserver.gui.GUIManager;

import java.util.Objects;
import java.util.logging.Level;

public final class MassStorageServer extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private NetworkManager networkManager;
    private DisksManager disksManager;
    private CableManager cableManager;
    private ItemManager itemManager;
    private RecipeManager recipeManager;
    private StorageManager storageManager;
    private GUIManager guiManager;
    private ExplosionManager explosionManager;
    private ExporterManager exporterManager;
    private ImporterManager importerManager;
    private MessageManager messageManager;

    public MassStorageServer() {
    }

    @Override
    public void onEnable() {

        getLogger().info(messageManager != null ? messageManager.getConsoleMessage("console.startup.loading", "version", getDescription().getVersion()) : "Loading Mass Storage Server v" + getDescription().getVersion());

        try {
            configManager = new ConfigManager(this);
            messageManager = new MessageManager(this);
            databaseManager = new DatabaseManager(this);
            networkManager = new NetworkManager(this);
            disksManager = new DisksManager(this);
            cableManager = new CableManager(this);
            itemManager = new ItemManager(this);

            // Initialize recipe manager AFTER config and item managers are ready
            recipeManager = new RecipeManager(this);

            storageManager = new StorageManager(this);
            guiManager = new GUIManager(this);
            explosionManager = new ExplosionManager(this);
            exporterManager = new ExporterManager(this);
            importerManager = new ImporterManager(this);

            // Start periodic GUI validation task (every 30 seconds)
            getServer().getScheduler().runTaskTimer(this, () -> {
                if (guiManager != null) {
                    guiManager.validateOpenGUIs();
                }
            }, 600L, 600L);

            getServer().getPluginManager().registerEvents(new BlockListener(this), this);
            getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
            getServer().getPluginManager().registerEvents(new PistonListener(this), this);

            Objects.requireNonNull(getCommand("mss")).setExecutor(new MSSCommand(this));

            // Register recipes AFTER everything else is initialized
            recipeManager.registerRecipes();

            getLogger().info(messageManager.getConsoleMessage("console.startup.enabled"));
            getLogger().info(messageManager.getConsoleMessage("console.recipes.registered", "count", recipeManager.getRegisteredRecipeCount()));

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable Mass Storage Server Plugin!", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info(messageManager != null ? messageManager.getConsoleMessage("console.shutdown.disabling") : "Shutting down Mass Storage Server Plugin...");

        if (guiManager != null) {
            guiManager.closeAllGUIs();
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info(messageManager != null ? messageManager.getConsoleMessage("console.startup.disabled") : "Mass Storage Server Plugin disabled successfully!");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public NetworkManager getNetworkManager() {
        return networkManager;
    }

    public DisksManager getDisksManager() {
        return disksManager;
    }

    public CableManager getCableManager() {
        return cableManager;
    }

    public ItemManager getItemManager() {
        return itemManager;
    }

    public RecipeManager getRecipeManager() {
        return recipeManager;
    }


    public StorageManager getStorageManager() {
        return storageManager;
    }

    public GUIManager getGUIManager() {
        return guiManager;
    }

    public ExplosionManager getExplosionManager() {
        return explosionManager;
    }

    public ExporterManager getExporterManager() {
        return exporterManager;
    }

    public ImporterManager getImporterManager() {
        return importerManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    /**
     * Log debug message only if debug mode is enabled
     */
    public void debugLog(String message) {
        if (configManager != null && configManager.isDebugMode()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    /**
     * Get debug message with placeholders (for message system)
     */
    public void debugLog(String messageKey, Object... placeholders) {
        if (configManager != null && configManager.isDebugMode() && messageManager != null) {
            String message = messageManager.getDebugMessage(messageKey, placeholders);
            getLogger().info("[DEBUG] " + message);
        }
    }
}