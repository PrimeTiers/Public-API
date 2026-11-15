package net.primetiers.api.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A player and all their tiers.
 *
 * @param id       PrimeTiers player id
 * @param username username with its stored capitalization
 * @param region   region code such as {@code "NA"}, {@code "EU"} or {@code "AS"}; may be null
 * @param platform e.g. {@code "premium"}; may be null
 * @param tiers    one entry per gamemode the player has a tier in; never null
 */
public record Player(long id, String username, String region, String platform, List<PlayerTier> tiers) {

    public Player {
        tiers = tiers == null ? List.of() : tiers.stream().filter(Objects::nonNull).toList();
    }

    /**
     * {@code true} if PrimeTiers has this player marked as premium (a paid Java Edition
     * account). Only premium players get a UUID from
     * {@link net.primetiers.api.PrimeTiersClient#resolveUuid(Player)}.
     */
    public boolean isPremium() {
        return "premium".equalsIgnoreCase(platform);
    }

    /** This player's tier in one gamemode, if they have one. */
    public Optional<PlayerTier> tier(Gamemode gamemode) {
        return tier(gamemode.slug());
    }

    /** This player's tier in one gamemode (by slug, ignoring case), if they have one. */
    public Optional<PlayerTier> tier(String gamemode) {
        return tiers.stream().filter(t -> t.gamemode().equalsIgnoreCase(gamemode)).findFirst();
    }

    /** This player's best tier across all gamemodes, if they have any. */
    public Optional<PlayerTier> bestTier() {
        return tiers.stream()
                .filter(t -> t.tier() != null)
                .min(Comparator.comparing(PlayerTier::tier));
    }
}
