package net.primetiers.api;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import net.primetiers.api.internal.Json;
import net.primetiers.api.internal.ResponseCache;
import net.primetiers.api.internal.Wire;
import net.primetiers.api.model.Gamemode;
import net.primetiers.api.model.GamemodeTierEntry;
import net.primetiers.api.model.GlobalEntry;
import net.primetiers.api.model.Leaderboard;
import net.primetiers.api.model.LeaderboardEntry;
import net.primetiers.api.model.Player;
import net.primetiers.api.model.PlayerBatch;
import net.primetiers.api.model.RecentTest;

/**
 * Client for the public PrimeTiers API at {@value #DEFAULT_BASE_URL}.
 *
 * <pre>{@code
 * PrimeTiersClient client = PrimeTiersClient.builder()
 *         .userAgent("MyMod/1.0.0")
 *         .build();
 *
 * client.getPlayer("Burnet01").thenAccept(player -> player.ifPresent(p ->
 *         System.out.println(p.username() + " " + p.tier(Gamemode.MACE_SPEAR))));
 * }</pre>
 *
 * <h2>Threading</h2>
 * Every method returns immediately with a {@link CompletableFuture} and never blocks, so
 * it is safe to call from a game or render thread. Callbacks run on the HTTP client's
 * threads. Move back to your own thread before touching game state, e.g.
 * {@code future.thenAcceptAsync(action, minecraftClient::execute)}.
 *
 * <h2>Batching</h2>
 * Every player lookup uses the batch endpoint ({@code /api/players?names=}).
 * {@link #getPlayer(String)} calls made within {@link Builder#batchWindow(Duration)
 * batchWindow} (default 50 ms) of each other go out as one request, so looking up players
 * one at a time as they join costs few requests.
 *
 * <h2>Caching</h2>
 * Results are cached in memory for {@link Builder#cacheTtl(Duration) cacheTtl} (default 1
 * minute, which is how often the server refreshes, so fresher data isn't available anyway).
 * Concurrent identical requests share one HTTP call. "Not found" results are cached too;
 * errors are not. {@link #getPlayer(String)} and {@link #getPlayers(Collection)} share one
 * per-player cache, so looking up everyone in a tab list and then single players costs no
 * extra requests.
 *
 * <h2>UUIDs</h2>
 * PrimeTiers doesn't store UUIDs. {@link #resolveUuid(Player)} and
 * {@link #resolveUuids(Collection)} look up premium players' UUIDs from Mojang's public
 * profile API ({@value #DEFAULT_MOJANG_URL}), 10 names per request, cached for
 * {@link Builder#uuidCacheTtl(Duration) uuidCacheTtl} (default 1 hour).
 *
 * <h2>Errors</h2>
 * Failed futures complete with {@link PrimeTiersException}. Invalid arguments such as an
 * unknown gamemode slug throw {@link IllegalArgumentException} straight away.
 *
 * <p>One client should be shared for the whole application. It is thread-safe.
 */
public final class PrimeTiersClient implements AutoCloseable {

    /** The production API. */
    public static final String DEFAULT_BASE_URL = "https://primetiers.net";

    /** Most names the API accepts in one batch request. Larger batches are split automatically. */
    public static final int MAX_BATCH_SIZE = 50;

    private static final String VERSION = Optional
            .ofNullable(PrimeTiersClient.class.getPackage().getImplementationVersion())
            .orElse("dev");
    private static final String DEFAULT_USER_AGENT = "primetiers-java/" + VERSION;

    /** Default root of Mojang's profile API, used to resolve UUIDs. */
    public static final String DEFAULT_MOJANG_URL = "https://api.minecraftservices.com";

    /** Most names Mojang's bulk lookup accepts per request. */
    private static final int MOJANG_BATCH_SIZE = 10;
    private static final Pattern MOJANG_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Type PROFILE_LIST = new TypeToken<List<Wire.MojangProfile>>() { }.getType();

