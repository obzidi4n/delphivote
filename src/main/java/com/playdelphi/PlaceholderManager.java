package com.playdelphi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class PlaceholderManager extends PlaceholderExpansion {

    private final DelphiVote plugin;
    private final DatabaseManager db;

    // Server-level cache
    private volatile int cachedServerVotes = 0;
    private volatile List<Map.Entry<String, Integer>> cachedTopVoters = Collections.emptyList();
    private volatile long serverCacheExpiry = 0;
    private final AtomicBoolean serverRefreshing = new AtomicBoolean(false);

    // Player-level cache
    private final ConcurrentHashMap<UUID, PlayerCache> playerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap.KeySetView<UUID, Boolean> refreshingPlayers = ConcurrentHashMap.newKeySet();

    private static final long SERVER_CACHE_TTL = 60_000L;
    private static final long PLAYER_CACHE_TTL = 30_000L;
    private static final int TOP_VOTER_LIMIT = 10;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static class PlayerCache {
        final int votes;
        final int rank;
        final String lastVote;
        final boolean votedToday;
        final long expiry;

        PlayerCache(int votes, int rank, String lastVote, boolean votedToday) {
            this.votes = votes;
            this.rank = rank;
            this.lastVote = lastVote;
            this.votedToday = votedToday;
            this.expiry = System.currentTimeMillis() + PLAYER_CACHE_TTL;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiry;
        }
    }

    public PlaceholderManager(DelphiVote plugin) {
        this.plugin = plugin;
        this.db = plugin.getDatabaseManager();
    }

    @Override
    public String getIdentifier() {
        return "delphivote";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        // Server-scoped placeholders (no player required)
        if (identifier.equals("server_votes")) {
            refreshServerCacheIfNeeded();
            return String.valueOf(cachedServerVotes);
        }

        if (identifier.startsWith("top_voter_")) {
            refreshServerCacheIfNeeded();
            int n = parseRankSuffix(identifier, "top_voter_".length());
            List<Map.Entry<String, Integer>> top = cachedTopVoters;
            return (n >= 1 && n <= top.size()) ? top.get(n - 1).getKey() : "";
        }

        if (identifier.startsWith("top_votes_")) {
            refreshServerCacheIfNeeded();
            int n = parseRankSuffix(identifier, "top_votes_".length());
            List<Map.Entry<String, Integer>> top = cachedTopVoters;
            return (n >= 1 && n <= top.size()) ? String.valueOf(top.get(n - 1).getValue()) : "";
        }

        // Player-scoped placeholders
        if (player == null) return "";

        PlayerCache cache = getPlayerCache(player);

        switch (identifier) {
            case "votes":       return String.valueOf(cache.votes);
            case "rank":        return cache.rank > 0 ? String.valueOf(cache.rank) : "";
            case "last_vote":   return cache.lastVote;
            case "voted_today": return cache.votedToday ? "yes" : "no";
            default:            return null;
        }
    }

    private int parseRankSuffix(String identifier, int prefixLength) {
        try {
            return Integer.parseInt(identifier.substring(prefixLength));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void refreshServerCacheIfNeeded() {
        if (System.currentTimeMillis() <= serverCacheExpiry) return;
        if (!serverRefreshing.compareAndSet(false, true)) return;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                cachedServerVotes = db.getServerVoteCount();
                cachedTopVoters = db.getTopVoters(TOP_VOTER_LIMIT);
                serverCacheExpiry = System.currentTimeMillis() + SERVER_CACHE_TTL;
            } finally {
                serverRefreshing.set(false);
            }
        });
    }

    private PlayerCache getPlayerCache(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerCache cache = playerCache.get(uuid);
        if (cache != null && !cache.isExpired()) {
            return cache;
        }
        if (refreshingPlayers.add(uuid)) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    playerCache.put(uuid, loadPlayerCache(player));
                } finally {
                    refreshingPlayers.remove(uuid);
                }
            });
        }
        return cache != null ? cache : new PlayerCache(0, 0, "", false);
    }

    private PlayerCache loadPlayerCache(Player player) {
        PlayerEnv env = new PlayerEnv(player);
        Map<String, Object> stats = db.getPlayerVoteStats(env);

        int votes = 0;
        String lastVote = "";
        if (stats != null) {
            votes = (int) stats.get("totalVotes");
            Timestamp ts = (Timestamp) stats.get("lastVoteDate");
            if (ts != null) {
                lastVote = ts.toLocalDateTime().format(DATE_FMT);
            }
        }

        int rank = votes > 0 ? db.getPlayerRank(env) : 0;
        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - 86_400_000L);
        boolean votedToday = db.hasVotedSince(env, cutoff);

        return new PlayerCache(votes, rank, lastVote, votedToday);
    }
}
