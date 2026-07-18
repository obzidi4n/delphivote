package com.playdelphi;

import java.io.File;
import java.util.HashMap;
import java.util.logging.Logger;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

public class LanguageManager {
    private final DelphiVote plugin;
	private File datafolder;
	private Logger logger;
    private YamlConfiguration langConfig;
    private ConfigManager configManager;
    private final Map<String, String> messages = new HashMap<>();

    public LanguageManager(DelphiVote plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.datafolder = plugin.getDataFolder();
        this.configManager = plugin.getConfigManager();
        loadLanguageConfigs();
    }

    public void loadLanguageConfigs() {
        File langFolder = new File(datafolder, "lang");
        
        // Get preferred language from config, default to English if not set
        String langPref = configManager.getConfig("config").getString("language", "messages-en.yml");

        langConfig = configManager.getConfig("lang_" + langPref);

        for (String key : langConfig.getKeys(false)) {
            String message = langConfig.getString(key);
            if (message != null) {
                // Store raw message without processing color codes
                messages.put(key, message);
            }
        }
    }

    private void createLanguageFile(String fileName) {
        File file = new File(datafolder + File.separator + fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
            // logger.info("Created language file: " + fileName);
        }
    }

    // lookup message by key, process color codes
    public String getMessage(String key) {
        if (key == null) {
            return null;
        }
        String message = messages.getOrDefault(key, "Message not found: " + key);
        // Process color codes after getting the message
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // override: lookup message by key, insert DelphiVote placeholders, process color codes
    public String getMessage(String key, Map<String, String> placeholders) {
        if (key == null) {
            return null;
        }
        String message = messages.getOrDefault(key, key);

        // process DelphiVote placeholders
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        // process color codes
        message = ChatColor.translateAlternateColorCodes('&', message);

        return message;
    }
}