    /** Usernames the API accepts: 1-32 letters, digits, {@code _} or {@code .}. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");
    private static final Pattern GAMEMODE = Pattern.compile("[A-Za-z0-9-]{1,32}");

    private final URI baseUri;
    private final String userAgent;
    private final Duration timeout;
    private final HttpClient http;
    private final boolean ownsHttp;
    private final ResponseCache cache;
    private final URI mojangUri;
    private final ResponseCache uuidCache;
    private final Duration batchWindow;
    private final Executor batchTimer;
    /** Single-player lookups waiting to be sent as one batch, by lowercased name. Guarded by itself. */
    private final Map<String, CompletableFuture<Optional<Player>>> queue = new LinkedHashMap<>();

    private PrimeTiersClient(Builder b) {
        String base = b.baseUrl.endsWith("/") ? b.baseUrl.substring(0, b.baseUrl.length() - 1) : b.baseUrl;
        this.baseUri = URI.create(base);
        this.userAgent = b.userAgent;
        this.timeout = b.timeout;
        this.cache = new ResponseCache(b.cacheTtl);
        String mojang = b.mojangUrl.endsWith("/") ? b.mojangUrl.substring(0, b.mojangUrl.length() - 1) : b.mojangUrl;
        this.mojangUri = URI.create(mojang);
        this.uuidCache = new ResponseCache(b.uuidCacheTtl);
        this.batchWindow = b.batchWindow;
        long windowNanos = b.batchWindow.toNanos();
        this.batchTimer = b.executor != null
                ? CompletableFuture.delayedExecutor(windowNanos, TimeUnit.NANOSECONDS, b.executor)
                : CompletableFuture.delayedExecutor(windowNanos, TimeUnit.NANOSECONDS);
        if (b.httpClient != null) {
            this.http = b.httpClient;
            this.ownsHttp = false;
        } else {
            HttpClient.Builder hb = HttpClient.newBuilder()
                    .connectTimeout(b.timeout)
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (b.executor != null) {
                hb.executor(b.executor);
            }
            this.http = hb.build();
            this.ownsHttp = true;
        }
    }

    /** A client for the production API with default settings. */
    public static PrimeTiersClient create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- players -------------------------------------------------------------

