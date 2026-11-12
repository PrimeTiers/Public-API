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
 * @param tiers    the tiers that make up the points
 */
public record GlobalEntry(int rank, String username, String region, int points, List<GamemodeTier> tiers) {

    public GlobalEntry {
        tiers = tiers == null ? List.of() : tiers.stream().filter(Objects::nonNull).toList();
    }
}
