package net.primetiers.api.model;

/**
 * A gamemode and tier pair, as listed on the global leaderboard.
 *
 * @param gamemode gamemode slug
 * @param tier     tier in that gamemode
 */
public record GamemodeTier(String gamemode, Tier tier) {
}