    /**
     * Looks up one player by username, ignoring case.
     *
     * @return the player, or empty if they have no PrimeTiers profile. Names the API can
     *         never match (such as names with spaces) also give empty, without a request.
     */
    public CompletableFuture<Optional<Player>> getPlayer(String username) {
        Objects.requireNonNull(username, "username");
        String name = username.trim();
        if (!USERNAME.matcher(name).matches()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String lower = name.toLowerCase(Locale.ROOT);
        CompletableFuture<Optional<Player>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<Player>> existing = cache.putIfAbsent(playerKey(lower), mine);
        if (existing != null) {
            return existing;
        }
        enqueue(Map.of(lower, mine), false);
        return mine;
    }

    /**
     * Looks up many players at once. Use this rather than many {@link #getPlayer(String)}
     * calls, e.g. for everyone in a tab list.
     *
     * <p>Names are matched ignoring case, and duplicates are removed. Any number of names
     * is allowed: they are sent {@value #MAX_BATCH_SIZE} per request, and players already
     * in the cache aren't requested again. Invalid names go straight to
     * {@link PlayerBatch#missing()}. Single lookups still waiting for their
     * {@link Builder#batchWindow(Duration) batch window} are sent along with this batch.
     *
     * @return found players in request order, plus the (lowercased) names not found
     */
    public CompletableFuture<PlayerBatch> getPlayers(Collection<String> usernames) {
        Objects.requireNonNull(usernames, "usernames");

        // Lowercase and dedupe, keeping order.
        Map<String, CompletableFuture<Optional<Player>>> results = new LinkedHashMap<>();
        Map<String, CompletableFuture<Optional<Player>>> toFetch = new LinkedHashMap<>();
        for (String raw : usernames) {
            if (raw == null) {
                continue;
            }
            String name = raw.trim();
            String lower = name.toLowerCase(Locale.ROOT);
            if (name.isEmpty() || results.containsKey(lower)) {
                continue;
            }
            if (!USERNAME.matcher(name).matches()) {
                results.put(lower, CompletableFuture.completedFuture(Optional.empty()));
                continue;
            }
            CompletableFuture<Optional<Player>> mine = new CompletableFuture<>();
            CompletableFuture<Optional<Player>> existing = cache.putIfAbsent(playerKey(lower), mine);
            if (existing != null) {
                results.put(lower, existing);
            } else {
                results.put(lower, mine);
                toFetch.put(lower, mine);
            }
        }

        enqueue(toFetch, true);

        return CompletableFuture.allOf(results.values().toArray(CompletableFuture[]::new))
                .handle((ignored, error) -> {
                    if (error != null) {
                        throw new CompletionException(unwrap(error));
                    }
                    List<Player> found = new ArrayList<>();
                    List<String> missing = new ArrayList<>();
                    results.forEach((name, f) -> f.join().ifPresentOrElse(found::add, () -> missing.add(name)));
                    return new PlayerBatch(found, missing);
                });
    }

    /** {@link #getPlayers(Collection)} for a few names. */
    public CompletableFuture<PlayerBatch> getPlayers(String... usernames) {
        return getPlayers(List.of(usernames));
    }

    /**
     * Queues player lookups (lowercased name to the future to complete) for the batch
     * endpoint. With {@code now}, or once a full batch is waiting, the whole queue is sent
     * straight away; otherwise it is sent when the batch window ends. While the queue is
     * not empty, a send is always scheduled.
     */
    private void enqueue(Map<String, CompletableFuture<Optional<Player>>> lookups, boolean now) {
        boolean scheduleFlush = false;
        boolean flushNow;
        synchronized (queue) {
            boolean wasEmpty = queue.isEmpty();
            queue.putAll(lookups);
            if (queue.isEmpty()) {
                return;
            }
            flushNow = now || batchWindow.isZero() || queue.size() >= MAX_BATCH_SIZE;
            if (!flushNow && wasEmpty) {
                scheduleFlush = true;
            }
        }
        if (flushNow) {
            flushQueue();
        } else if (scheduleFlush) {
            batchTimer.execute(this::flushQueue);
        }
    }

    /** Sends everything queued, {@value #MAX_BATCH_SIZE} names per request. */
    private void flushQueue() {
        List<Map.Entry<String, CompletableFuture<Optional<Player>>>> drained;
        synchronized (queue) {
            drained = List.copyOf(queue.entrySet());
            queue.clear();
        }
        for (int i = 0; i < drained.size(); i += MAX_BATCH_SIZE) {
            Map<String, CompletableFuture<Optional<Player>>> chunk = new LinkedHashMap<>();
            drained.subList(i, Math.min(drained.size(), i + MAX_BATCH_SIZE))
                    .forEach(e -> chunk.put(e.getKey(), e.getValue()));
            fetchPlayers(chunk);
        }
    }

    /** One {@code /api/players?names=} request. Completes every future in {@code chunk}. */
    private void fetchPlayers(Map<String, CompletableFuture<Optional<Player>>> chunk) {
        String query = URLEncoder.encode(String.join(",", chunk.keySet()), StandardCharsets.UTF_8);
        this.<Wire.PlayerBatch>request("/api/players?names=" + query, Wire.PlayerBatch.class)
                .whenComplete((batch, error) -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        chunk.values().forEach(f -> f.completeExceptionally(cause));
                        return;
                    }
                    for (Player p : Wire.list(batch.items())) {
                        CompletableFuture<Optional<Player>> f = p.username() == null ? null
                                : chunk.get(p.username().toLowerCase(Locale.ROOT));
                        if (f != null) {
                            f.complete(Optional.of(p));
                        }
                    }
                    // Everything else in the chunk was not found.
                    chunk.values().forEach(f -> f.complete(Optional.empty()));
                });
    }

    // --- UUIDs ---------------------------------------------------------------

    /**
     * Resolves a premium player's Minecraft UUID through Mojang's profile API.
     *
     * <p>The lookup is by username, because PrimeTiers doesn't store UUIDs. If the player
     * changed their Minecraft name after they were tiered, this gives the UUID of whoever
     * owns the PrimeTiers name now, or empty if no one does.
     *
     * @return the UUID, or empty if the player isn't {@link Player#isPremium() premium} or
     *         Mojang has no account with that name. Non-premium players send no request.
     */
    public CompletableFuture<Optional<UUID>> resolveUuid(Player player) {
        Objects.requireNonNull(player, "player");
        return resolveUuids(List.of(player)).thenApply(m -> Optional.ofNullable(m.get(player.username())));
    }

    /**
     * Resolves the Minecraft UUIDs of every premium player in {@code players}, e.g.
     * {@code client.getPlayers(names).thenCompose(b -> client.resolveUuids(b.players()))}.
     *
     * <p>Non-premium players are skipped without a request. Names are sent to Mojang 10 per
     * request, one request at a time, and cached UUIDs aren't requested again. Read
     * {@link #resolveUuid(Player)} for how renamed players are handled.
     *
     * @return UUIDs keyed by {@link Player#username()}, in input order. Players that aren't
     *         premium or that Mojang doesn't know are left out.
     */
    public CompletableFuture<Map<String, UUID>> resolveUuids(Collection<Player> players) {
        Objects.requireNonNull(players, "players");

        Map<String, String> usernameByLower = new LinkedHashMap<>();
        Map<String, CompletableFuture<Optional<UUID>>> results = new LinkedHashMap<>();
        Map<String, CompletableFuture<Optional<UUID>>> toFetch = new LinkedHashMap<>();
        for (Player p : players) {
            if (p == null || !p.isPremium()) {
                continue;
            }
            String lower = p.username().toLowerCase(Locale.ROOT);
            if (results.containsKey(lower)) {
                continue;
            }
            usernameByLower.put(lower, p.username());
            if (!MOJANG_NAME.matcher(p.username()).matches()) {
                results.put(lower, CompletableFuture.completedFuture(Optional.empty()));
                continue;
            }
            CompletableFuture<Optional<UUID>> mine = new CompletableFuture<>();
            CompletableFuture<Optional<UUID>> existing = uuidCache.putIfAbsent("uuid:" + lower, mine);
            if (existing != null) {
                results.put(lower, existing);
            } else {
                results.put(lower, mine);
                toFetch.put(lower, mine);
            }
        }

        // One request at a time, to stay well inside Mojang's rate limit. If one fails,
        // the rest fail with the same error rather than sending more requests.
        List<String> pending = List.copyOf(toFetch.keySet());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int i = 0; i < pending.size(); i += MOJANG_BATCH_SIZE) {
            List<String> chunk = pending.subList(i, Math.min(pending.size(), i + MOJANG_BATCH_SIZE));
            chain = chain.thenCompose(v -> fetchUuids(chunk, toFetch));
        }
        chain.whenComplete((v, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                toFetch.values().forEach(f -> f.completeExceptionally(cause));
            }
        });

        return CompletableFuture.allOf(results.values().toArray(CompletableFuture[]::new))
                .handle((ignored, error) -> {
                    if (error != null) {
                        throw new CompletionException(unwrap(error));
                    }
                    Map<String, UUID> out = new LinkedHashMap<>();
                    results.forEach((lower, f) -> f.join().ifPresent(id -> out.put(usernameByLower.get(lower), id)));
                    return Collections.unmodifiableMap(out);
                });
    }

    /** One Mojang bulk request. Completes every future in {@code chunk} on success. */
    private CompletableFuture<Void> fetchUuids(List<String> chunk,
            Map<String, CompletableFuture<Optional<UUID>>> futures) {
        HttpRequest req = baseRequest(mojangUri.resolve("/minecraft/profile/lookup/bulk/byname"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(chunk), StandardCharsets.UTF_8))
                .build();
        return this.<List<Wire.MojangProfile>>send(req, PROFILE_LIST, "Mojang API").thenAccept(profiles -> {
            for (Wire.MojangProfile profile : Wire.list(profiles)) {
                UUID id = Wire.uuid(profile.id());
                CompletableFuture<Optional<UUID>> f = profile.name() == null ? null
                        : futures.get(profile.name().toLowerCase(Locale.ROOT));
                if (f != null && id != null) {
                    f.complete(Optional.of(id));
                }
            }
            // Names Mojang left out have no account.
            chunk.forEach(name -> futures.get(name).complete(Optional.empty()));
        });
    }

    // --- leaderboards --------------------------------------------------------

    /** Every gamemode's leaderboard, each sorted HT1 first. */
    public CompletableFuture<Leaderboard> getLeaderboard() {
        return cached("leaderboard", "/api/leaderboard", Leaderboard.class, Function.identity());
    }

    /** One gamemode's leaderboard, sorted HT1 first. */
    public CompletableFuture<List<LeaderboardEntry>> getLeaderboard(Gamemode gamemode) {
        return getLeaderboard(gamemode.slug());
    }

    /**
     * One gamemode's leaderboard by slug, sorted HT1 first.
     *
     * @throws IllegalArgumentException if the slug is malformed. An unknown but well-formed
     *         slug fails the future with a 404 {@link PrimeTiersException}.
     */
    public CompletableFuture<List<LeaderboardEntry>> getLeaderboard(String gamemode) {
        String slug = gamemodeSlug(gamemode);
        return cached("leaderboard:" + slug, "/api/leaderboard?gamemode=" + slug, Leaderboard.class,
                lb -> lb.get(slug));
    }

    /** The global leaderboard (top 100), ranked by total points across all gamemodes. */
    public CompletableFuture<List<GlobalEntry>> getGlobalLeaderboard() {
        return getGlobalLeaderboard(100);
    }

    /**
     * The global leaderboard, ranked by total points across all gamemodes.
     *
     * @param limit how many players, 1 to 500 (values outside are clamped)
     */
    public CompletableFuture<List<GlobalEntry>> getGlobalLeaderboard(int limit) {
        int n = Math.clamp(limit, 1, 500);
        return cached("global:" + n, "/api/leaderboard/global?limit=" + n, Wire.GlobalLeaderboard.class,
                r -> Wire.list(r.items()));
    }

    // --- gamemodes and tests -------------------------------------------------

    /** Slugs of all active gamemodes, live from the API. */
    public CompletableFuture<List<String>> getGamemodes() {
        return cached("gamemodes", "/api/gamemodes", Wire.Gamemodes.class, r -> Wire.list(r.gamemodes()));
    }

    /** Most recently updated tiers in a gamemode, newest first (up to 50). */
    public CompletableFuture<List<GamemodeTierEntry>> getGamemodeTiers(Gamemode gamemode) {
        return getGamemodeTiers(gamemode.slug(), 50);
    }

    /**
     * Most recently updated tiers in a gamemode, newest first.
     *
     * @param limit how many, 1 to 100 (values outside are clamped)
     */
    public CompletableFuture<List<GamemodeTierEntry>> getGamemodeTiers(Gamemode gamemode, int limit) {
        return getGamemodeTiers(gamemode.slug(), limit);
    }

    /**
     * Most recently updated tiers in a gamemode (by slug), newest first.
     *
     * @param limit how many, 1 to 100 (values outside are clamped)
     */
    public CompletableFuture<List<GamemodeTierEntry>> getGamemodeTiers(String gamemode, int limit) {
        String slug = gamemodeSlug(gamemode);
        int n = Math.clamp(limit, 1, 100);
        return cached("tiers:" + slug + ":" + n, "/api/gamemodes/" + slug + "/tiers?limit=" + n,
                Wire.GamemodeTiers.class, r -> Wire.list(r.items()));
    }

    /** The 20 latest tier tests across all gamemodes, newest first. */
    public CompletableFuture<List<RecentTest>> getRecentTests() {
        return getRecentTests(20);
    }

    /**
     * The latest tier tests across all gamemodes, newest first.
     *
     * @param limit how many, 1 to 100 (values outside are clamped)
     */
    public CompletableFuture<List<RecentTest>> getRecentTests(int limit) {
        int n = Math.clamp(limit, 1, 100);
        return cached("recent:" + n, "/api/recent?limit=" + n, Wire.RecentTests.class, r -> Wire.list(r.items()));
    }

    /** {@code true} if the API is up. Never cached; never fails (errors give {@code false}). */
    public CompletableFuture<Boolean> isHealthy() {
        return this.<Wire.Health>request("/api/health", Wire.Health.class)
                .handle((h, error) -> error == null && h != null && h.ok());
    }

    // --- lifecycle -----------------------------------------------------------

    /** Forgets every cached result, so the next calls go to the API. */
    public void clearCache() {
        cache.clear();
        uuidCache.clear();
    }

    /** Closes the underlying HTTP client, unless it was supplied through the builder. */
    @Override
    public void close() {
        List<CompletableFuture<Optional<Player>>> unsent;
        synchronized (queue) {
            unsent = List.copyOf(queue.values());
            queue.clear();
        }
        PrimeTiersException closed = new PrimeTiersException(0, "client closed", null, null);
        unsent.forEach(f -> f.completeExceptionally(closed));
        cache.clear();
        uuidCache.clear();
        if (ownsHttp) {
            http.close();
        }
    }

    // --- internals -----------------------------------------------------------

    private static String playerKey(String lower) {
        return "player:" + lower;
    }

    private static String gamemodeSlug(String gamemode) {
        Objects.requireNonNull(gamemode, "gamemode");
        String s = gamemode.trim().toLowerCase(Locale.ROOT);
        if (!GAMEMODE.matcher(s).matches()) {
            throw new IllegalArgumentException("invalid gamemode: " + gamemode);
        }
        return Gamemode.fromSlug(s).map(Gamemode::slug).orElse(s);
    }

    private <W, T> CompletableFuture<T> cached(String key, String path, Class<W> type, Function<W, T> map) {
        return cache.get(key, () -> this.<W>request(path, type).thenApply(map));
    }

    /** Sends a GET and parses a 200 response. Any other outcome fails with {@link PrimeTiersException}. */
    private <T> CompletableFuture<T> request(String path, Type type) {
        return send(baseRequest(baseUri.resolve(path)).GET().build(), type, path);
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);
    }

    /**
     * Sends a request and parses a 200 response. Any other outcome fails with
     * {@link PrimeTiersException}. {@code label} names the request in error messages.
     */
    private <T> CompletableFuture<T> send(HttpRequest req, Type type, String label) {
        CompletableFuture<HttpResponse<String>> sent;
        try {
            sent = http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new PrimeTiersException(0, "request failed: " + e, null, e));
        }
        return sent.handle((resp, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                String what = cause instanceof IOException ? "network error" : "request failed";
                throw new PrimeTiersException(0, label + ": " + what + ": " + cause, null, cause);
            }
            if (resp.statusCode() != 200) {
                throw httpError(resp, label);
            }
            try {
                T body = Json.GSON.fromJson(resp.body(), type);
                if (body == null) {
                    throw new PrimeTiersException(0, "empty response from " + label, null, null);
                }
                return body;
            } catch (JsonParseException e) {
                throw new PrimeTiersException(0, "unreadable response from " + label, null, e);
            }
        });
    }

    private static PrimeTiersException httpError(HttpResponse<String> resp, String label) {
        String message = label + ": HTTP " + resp.statusCode();
        try {
            Wire.Error err = Json.GSON.fromJson(resp.body(), Wire.Error.class);
            String detail = err == null ? null : err.errorMessage() != null ? err.errorMessage() : err.error();
            if (detail != null) {
                message = message + ": " + detail;
            }
        } catch (JsonParseException ignored) {
            // keep the status-only message
        }
        Duration retryAfter = resp.headers().firstValue("Retry-After")
                .flatMap(v -> {
                    try {
                        return Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                })
                .orElse(null);
        return new PrimeTiersException(resp.statusCode(), message, retryAfter, null);
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    // --- builder -------------------------------------------------------------

    /** Settings for a {@link PrimeTiersClient}. Every setting is optional. */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String userAgent = DEFAULT_USER_AGENT;
        private Duration timeout = Duration.ofSeconds(10);
        private Duration cacheTtl = Duration.ofMinutes(1);
        private String mojangUrl = DEFAULT_MOJANG_URL;
        private Duration uuidCacheTtl = Duration.ofHours(1);
        private Duration batchWindow = Duration.ofMillis(50);
        private Executor executor;
        private HttpClient httpClient;

        private Builder() {
        }

        /**
         * Identifies your project to the API, e.g. {@code "MyMod/1.2.0"}. Please set this so
         * the PrimeTiers team can contact you if your traffic causes problems.
         * The library adds its own name and version after it.
         */
        public Builder userAgent(String userAgent) {
            Objects.requireNonNull(userAgent, "userAgent");
            this.userAgent = userAgent.isBlank() ? DEFAULT_USER_AGENT : userAgent.trim() + " " + DEFAULT_USER_AGENT;
            return this;
        }

        /** API root. Defaults to {@value PrimeTiersClient#DEFAULT_BASE_URL}; change it only for testing. */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        /** Connect and response timeout for each request. Default 10 seconds. */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * How long results are cached. Default 1 minute, which is how often the server
         * refreshes its data. {@link Duration#ZERO} disables caching.
         */
        public Builder cacheTtl(Duration cacheTtl) {
            if (cacheTtl.isNegative()) {
                throw new IllegalArgumentException("cacheTtl must not be negative");
            }
            this.cacheTtl = cacheTtl;
            return this;
        }

        /** Root of Mojang's profile API. Defaults to {@value PrimeTiersClient#DEFAULT_MOJANG_URL}; change it only for testing. */
        public Builder mojangUrl(String mojangUrl) {
            this.mojangUrl = Objects.requireNonNull(mojangUrl, "mojangUrl");
            return this;
        }

        /**
         * How long resolved UUIDs (and "no such account" results) are cached. Default 1 hour.
         * {@link Duration#ZERO} disables caching.
         */
        public Builder uuidCacheTtl(Duration uuidCacheTtl) {
            if (uuidCacheTtl.isNegative()) {
                throw new IllegalArgumentException("uuidCacheTtl must not be negative");
            }
            this.uuidCacheTtl = uuidCacheTtl;
            return this;
        }

        /**
         * How long {@link PrimeTiersClient#getPlayer(String)} waits to collect other lookups
         * into the same batch request. Default 50 ms. {@link Duration#ZERO} sends each
         * lookup at once (still through the batch endpoint).
         */
        public Builder batchWindow(Duration batchWindow) {
            if (batchWindow.isNegative()) {
                throw new IllegalArgumentException("batchWindow must not be negative");
            }
            this.batchWindow = batchWindow;
            return this;
        }

        /** Executor for HTTP work and callbacks. Ignored if {@link #httpClient(HttpClient)} is set. */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /** Uses your own {@link HttpClient}, e.g. to set a proxy. The client won't close it. */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public PrimeTiersClient build() {
            return new PrimeTiersClient(this);
        }
    }
}
