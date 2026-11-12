# PrimeTiers API for Java

A Java library for reading PrimeTiers data (player tiers, leaderboards and recent tests) in your mods, plugins, bots and tools. It calls the public API at **https://api.primetiers.net** over HTTPS and needs no API key or database access.

- Java 25, Maven
- Async: every call returns a `CompletableFuture` and never blocks your game thread
- Caches results in memory and shares in-flight requests, so lookups for a whole tab list cost few requests
- Batch lookups of any size (split into requests of 50 names automatically)
- One dependency: Gson 2.11, which Minecraft already ships

## Install

The library isn't on a public Maven repository yet. Build it and install it into your local Maven repository:

```bash
mvn install
```

**Maven**

```xml
<dependency>
    <groupId>net.primetiers</groupId>
    <artifactId>primetiers-api</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle** (Fabric / Paper / anything else)

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation "net.primetiers:primetiers-api:1.0.0"
    // Fabric: also bundle the jar into your mod
    include "net.primetiers:primetiers-api:1.0.0"
}
```

Minecraft already provides Gson, so mods don't need to bundle it. Standalone apps get it through the normal dependency.

## Quick start

```java
import net.primetiers.api.PrimeTiersClient;
import net.primetiers.api.model.Gamemode;

// Create one client and share it across your whole mod.
PrimeTiersClient primeTiers = PrimeTiersClient.builder()
        .userAgent("MyMod/1.0.0")   // please identify your project
        .build();

primeTiers.getPlayer("Burnet01").thenAccept(player -> player.ifPresentOrElse(
        p -> System.out.println(p.username() + ": " + p.tier(Gamemode.MACE_SPEAR)),
        () -> System.out.println("Not on PrimeTiers")));
```

### Threading in mods

Callbacks run on HTTP threads, not the game thread. Hop back before touching game state:

```java
// Fabric (client)
primeTiers.getPlayer(name).thenAcceptAsync(p -> updateNametag(p), MinecraftClient.getInstance()::execute);

// Paper / Bukkit
primeTiers.getPlayer(name).thenAccept(p ->
        Bukkit.getScheduler().runTask(plugin, () -> updateNametag(p)));
```

Use `.join()` only off the game thread, e.g. in a bot or command-line tool.

## Methods

| Method | Returns | Endpoint |
|---|---|---|
| `getPlayer(username)` | `Optional<Player>`; empty if not on PrimeTiers | `GET /api/players/{username}` |
| `getPlayers(names...)` / `getPlayers(Collection)` | `PlayerBatch` (`players()`, `missing()`) | `GET /api/players?names=` |
| `getLeaderboard()` | `Leaderboard` for every gamemode | `GET /api/leaderboard` |
| `getLeaderboard(Gamemode)` / `getLeaderboard(String)` | `List<LeaderboardEntry>`, HT1 first | `GET /api/leaderboard?gamemode=` |
| `getGlobalLeaderboard()` / `(limit)` | `List<GlobalEntry>` by total points, limit 1-500 (default 100) | `GET /api/leaderboard/global` |
| `getGamemodeTiers(gamemode[, limit])` | `List<GamemodeTierEntry>`, newest first, limit 1-100 (default 50) | `GET /api/gamemodes/{gamemode}/tiers` |
| `getRecentTests()` / `(limit)` | `List<RecentTest>`, newest first, limit 1-100 (default 20) | `GET /api/recent` |
| `getGamemodes()` | `List<String>` of active gamemode slugs | `GET /api/gamemodes` |
| `isHealthy()` | `Boolean`; never fails | `GET /api/health` |
| `clearCache()` | Drops all cached results | none |

### Examples

Show tiers for everyone online, in one or two requests:

```java
List<String> names = Bukkit.getOnlinePlayers().stream().map(p -> p.getName()).toList();
primeTiers.getPlayers(names).thenAccept(batch -> {
    for (var p : batch.players()) {
        p.bestTier().ifPresent(best ->
                System.out.println(p.username() + " best: " + best.tier() + " in " + best.gamemode()));
    }
});
```

Top 10 in a gamemode:

```java
primeTiers.getLeaderboard(Gamemode.CRYSTAL).thenAccept(board ->
        board.stream().limit(10).forEach(e ->
                System.out.println("#" + e.rank() + " " + e.username() + " " + e.tier())));
```

Compare tiers:

```java
Tier tier = player.tier(Gamemode.SWORD).map(PlayerTier::tier).orElse(null);
if (tier != null && tier.isBetterThan(Tier.LT3)) { ... }
tier.level();   // 1-5
tier.isHigh();  // HT vs LT
```

## Data model

All types are immutable records in `net.primetiers.api.model`.

- **`Player`**: `id`, `username`, `region` (`NA`/`EU`/`AS`…, nullable), `platform` (nullable), `tiers`. Helpers: `tier(Gamemode)`, `tier(String)`, `bestTier()`.
- **`PlayerTier`**: `gamemode` (slug), `tier`, `updatedAt` (`Instant`).
- **`Tier`**: `HT1`, `LT1`, `HT2`, … `LT5`, best first. `rank()`, `level()`, `isHigh()`, `isBetterThan()`, `Tier.parse()`.
- **`Gamemode`**: `CRYSTAL`, `AXE`, `SWORD`, `MACE`, `MACE_SPEAR` (`mace-spear`), `NETHERITE_OP` (`nethertite`), `POT`, `SMP`, `UHC`, `CART`. `slug()`, `Gamemode.fromSlug()`.
- Gamemodes in responses are strings, so a gamemode added later still shows up. A `Tier` field is `null` only if the API sends a tier this library version doesn't know.

## Errors

A failed request completes the future with `PrimeTiersException` (wrapped in `CompletionException` by `join()`):

| Check | Meaning |
|---|---|
| `isRateLimited()` | HTTP 429. Wait `retryAfter()`. |
| `isServerError()` | API or network problem (5xx, or `status() == 0` for no response). Retry later. |
| `status()` | The HTTP status code |

"Player not found" is **not** an error: `getPlayer` returns an empty `Optional`, and `getPlayers` lists the name in `missing()`. Invalid usernames are treated the same way and send no request. A malformed gamemode string throws `IllegalArgumentException` immediately.

```java
primeTiers.getRecentTests().exceptionally(e -> {
    if (e.getCause() instanceof PrimeTiersException pte && pte.isRateLimited()) { ... }
    return List.of();
});
```

## Configuration

```java
PrimeTiersClient.builder()
        .userAgent("MyMod/1.0.0")            // sent as "MyMod/1.0.0 primetiers-java/<version>"
        .cacheTtl(Duration.ofMinutes(5))     // default 5 min; Duration.ZERO disables caching
        .timeout(Duration.ofSeconds(10))     // per request
        .executor(myExecutor)                // threads for HTTP and callbacks
        .httpClient(myHttpClient)            // or bring your own java.net.http.HttpClient
        .baseUrl("https://api.primetiers.net")
        .build();
```

Close the client (`close()`, or try-with-resources) when your mod or app shuts down.

## Fair use

- The API caches responses for 5 minutes, so polling faster gets you no fresher data. Keep the default cache.
- Use `getPlayers` for groups of players instead of many `getPlayer` calls.
- The API rate-limits clients that send too many requests (HTTP 429). Back off for `retryAfter()`.
- Set `userAgent` so the PrimeTiers team can tell which project is sending traffic.

## Building

```bash
mvn package    # compile, test, and build the jar, -sources.jar and -javadoc.jar into target/
mvn test       # tests only
mvn install    # install into ~/.m2 for use in other local projects
```

Tests run against a local fake server, so they need no network access.

### Project layout

```
pom.xml
src/main/java/net/primetiers/api/
  PrimeTiersClient.java        Client and builder (all public methods)
  PrimeTiersException.java     Error type
  model/                       Response records, Tier and Gamemode enums
  internal/                    JSON parsing, response envelopes, cache (not public API)
src/test/java/net/primetiers/api/
  FakeApi.java                 Local stand-in for api.primetiers.net
  PrimeTiersClientTest.java
```

### Keeping in sync with the server

The server is the Rust Worker in `../API`. When it changes:

- **New gamemode:** add it to `model/Gamemode.java`. Until then it still comes through as a string.
- **New endpoint:** add a response envelope to `internal/Wire.java` if needed, any new record to `model/`, and a method to `PrimeTiersClient` using `cached(...)`. Then add a test and a row to the table above.
- **Response fields:** records ignore unknown JSON fields. Add new fields as record components. Removing or renaming components breaks users, so bump the major version.

The website-only `POST /api/network/report` routes are not part of this library.
