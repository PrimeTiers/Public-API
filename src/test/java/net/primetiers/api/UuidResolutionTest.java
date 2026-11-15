package net.primetiers.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;

import net.primetiers.api.FakeApi.Reply;
import net.primetiers.api.model.Player;

class UuidResolutionTest {

    private static final String BULK = "/minecraft/profile/lookup/bulk/byname";

    private FakeApi mojang;
    private PrimeTiersClient client;

    @BeforeEach
    void setUp() throws Exception {
        mojang = new FakeApi();
        // Fake Mojang: every name except ones starting with "ghost" exists. Like the real
        // API, names come back in canonical case and in no particular order.
        mojang.on(BULK, body -> {
            String[] names = new Gson().fromJson(body, String[].class);
            assertTrue(names.length <= 10, "Mojang accepts at most 10 names");
            List<String> out = new ArrayList<>();
            for (int i = names.length - 1; i >= 0; i--) {
                String n = names[i];
                if (!n.startsWith("ghost")) {
                    out.add("{\"id\":\"" + undashed(n) + "\",\"name\":\"" + n.toUpperCase() + "\"}");
                }
            }
            return Reply.ok("[" + String.join(",", out) + "]");
        });
        client = PrimeTiersClient.builder()
                .baseUrl("http://127.0.0.1:1") // PrimeTiers isn't needed here
                .mojangUrl(mojang.baseUrl())
                .build();
    }

    @AfterEach
    void tearDown() {
        client.close();
        mojang.close();
    }

    /** Deterministic fake UUID for a name, without dashes. */
    private static String undashed(String name) {
        return uuidOf(name).toString().replace("-", "");
    }

    private static UUID uuidOf(String name) {
        return UUID.nameUUIDFromBytes(name.toLowerCase().getBytes());
    }

    private static Player premium(String name) {
        return new Player(1, name, "NA", "premium", List.of());
    }

    private static Player cracked(String name) {
        return new Player(2, name, "AS", "cracked", List.of());
    }

    @Test
    void resolvesPremiumPlayersOnly() {
        List<Player> players = new ArrayList<>(IntStream.range(0, 12).mapToObj(i -> premium("Player" + i)).toList());
        players.add(cracked("CrackedGuy"));
        players.add(premium("ghost_renamed"));
        players.add(premium("PLAYER0")); // duplicate, different case
        players.add(premium("bedrock.name")); // not a valid Java name, no request

        Map<String, UUID> uuids = client.resolveUuids(players).join();

        assertEquals(12, uuids.size());
        assertEquals(uuidOf("Player0"), uuids.get("Player0"), "keyed by PrimeTiers username, dashes added");
        assertEquals("Player0", uuids.keySet().iterator().next(), "input order");
        assertFalse(uuids.containsKey("CrackedGuy"));
        assertFalse(uuids.containsKey("ghost_renamed"));
        assertEquals(2, mojang.count("POST " + BULK), "13 valid premium names -> 2 requests");

        // Everything is cached now, including "no such account".
        client.resolveUuids(players).join();
        assertTrue(client.resolveUuid(premium("ghost_renamed")).join().isEmpty());
        assertEquals(uuidOf("Player5"), client.resolveUuid(premium("player5")).join().orElseThrow());
        assertEquals(2, mojang.requests.size());
    }

    @Test
    void nonPremiumMakesNoRequest() {
        assertTrue(client.resolveUuid(cracked("Someone")).join().isEmpty());
        assertTrue(client.resolveUuid(new Player(3, "NoPlatform", null, null, List.of())).join().isEmpty());
        assertTrue(client.resolveUuids(List.of()).join().isEmpty());
        assertTrue(mojang.requests.isEmpty());
    }

    @Test
    void mojangErrorsFailAndAreNotCached() {
        mojang.on(BULK, new Reply(429, "{\"errorMessage\":\"Too many requests\"}", Map.of("Retry-After", "60")));
        List<Player> players = IntStream.range(0, 25).mapToObj(i -> premium("p" + i)).toList();

        CompletionException e = assertThrows(CompletionException.class, () -> client.resolveUuids(players).join());
        PrimeTiersException pe = assertInstanceOf(PrimeTiersException.class, e.getCause());
        assertTrue(pe.isRateLimited());
        assertTrue(pe.getMessage().startsWith("Mojang API: HTTP 429: Too many requests"), pe.getMessage());
        assertEquals(1, mojang.requests.size(), "stops after the first failed request");

        mojang.on(BULK, Reply.ok("[{\"id\":\"" + undashed("p0") + "\",\"name\":\"p0\"}]"));
        assertEquals(uuidOf("p0"), client.resolveUuid(premium("p0")).join().orElseThrow());
    }
}
