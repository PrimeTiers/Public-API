package net.primetiers.api.model;

import java.time.Instant;

/**
 * A completed tier test.
 *
 * @param username     tested player; may be null if the player record was removed
 * @param gamemode     gamemode slug
 * @param tier         tier awarded
 * @param previousTier tier before the test; null for a player's first test
 * @param region       region the test was in; may be null
 * @param createdAt    when the test was logged
 */
public record RecentTest(
        String username,
        String gamemode,
        Tier tier,
        Tier previousTier,
        String region,
        Instant createdAt) {
}
