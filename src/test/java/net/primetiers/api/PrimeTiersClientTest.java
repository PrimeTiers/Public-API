package net.primetiers.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.primetiers.api.FakeApi.Reply;
import net.primetiers.api.model.Gamemode;
import net.primetiers.api.model.GlobalEntry;
import net.primetiers.api.model.Leaderboard;
import net.primetiers.api.model.Player;
import net.primetiers.api.model.PlayerBatch;
import net.primetiers.api.model.RecentTest;
import net.primetiers.api.model.Tier;

class PrimeTiersClientTest {

    private static final String BURNET = """
            {"id":1,"username":"Burnet01","region":"NA","platform":"premium","tiers":[
              {"gamemode":"mace-spear","tier":"HT3","updatedAt":"2025-03-01T00:00:00Z"},
              {"gamemode":"crystal","tier":"LT2","updatedAt":"2025-02-01 12:30:00+00"},
              {"gamemode":"newmode","tier":"XT9","updatedAt":"garbage"}]}""";

    private FakeApi api;
    private PrimeTiersClient client;

    @BeforeEach
    void setUp() throws Exception {
        api = new FakeApi();
        client = PrimeTiersClient.builder().baseUrl(api.baseUrl()).userAgent("TestMod/1.0").build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        api.close();
    }

    @Test
    void getPlayerParsesAndCaches() {
        api.on("/api/players/burnet01", Reply.ok(BURNET));

        Player p = client.getPlayer("Burnet01").join().orElseThrow();
        assertEquals("Burnet01", p.username());
        assertEquals(Tier.HT3, p.tier(Gamemode.MACE_SPEAR).orElseThrow().tier());
        assertEquals(Instant.parse("2025-02-01T12:30:00Z"), p.tier("crystal").orElseThrow().updatedAt());
        assertEquals(Tier.LT2, p.bestTier().orElseThrow().tier());

        // Unknown tier and timestamp don't break parsing.
        assertNull(p.tier("newmode").orElseThrow().tier());
        assertNull(p.tier("newmode").orElseThrow().updatedAt());

        client.getPlayer("BURNET01").join();
        assertEquals(1, api.count("/api/players/"), "second lookup must come from the cache");
        assertTrue(api.userAgents.getFirst().startsWith("TestMod/1.0 primetiers-java/"));
    }

    @Test
    void missingPlayerIsEmptyAndCached() {
        api.on("/api/players/nobody", Reply.error(404, "player not found"));
        assertTrue(client.getPlayer("nobody").join().isEmpty());
        assertTrue(client.getPlayer("nobody").join().isEmpty());
        assertEquals(1, api.count("/api/players/nobody"));
    }

    @Test
    void invalidNameMakesNoRequest() {
        assertTrue(client.getPlayer("has space").join().isEmpty());
        assertTrue(client.getPlayer("").join().isEmpty());
        assertTrue(api.requests.isEmpty());
    }

    @Test
    void batchSplitsDedupesAndKeepsOrder() {
        // Server: every name ending in an even number exists.
        api.on("/api/players", query -> {
            List<String> names = Arrays.asList(query.substring("names=".length()).split(","));
            assertTrue(names.size() <= PrimeTiersClient.MAX_BATCH_SIZE);
            List<String> items = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String n : names) {
                if (Integer.parseInt(n.substring(1)) % 2 == 0) {
                    items.add("{\"id\":" + n.substring(1) + ",\"username\":\"" + n.toUpperCase() + "\",\"tiers\":[]}");
                } else {
                    missing.add("\"" + n + "\"");
                }
            }
            return Reply.ok("{\"count\":" + items.size() + ",\"items\":[" + String.join(",", items)
                    + "],\"missing\":[" + String.join(",", missing) + "]}");
        });

        List<String> names = new ArrayList<>(IntStream.range(0, 60).mapToObj(i -> "p" + i).toList());
        names.add("P0");          // duplicate, different case
        names.add("bad name!");   // invalid
        PlayerBatch batch = client.getPlayers(names).join();

        assertEquals(2, api.count("/api/players?"), "60 names -> 2 requests");
        assertEquals(30, batch.players().size());
        assertEquals("P0", batch.players().getFirst().username());
        assertEquals("P58", batch.players().getLast().username());
        assertEquals(31, batch.missing().size());
        assertTrue(batch.missing().contains("bad name!"));
        assertTrue(batch.get("p2").isPresent());

        // Batch results fill the single-player cache.
        assertEquals("P4", client.getPlayer("p4").join().orElseThrow().username());
        assertTrue(client.getPlayer("p5").join().isEmpty());
        assertEquals(2, api.requests.size());

