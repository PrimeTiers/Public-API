package net.primetiers.api.model;

import java.time.Instant;

/**
 * A recently updated tier in one gamemode.
 *
 * @param username  player username
 * @param tier      the tier
 * @param updatedAt when the tier was set; may be null
 */
public record GamemodeTierEntry(String username, Tier tier, Instant updatedAt) {
}
