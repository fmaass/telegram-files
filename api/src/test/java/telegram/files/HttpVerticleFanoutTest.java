package telegram.files;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the TELEGRAM_EVENT fanout logic rewritten in 0.4.5-fms
 * (BL-06) and hardened in BL-02: target resolution and stale-close teardown.
 * Exercises the extracted {@link HttpVerticle#resolveEventTargets} and
 * {@link HttpVerticle#handleWebSocketClose} without a running verticle.
 */
class HttpVerticleFanoutTest {

    private HttpVerticle verticle;

    private TelegramVerticle telegramX;

    @BeforeEach
    void setUp() {
        // clients is static — clear it so tests do not leak into each other.
        HttpVerticle.clients.clear();
        verticle = new HttpVerticle();
        verticle.sessionTelegramVerticles.clear();
        verticle.unboundClients.clear();

        telegramX = mock(TelegramVerticle.class);
        when(telegramX.getId()).thenReturn(100L);
    }

    // Case 1: a session bound to telegram X receives an event for X exactly once.
    @Test
    void boundSessionReceivesMatchingEvent() {
        HttpVerticle.clients.put("s1", "handler-1");
        verticle.sessionTelegramVerticles.put("s1", telegramX);

        List<String> targets = verticle.resolveEventTargets("100");

        assertEquals(List.of("handler-1"), targets);
    }

    // Case 2: a session bound to X gets no delivery for an event addressed to Y.
    @Test
    void boundSessionIgnoresNonMatchingEvent() {
        HttpVerticle.clients.put("s1", "handler-1");
        verticle.sessionTelegramVerticles.put("s1", telegramX);

        List<String> targets = verticle.resolveEventTargets("999");

        assertTrue(targets.isEmpty());
    }

    // Case 3: an unbound session is delivered to; a session that is BOTH bound
    // and present in unboundClients is delivered to exactly once (sentSessionIds dedup).
    @Test
    void unboundSessionReceivesAndIsNotDoubleCounted() {
        HttpVerticle.clients.put("s1", "handler-1");
        verticle.sessionTelegramVerticles.put("s1", telegramX);
        verticle.unboundClients.add("s1"); // also (wrongly) tracked as unbound

        List<String> targets = verticle.resolveEventTargets("100");

        assertEquals(List.of("handler-1"), targets, "session must be delivered to once, not twice");
    }

    // Case 4 (BL-02 regression): a reconnect that re-adds the same sessionId must
    // NOT create duplicate deliveries. A List field would hold [s1, s1] and fan out
    // twice; the Set field dedups to a single delivery.
    @Test
    void reconnectDoesNotDuplicateUnboundDelivery() {
        HttpVerticle.clients.put("s1", "handler-1");
        verticle.unboundClients.add("s1");
        verticle.unboundClients.add("s1"); // simulate a reconnect re-adding the session

        List<String> targets = verticle.resolveEventTargets(null);

        assertEquals(1, targets.size(), "duplicate sessionId must fan out exactly once");
        assertEquals(List.of("handler-1"), targets);
    }

    // Case 5: with no clients at all, resolution returns an empty list (early return,
    // no encode work happens on the event loop).
    @Test
    void noClientsResolvesToEmpty() {
        List<String> targets = verticle.resolveEventTargets("100");

        assertTrue(targets.isEmpty());
    }

    // Case 6 (BL-02 leak fix): a live socket closing removes the session from ALL
    // three collections, including unboundClients (previously leaked).
    @Test
    void closingLiveSocketRemovesSessionFromAllCollections() {
        HttpVerticle.clients.put("s1", "handler-1");
        verticle.sessionTelegramVerticles.put("s1", telegramX);
        verticle.unboundClients.add("s1");

        verticle.handleWebSocketClose("s1", "handler-1");

        assertFalse(HttpVerticle.clients.containsKey("s1"));
        assertFalse(verticle.sessionTelegramVerticles.containsKey("s1"));
        assertFalse(verticle.unboundClients.contains("s1"), "unboundClients must not leak the closed session");
    }

    // Stale-close (BL-02 race): a late close from an OLD socket after a same-session
    // reconnect must NOT evict the newer live registration. The reconnected client
    // keeps receiving events.
    @Test
    void staleCloseAfterReconnectKeepsLiveRegistration() {
        // old socket connects
        HttpVerticle.clients.put("s1", "handler-OLD");
        verticle.unboundClients.add("s1");
        // same session reconnects (overwrites clients with the new handler)
        HttpVerticle.clients.put("s1", "handler-NEW");
        verticle.unboundClients.add("s1");

        // the OLD socket's close handler fires late
        verticle.handleWebSocketClose("s1", "handler-OLD");

        assertEquals("handler-NEW", HttpVerticle.clients.get("s1"), "stale close must not evict the live handler");
        assertTrue(verticle.unboundClients.contains("s1"), "live session must survive a stale close");
        assertEquals(List.of("handler-NEW"), verticle.resolveEventTargets(null),
                "reconnected client must still resolve as a target");
    }

    // Case 7 (BL-04): encoding the published payload JsonObject directly must be
    // equivalent to the old mapTo(EventPayload)/Json.encode() round-trip — both
    // deserialize field-wise to the same EventPayload the publisher put on the bus.
    @Test
    void directPayloadEncodeEqualsRoundTrip() {
        EventPayload payload = new EventPayload(EventPayload.TYPE_FILE, "code-1", Map.of("k", "v"), 1234L);
        // Publishers put JsonObject.mapFrom(payload) on the event bus.
        JsonObject published = JsonObject.mapFrom(payload);

        // New BL-04 path: encode the JsonObject directly.
        String directEncoded = published.encode();
        // Old path: mapTo the record, then Json.encode it.
        String roundTripEncoded = Json.encode(published.mapTo(EventPayload.class));

        assertEquals(payload, new JsonObject(directEncoded).mapTo(EventPayload.class),
                "direct-encoded payload must deserialize back to the published EventPayload");
        assertEquals(payload, new JsonObject(roundTripEncoded).mapTo(EventPayload.class),
                "round-trip payload must deserialize back to the published EventPayload");
        assertEquals(new JsonObject(roundTripEncoded), new JsonObject(directEncoded),
                "both encodings must be field-wise equal");
    }
}
