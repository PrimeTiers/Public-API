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

    public record Error(String error) {
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

    /** Null-safe, null-free copy of a list from a response. */
    public static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : list.stream().filter(java.util.Objects::nonNull).toList();
    }
}
