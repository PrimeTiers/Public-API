package net.primetiers.api.model;

/**
 * One row of a gamemode leaderboard.
 *
 * @param rank     1-based position. Boards are sorted by tier (HT1 first), then username.
 * @param username player username
 * @param region   region code; may be null
 * @param tier     the player's tier in this gamemode
 */
public record LeaderboardEntry(int rank, String username, String region, Tier tier) {
}
