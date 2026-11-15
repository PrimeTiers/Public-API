package net.primetiers.api.internal;

import java.util.List;

import net.primetiers.api.model.GamemodeTierEntry;
import net.primetiers.api.model.GlobalEntry;
import net.primetiers.api.model.Player;
import net.primetiers.api.model.RecentTest;

/** Response envelopes exactly as the API sends them. Not part of the public API. */
public final class Wire {

    private Wire() {
    }

    /** PrimeTiers sends {@code error}; Mojang sends {@code errorMessage}. */
    public record Error(String error, String errorMessage) {
    }

    public record Health(boolean ok) {
    }

    public record Gamemodes(List<String> gamemodes) {
    }

    public record PlayerBatch(List<Player> items, List<String> missing) {
    }

    public record GlobalLeaderboard(List<GlobalEntry> items) {
    }

    public record GamemodeTiers(String gamemode, List<GamemodeTierEntry> items) {
    }

    public record RecentTests(List<RecentTest> items) {
    }

    /** A Mojang profile: {@code id} is the UUID without dashes, {@code name} the current username. */
    public record MojangProfile(String id, String name) {
    }

    /** Parses a UUID with or without dashes. Returns null if malformed. */
    public static java.util.UUID uuid(String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim();
        if (s.length() == 32) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-"
                    + s.substring(16, 20) + "-" + s.substring(20);
        }
        try {
            return java.util.UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Null-safe, null-free copy of a list from a response. */
    public static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : list.stream().filter(java.util.Objects::nonNull).toList();
    }
}
