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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.google.gson.JsonParseException;

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
 * <h2>Caching</h2>
 * Results are cached in memory for {@link Builder#cacheTtl(Duration) cacheTtl} (default 5
 * minutes, which matches the server's own cache, so fresher data isn't available anyway).
 * Concurrent identical requests share one HTTP call. "Not found" results are cached too;
 * errors are not. {@link #getPlayer(String)} and {@link #getPlayers(Collection)} share one
 * per-player cache, so looking up everyone in a tab list and then single players costs no
 * extra requests.
 *
 * <h2>Errors</h2>
 * Failed futures complete with {@link PrimeTiersException}. Invalid arguments such as an
 * unknown gamemode slug throw {@link IllegalArgumentException} straight away.
 *
 * <p>One client should be shared for the whole application. It is thread-safe.
 */
public final class PrimeTiersClient implements AutoCloseable {

    /** The production API. */
    public static final String DEFAULT_BASE_URL = "https://api.primetiers.net";

    /** Most names the API accepts in one batch request. Larger batches are split automatically. */
    public static final int MAX_BATCH_SIZE = 50;

    private static final String VERSION = Optional
            .ofNullable(PrimeTiersClient.class.getPackage().getImplementationVersion())
            .orElse("dev");
    private static final String DEFAULT_USER_AGENT = "primetiers-java/" + VERSION;

    /** Usernames the API accepts: 1-32 letters, digits, {@code _} or {@code .}. */
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");
    private static final Pattern GAMEMODE = Pattern.compile("[A-Za-z0-9-]{1,32}");

    private final URI baseUri;
    private final String userAgent;
    private final Duration timeout;
    private final HttpClient http;
    private final boolean ownsHttp;
    private final ResponseCache cache;

    private PrimeTiersClient(Builder b) {
        String base = b.baseUrl.endsWith("/") ? b.baseUrl.substring(0, b.baseUrl.length() - 1) : b.baseUrl;
        this.baseUri = URI.create(base);
        this.userAgent = b.userAgent;
        this.timeout = b.timeout;
        this.cache = new ResponseCache(b.cacheTtl);
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
        return cache.get(playerKey(lower), () -> this.<Player>request("/api/players/" + lower, Player.class)
                .thenApply(Optional::ofNullable)
                .exceptionallyCompose(e -> {
                    if (unwrap(e) instanceof PrimeTiersException pe && pe.status() == 404) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    return CompletableFuture.failedFuture(unwrap(e));
                }));
    }

    /**
     * Looks up many players at once. Use this rather than many {@link #getPlayer(String)}
     * calls, e.g. for everyone in a tab list.
     *
     * <p>Names are matched ignoring case, and duplicates are removed. Any number of names
     * is allowed: they are sent {@value #MAX_BATCH_SIZE} per request, and players already
     * in the cache aren't requested again. Invalid names go straight to
     * {@link PlayerBatch#missing()}.
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

        List<String> pending = List.copyOf(toFetch.keySet());
        for (int i = 0; i < pending.size(); i += MAX_BATCH_SIZE) {
            List<String> chunk = pending.subList(i, Math.min(pending.size(), i + MAX_BATCH_SIZE));
            String query = URLEncoder.encode(String.join(",", chunk), StandardCharsets.UTF_8);
            this.<Wire.PlayerBatch>request("/api/players?names=" + query, Wire.PlayerBatch.class)
                    .whenComplete((batch, error) -> {
                        for (String name : chunk) {
                            CompletableFuture<Optional<Player>> f = toFetch.get(name);
                            if (error != null) {
                                f.completeExceptionally(unwrap(error));
                            }
                        }
                        if (error != null) {
                            return;
                        }
                        for (Player p : Wire.list(batch.items())) {
                            CompletableFuture<Optional<Player>> f =
                                    toFetch.get(p.username().toLowerCase(Locale.ROOT));
                            if (f != null) {
                                f.complete(Optional.of(p));
                            }
                        }
                        // Everything else in the chunk was not found.
                        for (String name : chunk) {
                            toFetch.get(name).complete(Optional.empty());
                        }
                    });
        }

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
    }

    /** Closes the underlying HTTP client, unless it was supplied through the builder. */
    @Override
    public void close() {
        cache.clear();
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
        HttpRequest req = HttpRequest.newBuilder(baseUri.resolve(path))
                .GET()
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .build();
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
                throw new PrimeTiersException(0, what + ": " + cause, null, cause);
            }
            if (resp.statusCode() != 200) {
                throw httpError(resp);
            }
            try {
                T body = Json.GSON.fromJson(resp.body(), type);
                if (body == null) {
                    throw new PrimeTiersException(0, "empty response from " + path, null, null);
                }
                return body;
            } catch (JsonParseException e) {
                throw new PrimeTiersException(0, "unreadable response from " + path, null, e);
            }
        });
    }

    private static PrimeTiersException httpError(HttpResponse<String> resp) {
        String message = "HTTP " + resp.statusCode();
        try {
            Wire.Error err = Json.GSON.fromJson(resp.body(), Wire.Error.class);
            if (err != null && err.error() != null) {
                message = message + ": " + err.error();
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
        private Duration cacheTtl = Duration.ofMinutes(5);
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

        /** How long results are cached. Default 5 minutes. {@link Duration#ZERO} disables caching. */
        public Builder cacheTtl(Duration cacheTtl) {
            if (cacheTtl.isNegative()) {
                throw new IllegalArgumentException("cacheTtl must not be negative");
            }
            this.cacheTtl = cacheTtl;
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