        // A second batch only asks for names it hasn't seen.
        client.getPlayers("p0", "p100").join();
        assertEquals("/api/players?names=p100", api.requests.getLast());
    }

    @Test
    void leaderboards() {
        api.on("/api/leaderboard", q -> q.isEmpty()
                ? Reply.ok("""
                        {"gamemodes":{"crystal":[{"rank":1,"username":"alpha","region":"EU","tier":"HT3"}],
                                      "mace-spear":[]}}""")
                : Reply.ok("""
                        {"gamemodes":{"mace-spear":[{"rank":1,"username":"Burnet01","region":"NA","tier":"HT3"}]}}"""));
        api.on("/api/leaderboard/global", Reply.ok("""
                {"count":1,"items":[{"rank":1,"username":"alpha","region":"EU","points":55,
                  "tiers":[{"gamemode":"crystal","tier":"HT3"},{"gamemode":"nethertite","tier":"LT1"}]}]}"""));

        Leaderboard all = client.getLeaderboard().join();
        assertEquals(List.of("crystal", "mace-spear"), List.copyOf(all.gamemodes().keySet()));
        assertEquals("alpha", all.get(Gamemode.CRYSTAL).getFirst().username());
        assertTrue(all.get(Gamemode.CART).isEmpty());

        assertEquals("Burnet01", client.getLeaderboard(Gamemode.MACE_SPEAR).join().getFirst().username());
        assertEquals("/api/leaderboard?gamemode=mace-spear", api.requests.getLast());
        client.getLeaderboard("macespear").join(); // alias -> same cache entry
        assertEquals(2, api.requests.size());

        GlobalEntry top = client.getGlobalLeaderboard(9999).join().getFirst();
        assertEquals(55, top.points());
        assertEquals(Tier.LT1, top.tiers().get(1).tier());
        assertEquals("/api/leaderboard/global?limit=500", api.requests.getLast(), "limit is clamped");

        assertThrows(IllegalArgumentException.class, () -> client.getLeaderboard("../admin"));
    }

    @Test
    void recentTestsAndGamemodes() {
        api.on("/api/recent", Reply.ok("""
                {"count":1,"items":[{"username":"Burnet01","gamemode":"mace-spear","tier":"HT3",
                  "previousTier":null,"region":"NA","createdAt":"2025-03-01T00:00:00.123+00:00"}]}"""));
        api.on("/api/gamemodes", Reply.ok("{\"gamemodes\":[\"crystal\",\"mace-spear\"]}"));

        RecentTest t = client.getRecentTests(5).join().getFirst();
        assertEquals(Tier.HT3, t.tier());
        assertNull(t.previousTier());
        assertEquals(Instant.parse("2025-03-01T00:00:00.123Z"), t.createdAt());
        assertEquals("/api/recent?limit=5", api.requests.getLast());

        assertEquals(List.of("crystal", "mace-spear"), client.getGamemodes().join());
    }

    @Test
    void errorsAreReportedAndNotCached() {
        api.on("/api/recent", Reply.error(502, "upstream unavailable"));
        CompletionException e = assertThrows(CompletionException.class, () -> client.getRecentTests().join());
        PrimeTiersException pe = assertInstanceOf(PrimeTiersException.class, e.getCause());
        assertEquals(502, pe.status());
        assertTrue(pe.isServerError());
        assertTrue(pe.getMessage().contains("upstream unavailable"));

        assertThrows(CompletionException.class, () -> client.getRecentTests().join());
        assertEquals(2, api.count("/api/recent"), "errors must not be cached");
    }

    @Test
    void rateLimitExposesRetryAfter() {
        api.on("/api/players", new Reply(429, "{\"error\":\"rate limited\"}", Map.of("Retry-After", "30")));
        CompletionException e = assertThrows(CompletionException.class, () -> client.getPlayers("a", "b").join());
        PrimeTiersException pe = assertInstanceOf(PrimeTiersException.class, e.getCause());
        assertTrue(pe.isRateLimited());
        assertEquals(Duration.ofSeconds(30), pe.retryAfter().orElseThrow());

        // The failure was not cached; the next call retries.
        api.on("/api/players", Reply.ok("{\"items\":[],\"missing\":[\"a\",\"b\"]}"));
        assertEquals(List.of("a", "b"), client.getPlayers("a", "b").join().missing());
    }

    @Test
    void networkErrorHasStatusZero() {
        PrimeTiersClient dead = PrimeTiersClient.builder().baseUrl("http://127.0.0.1:1").build();
        CompletionException e = assertThrows(CompletionException.class, () -> dead.getRecentTests().join());
        PrimeTiersException pe = assertInstanceOf(PrimeTiersException.class, e.getCause());
        assertEquals(0, pe.status());
        assertFalse(dead.isHealthy().join());
        dead.close();
    }

    @Test
    void cacheCanBeDisabledAndCleared() {
        api.on("/api/gamemodes", Reply.ok("{\"gamemodes\":[]}"));
        client.getGamemodes().join();
        client.clearCache();
        client.getGamemodes().join();
        assertEquals(2, api.count("/api/gamemodes"));

        try (PrimeTiersClient noCache = PrimeTiersClient.builder()
                .baseUrl(api.baseUrl()).cacheTtl(Duration.ZERO).build()) {
            noCache.getGamemodes().join();
            noCache.getGamemodes().join();
        }
        assertEquals(4, api.count("/api/gamemodes"));
    }

    @Test
    void healthy() {
        api.on("/api/health", Reply.ok("{\"ok\":true}"));
        assertTrue(client.isHealthy().join());
    }

    @Test
    void isPremium() {
        assertTrue(new Player(1, "a", null, "premium", List.of()).isPremium());
        assertTrue(new Player(1, "a", null, "PREMIUM", List.of()).isPremium());
        assertFalse(new Player(1, "a", null, "cracked", List.of()).isPremium());
        assertFalse(new Player(1, "a", null, null, List.of()).isPremium());
    }

    @Test
    void tierAndGamemodeHelpers() {
        assertTrue(Tier.HT1.isBetterThan(Tier.LT1));
        assertEquals(3, Tier.LT3.level());
        assertTrue(Tier.HT5.isHigh());
        assertFalse(Tier.LT5.isHigh());
        assertEquals(Tier.HT2, Tier.parse(" ht2 ").orElseThrow());
        assertEquals(Gamemode.NETHERITE_OP, Gamemode.fromSlug("nethop").orElseThrow());
        assertEquals(Gamemode.MACE_SPEAR, Gamemode.fromSlug("MACE-SPEAR").orElseThrow());
        assertTrue(Gamemode.fromSlug("vanilla").isEmpty());
        assertEquals("crystal,axe,sword,mace,mace-spear,nethertite,pot,smp,uhc,cart",
                Arrays.stream(Gamemode.values()).map(Gamemode::slug).collect(Collectors.joining(",")));
    }
}
