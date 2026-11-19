package net.primetiers.api.model;

import java.util.List;
import java.util.Objects;

/**
 * One row of the global leaderboard, which ranks players by total points across all
 * gamemodes.
 *
 * @param rank     1-based position
 * @param username player username
 * @param region   region code; may be null
 * @param points   total points
 * @param tiers      the tiers that make up the points
 * @param platform   e.g. {@code "premium"}; may be null
 * @param combatRank combat rank title from the points, e.g. {@code "Combat Ace"}; may be null
 */
public record GlobalEntry(int rank, String username, String region, int points, List<GamemodeTier> tiers,
        String platform, String combatRank) {

    public GlobalEntry {
        tiers = tiers == null ? List.of() : tiers.stream().filter(Objects::nonNull).toList();
    }

    /** An entry without platform or combat rank. */
    public GlobalEntry(int rank, String username, String region, int points, List<GamemodeTier> tiers) {
        this(rank, username, region, points, tiers, null, null);
    }

    /** {@code true} if the player is marked premium (a paid Java Edition account). */
    public boolean isPremium() {
        return "premium".equalsIgnoreCase(platform);
    }
}
