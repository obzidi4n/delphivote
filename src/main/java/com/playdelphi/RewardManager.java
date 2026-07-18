package com.playdelphi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

public class RewardManager {
    private final DelphiVote plugin;
    private Logger logger;
    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private final LanguageManager languageManager;
    private Object headDatabaseAPI;
    private final PlayerEnvManager playerEnvManager;
    private final Random random;

    public RewardManager(DelphiVote plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.configManager = plugin.getConfigManager();
        this.databaseManager = plugin.getDatabaseManager();
        this.headDatabaseAPI = plugin.getHeadDatabaseAPI();
        this.languageManager = plugin.getLanguageManager();
        this.playerEnvManager = plugin.getPlayerEnvManager();
        this.random = new Random();
    }

    // Handle triggered rewards
    public void handleTriggers(PlayerEnv playerEnv, PlayerEnv tgt_playerEnv, String serviceName) {

        // get vote counts
        int playerVoteCount = databaseManager.getPlayerVoteCount(tgt_playerEnv);
        int serverVoteCount = databaseManager.getServerVoteCount();

        // load triggers
        ConfigurationSection triggerTable = configManager.getTriggerTable();

        // evaluate and execute triggers
        for (String triggerKey : triggerTable.getKeys(false)) {
            ConfigurationSection trigger = triggerTable.getConfigurationSection(triggerKey);
            // logger.info("evaluating trigger " + triggerKey);

            if (evaluateTrigger(trigger, playerVoteCount, serverVoteCount)) {
                // logger.info("executing trigger " + triggerKey);
                executeTrigger(playerEnv, tgt_playerEnv, trigger, playerVoteCount, serviceName);
            }
        }
    }

    private boolean evaluateTrigger(ConfigurationSection trigger, int playerVoteCount, int serverVoteCount) {
        String triggerName = trigger.getString("trigger_name");
        String triggerUser = trigger.getString("trigger_user");
        int triggerThreshold = trigger.getInt("trigger_threshold", 0);
        boolean triggerRepeat = trigger.getBoolean("trigger_repeat", true);

        // always fire if threshold is 0
        if (triggerThreshold == 0) {
            return true; 
        }

        // fire if threshold / repeat is met
        if (triggerUser.equals("player")) {
            if (triggerRepeat) {
                return playerVoteCount % triggerThreshold == 0;
            } else {
                return playerVoteCount == triggerThreshold;
            }
        } else if (triggerUser.equals("server")) {
            if (triggerRepeat) {
                return serverVoteCount % triggerThreshold == 0;
            } else {
                return serverVoteCount == triggerThreshold;
            }
        }

        return false;
    }

    private void executeTrigger(PlayerEnv playerEnv, PlayerEnv tgt_playerEnv, ConfigurationSection trigger, int voteCount, String serviceName) {
        String triggerUser = trigger.getString("trigger_user");
        List<String> triggerRewards = trigger.getStringList("trigger_rewards");
        String playerMessage = trigger.getString("trigger_player_message");
        String broadcastMessage = trigger.getString("trigger_broadcast_message");

        if (triggerUser.equals("player")) {

            // give rewards for this trigger
            for (String rewardName : triggerRewards) {
                ConfigurationSection reward = configManager.getRewardItemTable().getConfigurationSection(rewardName);
                if (reward != null) {
                    this.giveReward(playerEnv, tgt_playerEnv, reward, serviceName);
                }
            }
            
            // send broadcastmessage for this trigger
            if (!serviceName.equals("Admin") && broadcastMessage != null) {
                String formattedBroadcast = formatTriggerMessage(broadcastMessage, tgt_playerEnv.name, voteCount, serviceName);
                if (formattedBroadcast != null) {
                    plugin.getServer().broadcastMessage(formattedBroadcast);
                }
            }

            // send player message for this trigger, if player is online
            if (tgt_playerEnv.player != null && playerMessage != null) {
                String formattedMessage = formatTriggerMessage(playerMessage, tgt_playerEnv.name, voteCount, serviceName);
                if (formattedMessage != null) {
                    tgt_playerEnv.player.sendMessage(formattedMessage);
                }
            }

        } else if (triggerUser.equals("server")) {

            // get all player uuids
            List<UUID> allPlayers = databaseManager.getAllPlayersUUID();

            // loop through all players and give rewards
            for (UUID uuid : allPlayers) {

                // get or create PlayerEnv
                PlayerEnv svr_playerEnv = playerEnvManager.getPlayerEnv(uuid);

                // give rewards for this trigger
                for (String rewardName : triggerRewards) {
                    ConfigurationSection reward = configManager.getRewardItemTable().getConfigurationSection(rewardName);
                    if (reward != null) {
                        this.giveReward(playerEnv, svr_playerEnv, reward, serviceName);
                    }
                }

                // send player message for this trigger, if player is online
                if (svr_playerEnv.player != null && playerMessage != null) {
                    String formattedMessage = formatTriggerMessage(playerMessage, svr_playerEnv.name, voteCount, serviceName);
                    if (formattedMessage != null) {
                        svr_playerEnv.player.sendMessage(formattedMessage);
                    }
                }

                // close PlayerEnv if player is offline
                if (svr_playerEnv.player == null) {
                    playerEnvManager.removePlayerEnv(svr_playerEnv.uuid);
                }
            }

            // send broadcastmessage for this trigger
            if (broadcastMessage != null) {
                String formattedBroadcast = formatTriggerMessage(broadcastMessage, playerEnv.name, voteCount, serviceName);
                if (formattedBroadcast != null) {
                    plugin.getServer().broadcastMessage(formattedBroadcast);
                }
            }
        }
    }

