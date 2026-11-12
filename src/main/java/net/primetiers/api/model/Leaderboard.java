package net.primetiers.api.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Leaderboards for every gamemode.
 *
 * @param gamemodes boards keyed by gamemode slug, in the API's gamemode order
 */
public record Leaderboard(Map<String, List<LeaderboardEntry>> gamemodes) {

    public Leaderboard {
        Map<String, List<LeaderboardEntry>> copy = new LinkedHashMap<>();
        if (gamemodes != null) {
            gamemodes.forEach((k, v) -> copy.put(k, v == null ? List.of() : List.copyOf(v)));
        }
        gamemodes = Collections.unmodifiableMap(copy);
    }

    /** One gamemode's board. Empty if the gamemode has no players or isn't in this response. */
    public List<LeaderboardEntry> get(Gamemode gamemode) {
        return get(gamemode.slug());
    }

    /** One gamemode's board by slug. Empty if the gamemode isn't in this response. */
    public List<LeaderboardEntry> get(String gamemode) {
        return gamemodes.getOrDefault(gamemode, List.of());
    }
}
