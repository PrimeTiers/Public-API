package net.primetiers.api.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a multi-player lookup.
 *
 * @param players found players, in the order they were requested
 * @param missing requested names (lowercased) with no PrimeTiers profile
 */
public record PlayerBatch(List<Player> players, List<String> missing) {

    public PlayerBatch {
        players = players == null ? List.of() : players.stream().filter(Objects::nonNull).toList();
        missing = missing == null ? List.of() : List.copyOf(missing);
    }

    /** Finds a player in this batch by username, ignoring case. */
    public Optional<Player> get(String username) {
        return players.stream().filter(p -> p.username().equalsIgnoreCase(username)).findFirst();
    }
}
