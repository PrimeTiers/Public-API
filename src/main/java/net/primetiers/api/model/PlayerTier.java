package net.primetiers.api.model;

import java.time.Instant;
import java.util.Optional;

/**
 * A player's tier in one gamemode.
 *
 * @param gamemode  gamemode slug, e.g. {@code "mace-spear"}
 * @param tier      the tier; null only if the API sends a tier this library doesn't know
 * @param updatedAt when the tier was last set; may be null
 */
public record PlayerTier(String gamemode, Tier tier, Instant updatedAt) {

    /** The gamemode as a constant, if this library version knows it. */
    public Optional<Gamemode> knownGamemode() {
        return Gamemode.fromSlug(gamemode);
    }
}
