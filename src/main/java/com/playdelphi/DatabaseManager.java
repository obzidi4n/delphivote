package com.playdelphi;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.lang.String;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public class DatabaseManager {
    private final DelphiVote plugin;
    private FileConfiguration config;
    private ConfigManager configManager;
	private File datafolder;
	private Logger logger;
    private HikariDataSource dataSource;
    private LanguageManager languageManager;
    private boolean isMySQL;
    private String dbType;
    private String tablePrefix;
    private String votesTable;
    private String playersTable;
    private String offlineRewardsTable;

    
    // Constructor
    public DatabaseManager(DelphiVote plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
        this.datafolder = plugin.getDataFolder();
        this.logger = plugin.getLogger();
        this.configManager = plugin.getConfigManager();
        this.languageManager = plugin.getLanguageManager();
        this.dbType = config.getString("database.type", "sqlite");
        this.isMySQL = "mysql".equalsIgnoreCase(dbType);
        initializeDatabase();
        createTables();
    }

    // Initialize database
    private void initializeDatabase() {
        tablePrefix = config.getString("database.table_prefix");
        votesTable = tablePrefix + "_votes";
        playersTable = tablePrefix + "_players";
        offlineRewardsTable = tablePrefix + "_offline_rewards";

        // Create data subfolder if it doesn't exist
        File databaseFolder = new File(datafolder, "data");
        if (!databaseFolder.exists()) {
            databaseFolder.mkdirs(); 
        }

        HikariConfig hikariConfig = new HikariConfig();

        if (isMySQL) {
            hikariConfig.setJdbcUrl("jdbc:mysql://" + config.getString("database.host") + ":" + config.getInt("database.port") + "/" + config.getString("database.database"));
            hikariConfig.setUsername(config.getString("database.username"));
            hikariConfig.setPassword(config.getString("database.password"));
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else {
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + datafolder.getAbsolutePath() + "/data/delphivote.db");
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
        }

        hikariConfig.setMaximumPoolSize(10);

        dataSource = new HikariDataSource(hikariConfig);
    }

    // Create tables
    private void createTables() {
        // YamlConfiguration databaseConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(plugin.getResource("database.yml"), StandardCharsets.UTF_8));
        FileConfiguration databaseConfig = configManager.getResourceConfig("database.yml");
        ConfigurationSection tablesSection = databaseConfig.getConfigurationSection("tables");
        
        if (tablesSection == null) {
            logger.severe("No table definitions found in database.yml");
            return;
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            for (String tableName : tablesSection.getKeys(false)) {
                ConfigurationSection tableSection = tablesSection.getConfigurationSection(tableName);
                if (tableSection == null) continue;

                StringBuilder createTableSQL = new StringBuilder("CREATE TABLE IF NOT EXISTS " + tablePrefix + "_" + tableName + " (");
                
                for (String fieldName : tableSection.getKeys(false)) {
                    ConfigurationSection fieldSection = tableSection.getConfigurationSection(fieldName);
                    String fieldDef;
                    
                    if (fieldSection != null) {
                        // Field has separate SQLite and MySQL definitions
                        fieldDef = isMySQL ? fieldSection.getString("mysql") : fieldSection.getString("sqlite");
                    } else {
                        // Field has a single definition for both database types
                        fieldDef = tableSection.getString(fieldName);
                    }
                    
                    if (fieldDef == null) continue;
                    
                    createTableSQL.append(fieldName).append(" ").append(fieldDef).append(", ");
                }
                
                createTableSQL.setLength(createTableSQL.length() - 2);  // Remove last comma and space
                createTableSQL.append(")");
                
                stmt.execute(createTableSQL.toString());
                // logger.info("Created table: " + tablePrefix + "_" + tableName);
                
                // If mysql, create index on player_uuid for faster lookups in votes table
                if (tableName.equals("votes")) {
                    if (isMySQL) {
                        try {
                            stmt.execute("CREATE INDEX idx_player_uuid ON " + tablePrefix + "_" + tableName + " (player_uuid)");
                        } catch (SQLException e) {
                            // Ignore error if index already exists (MySQL error code 1061)
                            if (e.getErrorCode() != 1061) {
                                throw e;
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            logger.severe("Error creating tables: " + e.getMessage());
        }
    }

    // Add vote to votes table
    public void addVote(PlayerEnv playerEnv, PlayerEnv tgt_playerEnv, String serviceName) {
        String sql = "INSERT INTO " + votesTable + " (player_uuid, player_name, vote_service, vote_ts) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            pstmt.setString(2, tgt_playerEnv.name);
            pstmt.setString(3, serviceName);
            pstmt.executeUpdate();
            
        } catch (SQLException e) {
            logger.severe("Error adding vote: " + e.getMessage()); 
        }
    }

    // Get player vote count
    public int getPlayerVoteCount(PlayerEnv tgt_playerEnv) {
        String sql = "SELECT COUNT(*) FROM " + votesTable + " WHERE player_uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            
        } catch (SQLException e) {
            logger.severe("Error getting player vote count: " + e.getMessage());
        }
        return 0;
    }

    // Get total server vote count
    public int getServerVoteCount() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM " + votesTable)) {
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.severe("Failed to get total server vote count: " + e.getMessage());
        }
        return 0;
    }

    // Get player rank by all-time vote count (1 = top voter, 0 = never voted)
    public int getPlayerRank(PlayerEnv tgt_playerEnv) {
        String sql = "SELECT COUNT(*) + 1 FROM ("
                   + "SELECT player_uuid, COUNT(*) as vote_count FROM " + votesTable + " GROUP BY player_uuid"
                   + ") subq WHERE vote_count > (SELECT COUNT(*) FROM " + votesTable + " WHERE player_uuid = ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.severe("Error getting player rank: " + e.getMessage());
        }
        return 0;
    }

    // Check if player has cast any vote since the given timestamp
    public boolean hasVotedSince(PlayerEnv tgt_playerEnv, Timestamp since) {
        String sql = "SELECT COUNT(*) FROM " + votesTable + " WHERE player_uuid = ? AND vote_ts >= ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            pstmt.setTimestamp(2, since);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            logger.severe("Error checking recent vote for player: " + e.getMessage());
        }
        return false;
    }

    // Get top voters
    public List<Map.Entry<String, Integer>> getTopVoters(int limit) {
        List<Map.Entry<String, Integer>> topVoters = new ArrayList<>();
        String sql = "SELECT player_name, COUNT(*) as vote_count FROM " + votesTable + " GROUP BY player_name ORDER BY vote_count DESC LIMIT ?";

        // adjust sql to join on players to get by uuid and latest name

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setInt(1, limit);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                String player_name = rs.getString("player_name");
                int voteCount = rs.getInt("vote_count");
                topVoters.add(new AbstractMap.SimpleEntry<>(player_name, voteCount));
            }
        } catch (SQLException e) {
            logger.severe("Error fetching top voters: " + e.getMessage());
        }
        
        return topVoters;
    }

    // Get single player vote stats
    public Map<String, Object> getPlayerVoteStats(PlayerEnv tgt_playerEnv) {
        Map<String, Object> stats = new HashMap<>();
        String sql = "SELECT COUNT(*) as total_votes, MAX(vote_ts) as last_vote FROM " + votesTable + " WHERE player_uuid = ?";
        
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                stats.put("totalVotes", rs.getInt("total_votes"));
                stats.put("lastVoteDate", rs.getTimestamp("last_vote"));
            }
        } catch (SQLException e) {
            logger.severe("Error fetching player vote stats: " + e.getMessage());
        }
        
        return stats.isEmpty() ? null : stats;
    }

    // Clear old offline rewards
    public void expireRewards(long cutoffTime) {
        String sql = "DELETE FROM " + offlineRewardsTable + " WHERE reward_ts < ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setTimestamp(1, new Timestamp(cutoffTime));
            int deletedRewards = pstmt.executeUpdate();
            logger.info("Cleared " + deletedRewards + " old offline rewards.");
        } catch (SQLException e) {
            logger.severe("Error clearing old offline rewards: " + e.getMessage());
        }
    }

    // Close database connection
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    // Add or update player in players table
    public void addOrUpdatePlayer(PlayerEnv tgt_playerEnv) {
        String query;
        if (isMySQL) {
            query = "INSERT INTO " + playersTable + " (player_uuid, player_name, last_seen_ts) VALUES (?, ?, CURRENT_TIMESTAMP) "
                  + "ON DUPLICATE KEY UPDATE player_name = ?, last_seen_ts = CURRENT_TIMESTAMP";
        } else {
            query = "INSERT OR REPLACE INTO " + playersTable + " (player_uuid, player_name, last_seen_ts) VALUES (?, ?, CURRENT_TIMESTAMP)";
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            pstmt.setString(2, tgt_playerEnv.name);
            if (isMySQL) {
                pstmt.setString(3, tgt_playerEnv.name);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.severe("Error adding/updating player: " + e.getMessage());
        }
    }

    // Get player UUID from players table
    public UUID getPlayerUUID(PlayerEnv tgt_playerEnv) {
        String query = "SELECT player_uuid FROM " + playersTable + " WHERE player_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, tgt_playerEnv.name);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("player_uuid"));
                }
            }
        } catch (SQLException e) {
            logger.severe("Error getting player UUID: " + e.getMessage());
        }
        return null;
    }

    // Get all players UUID from players table
    public List<UUID> getAllPlayersUUID() {
        List<UUID> playersUUID = new ArrayList<>();
        String query = "SELECT DISTINCT player_uuid FROM " + playersTable;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    playersUUID.add(UUID.fromString(rs.getString("player_uuid")));
                }
                return playersUUID;
            }
        } catch (SQLException e) {
            logger.severe("Error getting all players UUID: " + e.getMessage());
        }
        return null;
    }

    // Get player name from players table
    public String getPlayerUsername(PlayerEnv tgt_playerEnv) {
        String query = "SELECT player_name FROM " + playersTable + " WHERE player_uuid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(query)) {
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("player_name");
                }
            }
        } catch (SQLException e) {
            logger.severe("Error getting player name: " + e.getMessage());
        }
        return null;
    }

    public void addOfflineReward(PlayerEnv tgt_playerEnv, String rewardId, String serviceName) {
        String sql = "INSERT INTO " + offlineRewardsTable + " (player_uuid, reward_id, vote_service, reward_ts) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            pstmt.setString(2, rewardId);
            pstmt.setString(3, serviceName);
            pstmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            pstmt.executeUpdate();
            // logger.info("Added offline reward for " + tgt_playerEnv.uuid + " with reward ID " + rewardId + " from service " + serviceName);
        } catch (SQLException e) {
            logger.severe("Error adding offline reward: " + e.getMessage());
        }
    }

    // Get offline rewards for player
    public List<Map<String, Object>> getOfflineRewards(PlayerEnv tgt_playerEnv) {
        List<Map<String, Object>> rewards = new ArrayList<>();
        String sql = "SELECT * FROM " + offlineRewardsTable + " WHERE player_uuid = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> reward = new HashMap<>();
                    reward.put("reward_id", rs.getString("reward_id"));
                    reward.put("vote_service", rs.getString("vote_service"));
                    reward.put("reward_ts", rs.getTimestamp("reward_ts"));
                    rewards.add(reward);
                }
            }
        } catch (SQLException e) {
            logger.severe("Error getting offline rewards: " + e.getMessage());
        }
        return rewards;
    }

    // Remove specific offline reward for player
    public void removeOfflineReward(PlayerEnv tgt_playerEnv, String rewardId) {
        String sql = "DELETE FROM " + offlineRewardsTable + " WHERE player_uuid = ? AND reward_id = ?";
        try (Connection conn = getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tgt_playerEnv.uuid.toString());
            pstmt.setString(2, rewardId);
            pstmt.executeUpdate();
            logger.info("Removed offline reward for " + tgt_playerEnv.uuid + ", reward ID " + rewardId);
        } catch (SQLException e) {
            logger.severe("Error removing offline reward: " + e.getMessage());
        }
    }

    // Get connection to database
    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}