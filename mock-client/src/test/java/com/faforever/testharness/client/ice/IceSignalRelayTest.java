package com.faforever.testharness.client.ice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IceSignalRelay}, driving both directions of the json-rpc-spec.md §7 loop
 * with a real {@link LobbyConnection} against {@link ScriptedWebSocketServer} and a real {@link
 * IceAdapterConnection} against {@link ScriptedJsonRpcServer}.
 */
final class IceSignalRelayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A representative ICE payload; a JSON object end to end, a string only across the lobby. */
    private static final String ICE_MSG_JSON =
            "{\"type\":\"offer\",\"sdp\":\"v=0 o=- 46117 2 IN IP4 127.0.0.1\"}";

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

        relay = new IceSignalRelay(lobby, adapter);
        relay.start();
    }

    @AfterEach
    void tearDown() throws Exception {
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
     * Adapter → lobby, with the payload shape the <em>shipped</em> adapter sends: {@code params[2]}
     * is already a JSON string ({@code RPCService.onIceMsg} serialises the {@code
     * CandidatesMessage} before sending). It must cross the lobby stringified exactly once.
     *
     * <p>This is the 4.3.1 regression: stringifying it again produced a double-encoded payload that
     * the receiving client dropped as "not a JSON object", so no candidate ever reached a peer and
     * ICE sat in gathering → awaitingCandidates → disconnected. Nothing single-client could catch
     * it — both halves of this relay shared the same wrong assumption.
     */
    @Test
    void alreadyStringifiedPayloadIsForwardedVerbatimNotDoubleEncoded() throws Exception {
        String stringifiedPayload = MAPPER.writeValueAsString(ICE_MSG_JSON);
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,2,"
                        + stringifiedPayload
                        + "]}\n");

        JsonNode sent = MAPPER.readTree(lobbyServer.pollReceived(3, TimeUnit.SECONDS).strip());
        JsonNode argsMsg = sent.get("args").get(1);
        assertTrue(argsMsg.isTextual(), "msg must cross the lobby as a JSON string");
        assertEquals(
                MAPPER.readTree(ICE_MSG_JSON),
                MAPPER.readTree(argsMsg.asText()),
                "one parse must recover the object; two would mean it was encoded twice");
    }

    /**
     * Adapter → lobby with an object payload — what json-rpc-spec.md §5 documents, and what a
     * future adapter release could send. Kept so the tolerant branch stays covered.
     */
    @Test
    void onIceMsgIsWrappedOntoLobbyWithStringifiedPayload() throws Exception {
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,2,"
                        + ICE_MSG_JSON
                        + "]}\n");

        JsonNode sent = MAPPER.readTree(lobbyServer.pollReceived(3, TimeUnit.SECONDS).strip());
        assertEquals("IceMsg", sent.get("command").asText());
        assertEquals("game", sent.get("target").asText());
        assertEquals(2, sent.get("args").get(0).asInt(), "args[0] must be remoteId (params[1])");
        JsonNode argsMsg = sent.get("args").get(1);
        assertTrue(argsMsg.isTextual(), "msg must cross the lobby as a JSON string");
        assertEquals(
                MAPPER.readTree(ICE_MSG_JSON),
                MAPPER.readTree(argsMsg.asText()),
                "the string must parse back to the original msg object (stringified exactly once)");
    }

    /**
     * Lobby → adapter: {@code args[1]} reaches the adapter as the JSON <em>string</em> it arrived
     * as. The shipped {@code RPCHandler.iceMsg(long, Object)} casts its second argument to {@code
     * String} and parses it itself (verified against the jar's bytecode), so handing it the parsed
     * object — which json-rpc-spec.md §5 asks for — makes the adapter throw and drop the candidate.
     */
    @Test
    void lobbyIceMsgIsPushedToAdapterAsAString() throws Exception {
        lobbyServer.broadcastText(iceMsgFrame(1, ICE_MSG_JSON));

        JsonNode call = MAPPER.readTree(adapterServer.pollReceived(3, TimeUnit.SECONDS));
        assertEquals("iceMsg", call.get("method").asText());
        assertEquals(1, call.get("params").get(0).asInt(), "params[0] must be senderId (args[0])");
        JsonNode msg = call.get("params").get(1);
        assertTrue(msg.isTextual(), "the adapter casts msg to String, so it must be sent as one");
        assertEquals(
                MAPPER.readTree(ICE_MSG_JSON),
                MAPPER.readTree(msg.asText()),
                "the string must be the payload verbatim, neither re-encoded nor altered");
    }

    /** Malformed inbound frames are logged and dropped; the relay keeps working afterwards. */
    @Test
    void malformedInboundIceMsgIsDroppedAndRelaySurvives() throws Exception {
        // args[1] is not valid JSON.
        lobbyServer.broadcastText(iceMsgFrame(1, "{not-json"));
        // args[1] is empty — readTree("") yields a MissingNode rather than throwing.
        lobbyServer.broadcastText(iceMsgFrame(1, ""));
        // args[1] is valid JSON but not an object (spec §5 requires msg: object).
        lobbyServer.broadcastText(iceMsgFrame(1, "123"));
        // args missing entirely.
        lobbyServer.broadcastText("{\"command\":\"IceMsg\",\"target\":\"game\"}");

        assertThrows(
                AssertionError.class,
                () -> adapterServer.pollReceived(300, TimeUnit.MILLISECONDS),
                "malformed frames must not produce an iceMsg call");

        // A valid frame afterwards still relays — the reader thread survived.
        lobbyServer.broadcastText(iceMsgFrame(7, ICE_MSG_JSON));
        JsonNode call = MAPPER.readTree(adapterServer.pollReceived(3, TimeUnit.SECONDS));
        assertEquals("iceMsg", call.get("method").asText());
        assertEquals(7, call.get("params").get(0).asInt());
    }

    /** start() is one-shot — a second call would relay every candidate twice, so it throws. */
    @Test
    void startTwiceThrows() {
        assertThrows(IllegalStateException.class, relay::start);
    }

    /** Malformed outbound notifications are logged and dropped; the relay keeps working. */
    @Test
    void malformedOnIceMsgIsDroppedAndRelaySurvives() throws Exception {
        // params too short (msg missing).
        adapterServer.send("{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,2]}\n");
        // remoteId not an int.
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,\"x\","
                        + ICE_MSG_JSON
                        + "]}\n");

        assertThrows(
                AssertionError.class,
                () -> lobbyServer.pollReceived(300, TimeUnit.MILLISECONDS),
                "malformed notifications must not produce a lobby IceMsg frame");

        // A valid notification afterwards still relays — the reader thread survived.
        adapterServer.send(
                "{\"jsonrpc\":\"2.0\",\"method\":\"onIceMsg\",\"params\":[1,9,"
                        + ICE_MSG_JSON
                        + "]}\n");
        JsonNode sent = MAPPER.readTree(lobbyServer.pollReceived(3, TimeUnit.SECONDS).strip());
        assertEquals("IceMsg", sent.get("command").asText());
        assertEquals(9, sent.get("args").get(0).asInt());
    }

    /**
     * Builds a lobby {@code IceMsg} frame: {@code args[0]} is the sender id, {@code args[1]} the
     * given string carried verbatim (a stringified msg for the happy path, garbage for the
     * malformed one).
     */
    private static String iceMsgFrame(final int senderId, final String msgString) throws Exception {
        ObjectNode frame = MAPPER.createObjectNode();
        frame.put("command", "IceMsg");
        frame.put("target", "game");
        frame.putArray("args").add(senderId).add(msgString);
        return MAPPER.writeValueAsString(frame);
    }
}