    // Handle manual rewards
    public void handleRewards(PlayerEnv playerEnv, PlayerEnv tgt_playerEnv, String serviceName, String rewardName) {

        // load rewards
        ConfigurationSection rewardTable = configManager.getRewardItemTable();

        // evaluate and give rewards
        for (String rewardKey : rewardTable.getKeys(false)) {
            ConfigurationSection reward = configManager.getRewardItemTable().getConfigurationSection(rewardKey);

            // logger.info("looking for " + rewardName + " in " + rewardKey);
           
            if (rewardName.equals(rewardKey)) {
                this.giveReward(playerEnv, tgt_playerEnv, reward, serviceName);

                // send player messages
                playerEnv.sendMessage(languageManager.getMessage("give_reward_success", 
                Map.of("player", tgt_playerEnv.name,
                    "reward", reward.getString("reward_name"))));

                if (tgt_playerEnv.player != null) {
                    tgt_playerEnv.sendMessage(languageManager.getMessage("give_reward_success_player",
                        Map.of("reward", reward.getString("reward_name"))));
                }
            } 
        }  
    }

    // Give reward items to player
    public void giveReward(PlayerEnv playerEnv, PlayerEnv tgt_playerEnv, ConfigurationSection reward, String serviceName) {

        // store offline rewards
        if (tgt_playerEnv.player == null) {
            databaseManager.addOfflineReward(tgt_playerEnv, reward.getName(), serviceName);
            return;
        }

        // give online rewards
        List<Map<?, ?>> rewardItems = reward.getMapList("reward_items");
        boolean isRandomized = reward.getBoolean("reward_randomized");
        int maxItems = reward.getInt("reward_max_items");

        // create Itemstack array of items
        List<ItemStack> items = new ArrayList<>();
        Map<ItemStack, List<String>> commands = new HashMap<>();
        
        for (Map<?, ?> rewardItem : rewardItems) {
            String itemTitle = (String) rewardItem.get("title");
            // logger.info("itemTitle: " + itemTitle);
            String itemName = (String) rewardItem.get("item");
            // logger.info("itemName: " + itemName);
            int itemCount = (int) rewardItem.get("quantity");
            // logger.info("itemCount: " + itemCount);
            List<String> itemLoreList = (List<String>) rewardItem.get("lore");
            // logger.info("itemLoreList: " + itemLoreList);
            List<String> itemCommandList = (List<String>) rewardItem.get("commands");
            // logger.info("itemCommandList: " + itemCommandList);
            ItemStack item;

            // add item block
            if (itemName.startsWith("hdb-") && headDatabaseAPI != null) {
                String headID = itemName.split("-")[1];
                item = getHeadItem(headID);
                if (item == null) {
                    logger.warning("Head with ID " + headID + " could not be found!");
                    continue;
                }
                item.setAmount(itemCount);
            } else {
                item = new ItemStack(Material.valueOf(itemName.toUpperCase()), itemCount);
                // logger.info("added itemName: " + itemName);
            }

            // add item meta
            ItemMeta meta = item.getItemMeta();
            // logger.info("meta: " + meta);
            
            if (meta != null) {
                if (itemTitle != null) {
                    meta.setDisplayName(itemTitle);
                }
                if (itemLoreList != null) {
                    meta.setLore(itemLoreList);
                }
                item.setItemMeta(meta);
            }
            // logger.info("added item meta");

            // add item commands
            if (itemCommandList != null && !itemCommandList.isEmpty()) {
                commands.put(item, itemCommandList);
                // logger.info("added itemcommandList " + itemCommandList);
            }
            
            // add finished item to array
            items.add(item);
            // logger.info("added item to array " + item);
        }

        // randomize Itemstack array and trim to max items allowed
        if (isRandomized && maxItems > 0 && maxItems < items.size()) {
            while (items.size() > maxItems) {
                ItemStack removedItem = items.remove(random.nextInt(items.size()));
                commands.remove(removedItem);
                // logger.info("removed item from array " + removedItem);
            }
        }
        // logger.info("completed itemstack");

        // add to player inventory and execute commands
        for (ItemStack item : items) {
            tgt_playerEnv.player.getInventory().addItem(item);
            // logger.info("added item to player inventory " + item);

            if (commands.containsKey(item)) {
                for (String command : commands.get(item)) {
                        executeConsoleCommand(tgt_playerEnv, command);
                        // logger.info("executed command " + command);
                }
            }
        }
    }   

