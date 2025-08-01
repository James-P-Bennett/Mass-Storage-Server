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
import org.jamesphbennett.massstorageserver.gui.GUIManager;

import java.util.Objects;
import java.util.logging.Level;

public final class MassStorageServer extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private NetworkManager networkManager;
    private DisksManager disksManager;
    private ItemManager itemManager;
    private RecipeManager recipeManager;
    private CooldownManager cooldownManager;
    private StorageManager storageManager;
    private GUIManager guiManager;
    private ExplosionManager explosionManager;

    public MassStorageServer() {
        // Constructor
    }

    @Override
    public void onEnable() {

        getLogger().info("Starting Mass Storage Server Plugin...");

        // Initialize managers in order
        try {
            // Load configuration first
            configManager = new ConfigManager(this);

            // Initialize database
            databaseManager = new DatabaseManager(this);

            // Initialize other managers
            networkManager = new NetworkManager(this);
            disksManager = new DisksManager(this);
            itemManager = new ItemManager(this);

            // Initialize recipe manager AFTER config and item managers are ready
            recipeManager = new RecipeManager(this);

            cooldownManager = new CooldownManager(configManager.getOperationCooldown());
            storageManager = new StorageManager(this);
            guiManager = new GUIManager(this);
            explosionManager = new ExplosionManager(this);

            // Start periodic GUI validation task (every 30 seconds)
            getServer().getScheduler().runTaskTimer(this, () -> {
                if (guiManager != null) {
                    guiManager.validateOpenGUIs();
                }
            }, 600L, 600L); // 30 seconds in ticks (20 ticks = 1 second)

            // Register listeners
            getServer().getPluginManager().registerEvents(new BlockListener(this), this);
            getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
            getServer().getPluginManager().registerEvents(new PistonListener(this), this);

            // Register commands
            Objects.requireNonNull(getCommand("mss")).setExecutor(new MSSCommand(this));

            // Register recipes AFTER everything else is initialized
            recipeManager.registerRecipes();

            getLogger().info("Mass Storage Server Plugin enabled successfully!");
            getLogger().info("Registered " + recipeManager.getRegisteredRecipeCount() + " recipes");

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to enable Mass Storage Server Plugin!", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("Shutting down Mass Storage Server Plugin...");

        if (guiManager != null) {
            guiManager.closeAllGUIs();
        }

        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("Mass Storage Server Plugin disabled successfully!");
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

    public ItemManager getItemManager() {
        return itemManager;
    }

    public RecipeManager getRecipeManager() {
        return recipeManager;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
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
}