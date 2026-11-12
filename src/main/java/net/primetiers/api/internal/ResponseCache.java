package net.primetiers.api.internal;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Caches the futures of in-flight and finished requests by key.
 *
 * <ul>
 *   <li>Requests for a key that is already loading share the same future, so a burst of
 *       identical lookups makes one HTTP call.</li>
 *   <li>Successful results are kept for the TTL, counted from when they finish.</li>
 *   <li>Failed requests are removed at once and never cached.</li>
 *   <li>A zero TTL turns caching off (in-flight requests are still shared).</li>
 * </ul>
 *
 * Not part of the public API.
 */
public final class ResponseCache {

    private static final int MAX_ENTRIES = 10_000;

    private static final class Entry {
        final CompletableFuture<?> future;
        /** {@link Long#MAX_VALUE} while loading. */
        volatile long expiresAt = Long.MAX_VALUE;

        Entry(CompletableFuture<?> future) {
            this.future = future;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final LongSupplier clock;

    public ResponseCache(Duration ttl) {
        this(ttl, System::nanoTime);
    }

    public ResponseCache(Duration ttl, LongSupplier nanoClock) {
        this.ttlNanos = ttl.toNanos();
        this.clock = nanoClock;
    }

    /** Returns the live future for {@code key}, or null if there is none. */
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> peek(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return null;
        }
        if (e.expiresAt != Long.MAX_VALUE && clock.getAsLong() - e.expiresAt >= 0) {
            entries.remove(key, e);
            return null;
        }
        return (CompletableFuture<T>) e.future;
    }

    /**
     * Stores {@code future} under {@code key} unless a live entry exists.
     *
     * @return the existing live future, or null if {@code future} was stored
     */
    public <T> CompletableFuture<T> putIfAbsent(String key, CompletableFuture<T> future) {
        while (true) {
            CompletableFuture<T> existing = peek(key);
            if (existing != null) {
                return existing;
            }
            if (entries.size() >= MAX_ENTRIES) {
                evict();
            }
            Entry entry = new Entry(future);
            if (entries.putIfAbsent(key, entry) == null) {
                future.whenComplete((value, error) -> {
                    if (error != null || ttlNanos <= 0) {
                        entries.remove(key, entry);
                    } else {
                        entry.expiresAt = clock.getAsLong() + ttlNanos;
                    }
                });
                return null;
            }
            // Another thread stored an entry first; loop and return it.
        }
    }

    /** Returns the cached future for {@code key}, starting {@code loader} if there is none. */
    public <T> CompletableFuture<T> get(String key, Supplier<CompletableFuture<T>> loader) {
        CompletableFuture<T> mine = new CompletableFuture<>();
        CompletableFuture<T> existing = putIfAbsent(key, mine);
        if (existing != null) {
            return existing;
        }
        try {
            loader.get().whenComplete((value, error) -> {
                if (error != null) {
                    mine.completeExceptionally(error);
                } else {
                    mine.complete(value);
                }
            });
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
        }
        return mine;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private void evict() {
        long now = clock.getAsLong();
        entries.values().removeIf(e -> e.expiresAt != Long.MAX_VALUE && now - e.expiresAt >= 0);
        if (entries.size() >= MAX_ENTRIES) {
            // Still full: drop finished entries rather than grow without bound.
            entries.values().removeIf(e -> e.future.isDone());
        }
    }
}
