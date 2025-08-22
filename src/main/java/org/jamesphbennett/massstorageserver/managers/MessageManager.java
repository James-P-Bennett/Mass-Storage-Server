package org.jamesphbennett.massstorageserver.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jamesphbennett.massstorageserver.MassStorageServer;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageManager {

    private final MassStorageServer plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, YamlConfiguration> loadedLanguages = new ConcurrentHashMap<>();
    private final Pattern placeholderPattern = Pattern.compile("\\{([^}]+)\\}");
    
    // Default language fallback
    private static final String DEFAULT_LANGUAGE = "en_US";
    
    public MessageManager(MassStorageServer plugin) {
        this.plugin = plugin;
        loadLanguages();
    }
    
    /**
     * Load all available language files from resources and custom folder
     */
    private void loadLanguages() {
        // Always load default English from resources
        loadLanguageFromResources(DEFAULT_LANGUAGE);
        
        // Create lang directory if it doesn't exist and copy default files
        File langDir = new File(plugin.getDataFolder(), "lang");
        if (!langDir.exists()) {
            langDir.mkdirs();
            copyDefaultLanguageFiles(langDir);
        }
        
        // Load custom language files from plugins/MassStorageServer/lang/
        if (langDir.exists() && langDir.isDirectory()) {
            File[] langFiles = langDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (langFiles != null) {
                for (File langFile : langFiles) {
                    String langCode = langFile.getName().replace(".yml", "");
                    loadLanguageFromFile(langCode, langFile);
                }
            }
        }
        
        plugin.getLogger().info("Loaded " + loadedLanguages.size() + " language files");
    }
    
    /**
     * Copy default language files to the plugin data folder
     */
    private void copyDefaultLanguageFiles(File langDir) {
        try {
            // Copy the default English language file
            File defaultFile = new File(langDir, DEFAULT_LANGUAGE + ".yml");
            if (!defaultFile.exists()) {
                try (InputStream stream = plugin.getResource("lang/" + DEFAULT_LANGUAGE + ".yml")) {
                    if (stream != null) {
                        java.nio.file.Files.copy(stream, defaultFile.toPath());
                        plugin.getLogger().info("Copied default language file: " + DEFAULT_LANGUAGE + ".yml");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to copy default language files: " + e.getMessage());
        }
    }

    /**
     * Load language file from plugin resources
     */
    private void loadLanguageFromResources(String langCode) {
        try (InputStream stream = plugin.getResource("lang/" + langCode + ".yml")) {
            if (stream != null) {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                loadedLanguages.put(langCode, config);
                plugin.getLogger().info("Loaded language: " + langCode + " from resources");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load language " + langCode + " from resources: " + e.getMessage());
        }
    }
    
    /**
     * Load language file from custom file
     */
    private void loadLanguageFromFile(String langCode, File file) {
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            loadedLanguages.put(langCode, config);
            plugin.getLogger().info("Loaded language: " + langCode + " from " + file.getName());
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load language file " + file.getName() + ": " + e.getMessage());
        }
    }
    
    /**
     * Get message for a specific player (auto-detects their locale)
     */
    public String getMessage(Player player, String key, Object... placeholders) {
        String locale = getPlayerLocale(player);
        return getMessage(locale, key, placeholders);
    }
    
    /**
     * Get message component for a specific player (auto-detects their locale)
     */
    public Component getMessageComponent(Player player, String key, Object... placeholders) {
        String message = getMessage(player, key, placeholders);
        return miniMessage.deserialize(message);
    }
    
    /**
     * Get message for console/server (always uses default language)
     */
    public String getConsoleMessage(String key, Object... placeholders) {
        return getMessage(DEFAULT_LANGUAGE, key, placeholders);
    }
    
    /**
     * Get message for debug logging (always uses default language)
     */
    public String getDebugMessage(String key, Object... placeholders) {
        return getMessage(DEFAULT_LANGUAGE, key, placeholders);
    }
    
    /**
     * Get message for specific language
     */
    public String getMessage(String langCode, String key, Object... placeholders) {
        YamlConfiguration config = loadedLanguages.get(langCode);
        
        // Fall back to default language if requested language not available
        if (config == null) {
            config = loadedLanguages.get(DEFAULT_LANGUAGE);
        }
        
        // Final fallback if even default language fails
        if (config == null) {
            return "Missing message: " + key;
        }
        
        String message = config.getString(key);
        if (message == null) {
            // Try to get from default language as fallback
            YamlConfiguration defaultConfig = loadedLanguages.get(DEFAULT_LANGUAGE);
            if (defaultConfig != null) {
                message = defaultConfig.getString(key);
            }
            
            if (message == null) {
                return "Missing message: " + key;
            }
        }
        
        // Apply placeholders
        return applyPlaceholders(message, placeholders);
    }
    
    /**
     * Apply placeholders to a message
     * Supports both {key} format and positional {0}, {1}, etc.
     */
    private String applyPlaceholders(String message, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return message;
        }
        
        // If placeholders are provided as key-value pairs (even length)
        if (placeholders.length % 2 == 0) {
            Map<String, Object> placeholderMap = new HashMap<>();
            for (int i = 0; i < placeholders.length; i += 2) {
                placeholderMap.put(placeholders[i].toString(), placeholders[i + 1]);
            }
            
            Matcher matcher = placeholderPattern.matcher(message);
            StringBuffer result = new StringBuffer();
            
            while (matcher.find()) {
                String key = matcher.group(1);
                Object value = placeholderMap.get(key);
                if (value != null) {
                    matcher.appendReplacement(result, Matcher.quoteReplacement(value.toString()));
                }
            }
            matcher.appendTail(result);
            return result.toString();
        } else {
            // Positional placeholders {0}, {1}, etc.
            String result = message;
            for (int i = 0; i < placeholders.length; i++) {
                result = result.replace("{" + i + "}", placeholders[i].toString());
            }
            return result;
        }
    }
    
    /**
     * Get player's locale, with fallback to default
     */
    private String getPlayerLocale(Player player) {
        try {
            // Get player's client locale (e.g., "en_US", "es_ES", "fr_FR")
            Locale locale = player.locale();
            String langCode = locale.toString();
            
            // Check if we have this exact locale
            if (loadedLanguages.containsKey(langCode)) {
                return langCode;
            }
            
            // Try just the language part (e.g., "en" from "en_US")
            String languageOnly = locale.getLanguage();
            for (String availableLang : loadedLanguages.keySet()) {
                if (availableLang.startsWith(languageOnly + "_")) {
                    return availableLang;
                }
            }
            
            // Fall back to default
            return DEFAULT_LANGUAGE;
        } catch (Exception e) {
            return DEFAULT_LANGUAGE;
        }
    }
    
    /**
     * Reload all language files
     */
    public void reloadLanguages() {
        loadedLanguages.clear();
        loadLanguages();
    }
    
    /**
     * Get all loaded languages
     */
    public Map<String, YamlConfiguration> getLoadedLanguages() {
        return new HashMap<>(loadedLanguages);
    }
}