    // Process pending offline rewards
    public void processPendingOfflineRewards(PlayerEnv tgt_playerEnv) {

        List<Map<String, Object>> pendingRewards = databaseManager.getOfflineRewards(tgt_playerEnv);
        
        for (Map<String, Object> rewardData : pendingRewards) {
            String rewardId = (String) rewardData.get("reward_id");
            String serviceName = (String) rewardData.get("vote_service");
            ConfigurationSection reward = configManager.getRewardItemTable().getConfigurationSection(rewardId);
            
            if (reward != null) {
                giveReward(tgt_playerEnv, tgt_playerEnv, reward, serviceName);
                databaseManager.removeOfflineReward(tgt_playerEnv, rewardId);
                
                String message = languageManager.getMessage("give_reward_success_player",
                    Map.of("reward", reward.getString("reward_name")));
                tgt_playerEnv.player.sendMessage(message);
            }
        }
    }

    // Format trigger messages
    private String formatTriggerMessage(String message, String playerName, int voteCount, String serviceName) {
        return languageManager.getMessage(message, 
            Map.of("player", playerName,
                   "votes", String.valueOf(voteCount),
                   "service", serviceName));
    }

    // List triggers
    public void listTriggers(PlayerEnv playerEnv) {
        ConfigurationSection triggerTable = configManager.getTriggerTable();

        if (triggerTable == null && playerEnv.player != null) {
            playerEnv.sendMessage(languageManager.getMessage("trigger_list_error"));
            return;
        }

        if (playerEnv.player != null) {
            playerEnv.sendMessage(languageManager.getMessage("trigger_list_header"));
        }

        for (String triggerKey : triggerTable.getKeys(false)) {
            ConfigurationSection trigger = triggerTable.getConfigurationSection(triggerKey);
            if (trigger != null && playerEnv.player != null) {
                playerEnv.sendMessage(languageManager.getMessage("trigger_list_item", 
                    Map.of("key", triggerKey)));
            }
        }
    }

    // List rewards
    public void listRewards(PlayerEnv playerEnv) {
        ConfigurationSection rewardTable = configManager.getRewardItemTable();

        if (rewardTable == null && playerEnv.player != null) {
            playerEnv.sendMessage(languageManager.getMessage("reward_list_error"));
            return;
        }

        if (playerEnv.player != null) {
            playerEnv.sendMessage(languageManager.getMessage("reward_list_header"));
        }

        for (String rewardKey : rewardTable.getKeys(false)) {
            ConfigurationSection reward = rewardTable.getConfigurationSection(rewardKey);
            if (reward != null && playerEnv.player != null) {
                playerEnv.sendMessage(languageManager.getMessage("reward_list_item", 
                    Map.of("key", rewardKey)));
            }
        }
    }

    // Expire rewards
    public void expireRewards() {
        YamlConfiguration mainConfig = configManager.getConfig("config");
        long rewardsExpire = (long)mainConfig.getInt("rewards_expire");
        long cutoffTime = System.currentTimeMillis() - (rewardsExpire * 24 * 60 * 60 * 1000); // in ms
        databaseManager.expireRewards(cutoffTime);
    }

    // Execute console command
    private void executeConsoleCommand(PlayerEnv tgt_playerEnv, String itemCommand) {
        String command = itemCommand.replace("{player}", tgt_playerEnv.name);
        ConsoleCommandSender console = Bukkit.getServer().getConsoleSender();
        Bukkit.dispatchCommand(console, command);
    }

    private ItemStack getHeadItem(String headId) {
        try {
            if (headDatabaseAPI != null) {
                Class<?> apiClass = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
                return (ItemStack) apiClass.getMethod("getItemHead", String.class)
                    .invoke(headDatabaseAPI, headId);
            }
            // Return fallback item if HeadDatabase isn't available
            return new ItemStack(Material.PLAYER_HEAD);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get head item: " + e.getMessage());
            return new ItemStack(Material.PLAYER_HEAD);
        }
    }
}