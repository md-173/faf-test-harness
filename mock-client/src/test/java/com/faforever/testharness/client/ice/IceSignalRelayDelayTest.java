package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests {@link IceSignalRelay}'s WBS-5.1 forward delay: the delayed-ICE half of the harness's
 * network fault injection.
 *
 * <p>Kept apart from {@link IceSignalRelayTest} because the relay's delay is fixed at construction,
 * and that suite's shared {@code @BeforeEach} builds an undelayed one — which is also the control
 * for everything asserted here. What that suite proves about payload shape holds unchanged; this
 * one only asks when the forward happens, and that nothing is lost or reordered on the way.
 */
@Timeout(30)
final class IceSignalRelayDelayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A representative ICE payload; a JSON object end to end, a string only across the lobby. */
    private static final String ICE_MSG_JSON = "{\"type\":\"offer\",\"sdp\":\"v=0 o=- 46117 2\"}";

    /**
     * The injected delay. Long enough that the "not yet" probe below cannot pass by accident on a
     * loaded runner, short enough that six forwards still finish well inside the class timeout.
     */
    private static final Duration DELAY = Duration.ofMillis(600);

    /** Generous ceiling for a forward that should arrive; scheduling jitter is not the subject. */
    private static final int ARRIVES_SECONDS = 5;

    private ScriptedWebSocketServer lobbyServer;
    private ScriptedJsonRpcServer adapterServer;
    private LobbyConnection lobby;
    private IceAdapterConnection adapter;
    private IceSignalRelay relay;

    @BeforeEach
    void setUp() throws Exception {
        lobbyServer = new ScriptedWebSocketServer();
        lobbyServer.startAndAwait();
        adapterServer = new ScriptedJsonRpcServer();
        adapterServer.start();

        lobby = new LobbyConnection(lobbyServer.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        lobbyServer.awaitFirstClient();

        adapter =
                new IceAdapterConnection(
                        adapterServer.port(), 5, Duration.ofMillis(20), Duration.ofSeconds(2));
        adapter.connect().get(5, TimeUnit.SECONDS);
        adapterServer.awaitClient();

        relay = new IceSignalRelay(lobby, adapter, DELAY);
        relay.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (relay != null) {
            relay.stop();
        }
        if (adapter != null) {
            adapter.close();
        }
        if (adapterServer != null) {
            adapterServer.stop();
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
        lobbyServer.stop(1000);
    }

    /**
     * Adapter → lobby: the candidate arrives later than the configured delay, and arrives intact.
     * Both halves matter — a relay that dropped the candidate would also pass the "not yet" probe.
     */
    @Test
    void outboundForwardIsHeldForTheDelayAndThenArrivesIntact() throws Exception {
        long start = System.nanoTime();
        adapterServer.send(onIceMsgNotification(2, ICE_MSG_JSON));

        // The scripted servers' pollReceived fails rather than returning null, so "not yet" is
        // asserted by the clock instead of a negative poll: an undelayed relay forwards on the
        // reader thread and lands here in single-digit milliseconds.
        String frame = lobbyServer.pollReceived(ARRIVES_SECONDS, TimeUnit.SECONDS);
        assertNotNull(frame, "the candidate never arrived; a delay must not become a drop");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(
                elapsedMillis >= DELAY.toMillis(),
                "arrived after " + elapsedMillis + "ms, sooner than the " + DELAY + " delay");

        JsonNode sent = MAPPER.readTree(frame.strip());
        assertEquals("IceMsg", sent.get("command").asText());
        assertEquals(2, sent.get("args").get(0).asInt(), "args[0] must be remoteId");
        assertEquals(
                MAPPER.readTree(ICE_MSG_JSON),
                MAPPER.readTree(sent.get("args").get(1).asText()),
                "the delayed payload must be the original, stringified exactly once");
    }

    /** Lobby → adapter: the same guarantee in the other direction, one flag covering both. */
    @Test
    void inboundForwardIsHeldForTheDelayAndThenArrivesIntact() throws Exception {
        long start = System.nanoTime();
        lobbyServer.broadcastText(iceMsgFrame(1, ICE_MSG_JSON));

        String raw = adapterServer.pollReceived(ARRIVES_SECONDS, TimeUnit.SECONDS);
        assertNotNull(raw, "the candidate never arrived; a delay must not become a drop");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(
                elapsedMillis >= DELAY.toMillis(),
                "arrived after " + elapsedMillis + "ms, sooner than the " + DELAY + " delay");

        JsonNode call = MAPPER.readTree(raw);
        assertEquals("iceMsg", call.get("method").asText());
        assertEquals(1, call.get("params").get(0).asInt(), "params[0] must be senderId");
        assertEquals(
                MAPPER.readTree(ICE_MSG_JSON),
                MAPPER.readTree(call.get("params").get(1).asText()),
                "the delayed payload must reach the adapter verbatim");
    }

    /**
     * Three candidates queued back to back come out in the order they went in. The card asks for a
     * delay, explicitly not a reorder: ICE negotiation is order-sensitive, and a relay that
     * shuffled candidates would be injecting a fault nobody asked for and no counter would
     * attribute.
     */
    @Test
    void queuedForwardsKeepTheirOrder() throws Exception {
        List<String> sdps = List.of("first", "second", "third");
        for (String sdp : sdps) {
            adapterServer.send(onIceMsgNotification(2, "{\"sdp\":\"" + sdp + "\"}"));
        }

        List<String> arrived = new ArrayList<>();
        for (int i = 0; i < sdps.size(); i++) {
            String frame = lobbyServer.pollReceived(ARRIVES_SECONDS, TimeUnit.SECONDS);
            assertNotNull(frame, "only " + arrived.size() + " of 3 candidates arrived");
            JsonNode payload =
                    MAPPER.readTree(MAPPER.readTree(frame.strip()).get("args").get(1).asText());
            arrived.add(payload.get("sdp").asText());
        }

        assertEquals(sdps, arrived, "delayed candidates must keep their relative order");
    }

    /**
     * Validation stays on the reader thread. A malformed frame is rejected at once rather than
     * occupying a scheduler slot and being dropped a delay later, so the delay applies only to
     * forwards that were going to happen — and the relay still works afterwards.
     */
    @Test
    void malformedFramesAreStillRejectedImmediatelyAndTheRelaySurvives() throws Exception {
        adapterServer.send("{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1]}\n");
        adapterServer.send(onIceMsgNotification(9, ICE_MSG_JSON));

        // The good candidate must be the *first* thing to arrive. Had the malformed one been queued
        // on the scheduler and rejected a delay later, it would surface here ahead of this one.
        String frame = lobbyServer.pollReceived(ARRIVES_SECONDS, TimeUnit.SECONDS);
        assertNotNull(frame, "the relay stopped working after a malformed frame");
        assertEquals(
                9,
                MAPPER.readTree(frame.strip()).get("args").get(0).asInt(),
                "the malformed notification produced a forward instead of being dropped inline");
    }

    /** A negative delay is a typo, not a mode; it is rejected at construction. */
    @Test
    void negativeDelayIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IceSignalRelay(lobby, adapter, Duration.ofMillis(-1)));
    }

    /** {@link IceSignalRelay#stop()} is safe on an undelayed relay, which has no scheduler. */
    @Test
    void stopIsSafeWithoutADelay() {
        IceSignalRelay undelayed = new IceSignalRelay(lobby, adapter, Duration.ZERO);
        undelayed.stop();
        undelayed.stop();
    }

    /** An {@code onIceMsg} notification carrying an already-stringified payload. */
    private static String onIceMsgNotification(final int remoteId, final String msgJson)
            throws Exception {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,"
                + remoteId
                + ","
                + MAPPER.writeValueAsString(msgJson)
                + "]}\n";
    }

    /** A lobby {@code IceMsg} frame carrying {@code msgString} verbatim in {@code args[1]}. */
    private static String iceMsgFrame(final int senderId, final String msgString) throws Exception {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("command", "IceMsg");
        frame.put("target", "game");
        frame.putArray("args").add(senderId).add(msgString);
        return MAPPER.writeValueAsString(frame);
    }
}
