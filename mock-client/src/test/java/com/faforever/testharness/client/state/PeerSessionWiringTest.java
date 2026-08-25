package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The session wiring a two-peer run needs (#218), unit-tested the way the rest of the FSM is: a
 * real {@link LobbyConnection} against an in-process {@link ScriptedWebSocketServer}, with the
 * adapter and both subprocesses stubbed. Three things land here, all of which the live two-peer
 * test depends on and none of which existed in an orchestrated session before:
 *
 * <ul>
 *   <li><b>{@code ConnectToPeer}.</b> The frame faf-server sends the host when a joiner arrives —
 *       and every peer already present when a later one does — reaches the adapter as {@code
 *       connectToPeer(login, id, offer)}.
 *   <li><b>The relays.</b> {@code IceSignalRelay} (R39) and {@code GpgNetForwarder} (R72) were
 *       built, tested, and then wired into no session. Their own transcoding is covered by their
 *       own tests; what is asserted here is only that an orchestrated launch leaves both active in
 *       both directions.
 *   <li><b>{@code gameLaunched()}.</b> The uid a second client needs as its join target.
 * </ul>
 *
 * <p>Frames that a server would send arrive over the socket rather than being posted as events,
 * because the registration is half of what is being tested — an event posted directly would pass
 * even if nothing were listening for the command. Waits on anything crossing a thread are bounded
 * and named; nothing here polls forever.
 */
final class PeerSessionWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Budget for a frame to cross the loopback socket and be acted on. Generous for a unit test.
     */
    private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(5);

    /** Poll slice while waiting for an effect produced on another thread. */
    private static final Duration POLL_SLICE = Duration.ofMillis(25);

    /** The peer the lobby names in the ConnectToPeer frames below. */
    private static final String PEER_LOGIN = "joiner-login";

    /** The peer's lobby-assigned id. */
    private static final int PEER_ID = 4242;

    private static final MockClientConfig MINIMAL_CONFIG =
            new MockClientConfig(
                    URI.create("wss://lobby.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    Path.of("/nonexistent/test-refresh-token"),
                    "00000000-0000-0000-0000-000000000000",
                    "0.0.0-mock",
                    "faf-test-harness",
                    Optional.empty(),
                    Path.of("/bin/faf-ice-adapter"),
                    Path.of("/bin/mock-game"),
                    0,
                    0,
                    0,
                    0,
                    5,
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza",
                    Optional.empty(),
                    Optional.empty());

    private static final GameConfig GAME_CONFIG =
            new GameConfig(
                    9042,
                    "faf",
                    "Two-peer wiring",
                    0,
                    "custom",
                    "global",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private DummyIceAdapterConnection adapter;

    // The dummy launchers spawn a real placeholder subprocess; tests that stop short of TERMINATED
    // never reap them through SessionTeardown, so they are tracked and terminated here.
    private final List<DummyGameLauncher> gameLaunchers = new ArrayList<>();
    private final List<DummyIceLauncher> iceLaunchers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();

        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (DummyGameLauncher launcher : gameLaunchers) {
            if (launcher.getSubprocess() != null) {
                launcher.getSubprocess().terminate(Duration.ofSeconds(1));
            }
        }
        for (DummyIceLauncher launcher : iceLaunchers) {
            if (launcher.getSubprocess() != null) {
                launcher.getSubprocess().terminate(Duration.ofSeconds(1));
            }
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // some tests close the underlying socket already
            }
        }
        server.stop(1000);
    }

    @Test
    void hostConnectsToTheJoinerTheLobbyNames() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();

        // Verbatim from faf-server: connect_to_host sends the host
        // ConnectToPeer(player_name=joiner.login, player_uid=joiner.id, offer=True) as soon as the
        // joiner's game reports Lobby.
        server.broadcastText(connectToPeer(PEER_LOGIN, PEER_ID, true) + "\n");

        Object[] call = awaitCall("connectToPeer");
        assertEquals(PEER_LOGIN, call[0], "the adapter must be told the peer's login");
        assertEquals(PEER_ID, call[1], "the adapter must be told the peer's lobby id");
        assertEquals(true, call[2], "offer=true makes this side the ICE initiator");
        assertEquals(
                ClientState.HOSTING,
                lifecycle.getState(),
                "a peer joining does not change the host's own phase");
    }

    @Test
    void offerFlagIsCarriedThroughRatherThanAssumed() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();

        // The third-peer case (faf-server connect_to_peer): the player already in the game is told
        // to answer, not to offer. Hardcoding true here would have both ends offering.
        server.broadcastText(connectToPeer(PEER_LOGIN, PEER_ID, false) + "\n");

        Object[] call = awaitCall("connectToPeer");
        assertEquals(false, call[2], "offer=false must reach the adapter as false");
        assertEquals(ClientState.HOSTING, lifecycle.getState());
    }

    @Test
    void joinerAlsoConnectsToAdditionalPeers() throws Exception {
        MockClientLifecycle lifecycle = launchedLifecycle();
        lifecycle.post(new JoinGame(joinGameCommand()));
        assertEquals(ClientState.JOINING, lifecycle.getState());

        server.broadcastText(connectToPeer(PEER_LOGIN, PEER_ID, true) + "\n");

        // Same handler, no second code path: this is what makes the 3-4 peer card (4.3.3) a
        // scaling exercise rather than another wiring one.
        Object[] call = awaitCall("connectToPeer");
        assertEquals(PEER_LOGIN, call[0]);
        assertEquals(PEER_ID, call[1]);
        assertEquals(ClientState.JOINING, lifecycle.getState());
    }

    @Test
    void malformedConnectToPeerEndsTheSessionRatherThanContinuingWithoutAPeer() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();

        // offer missing: the adapter cannot be told which side initiates, so the peer link cannot
        // be set up. Treated exactly as a malformed HostGame/JoinGame is.
        ObjectNode command = MAPPER.createObjectNode().put("command", "ConnectToPeer");
        command.putArray("args").add(PEER_LOGIN).add(PEER_ID);
        server.broadcastText(command + "\n");

        awaitState(lifecycle, ClientState.TERMINATED);
        assertNull(
                adapter.receivedMessage("connectToPeer"),
                "a frame we could not read must not produce a half-specified RPC");
    }

    @Test
    void launchedSessionRelaysIceCandidatesBothWays() throws Exception {
        launchedLifecycle();

        // Adapter → lobby (R39): a local candidate reaches the lobby as an IceMsg frame.
        adapter.fireNotification(
                "onIceMsg",
                notification(
                        "onIceMsg",
                        MAPPER.createArrayNode()
                                .add(1)
                                .add(PEER_ID)
                                .add(MAPPER.createObjectNode().put("candidate", "host"))));

        JsonNode relayed = MAPPER.readTree(awaitLobbyFrame("IceMsg"));
        assertEquals(PEER_ID, relayed.path("args").path(0).asInt());
        assertEquals("game", relayed.path("target").asText());

        // Lobby → adapter (R39): a remote candidate reaches the adapter as an iceMsg call.
        server.broadcastText(
                "{\"command\":\"IceMsg\",\"args\":["
                        + PEER_ID
                        + ",\"{\\\"candidate\\\":\\\"srflx\\\"}\"]}\n");

        Object[] call = awaitCall("iceMsg");
        assertEquals(PEER_ID, call[0], "the sender id the lobby swapped in must be preserved");
        assertEquals(
                "srflx",
                ((JsonNode) call[1]).path("candidate").asText(),
                "the payload must reach the adapter parsed back into an object");
    }

    @Test
    void launchedSessionForwardsGpgNetFramesToTheLobby() throws Exception {
        launchedLifecycle();

        // R72. Without this the server never learns the game reached Lobby, and until it does the
        // game is not joinable at all — which is why this is wired here and not left to 4.3.2.
        adapter.fireNotification(
                "onGpgNetMessageReceived",
                notification(
                        "onGpgNetMessageReceived",
                        MAPPER.createArrayNode()
                                .add("GameState")
                                .add(MAPPER.createArrayNode().add("Lobby"))));

        JsonNode forwarded = MAPPER.readTree(awaitLobbyFrame("GameState"));
        assertEquals("game", forwarded.path("target").asText());
        assertEquals("Lobby", forwarded.path("args").path(0).asText());
    }

    @Test
    void gameLaunchedCarriesTheUidASecondClientJoinsOn() throws Exception {
        MockClientLifecycle lifecycle = launchedLifecycle();

        GameConfig launched =
                lifecycle.gameLaunched().get(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(GAME_CONFIG.uid(), launched.uid(), "the join target for the second client");
        assertEquals(GAME_CONFIG.name(), launched.name());
    }

    @Test
    void gameLaunchedStaysPendingWhenTheLaunchFails() throws Exception {
        adapter = new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort(), true);
        MockClientLifecycle lifecycle = lifecycleWith(adapter);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(GAME_CONFIG));

        // The adapter connection was rigged to fail, so the launch lands in TERMINATED.
        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertFalse(
                lifecycle.gameLaunched().isDone(),
                "a session that never came up must not hand out a join target for itself");
    }

    /** A lifecycle driven to STARTING_GAME with both stub subprocesses up. */
    private MockClientLifecycle launchedLifecycle() throws Exception {
        adapter = new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle = lifecycleWith(adapter);
        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        lifecycle.post(new LaunchGame(GAME_CONFIG));
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());
        return lifecycle;
    }

    /** A lifecycle in HOSTING, the state the host is in when a joiner arrives. */
    private MockClientLifecycle hostingLifecycle() throws Exception {
        MockClientLifecycle lifecycle = launchedLifecycle();
        lifecycle.post(new HostGame(hostGameCommand()));
        assertEquals(ClientState.HOSTING, lifecycle.getState());
        return lifecycle;
    }

    private MockClientLifecycle lifecycleWith(final DummyIceAdapterConnection iceConnection) {
        DummyGameLauncher gameLauncher = new DummyGameLauncher(MINIMAL_CONFIG);
        DummyIceLauncher iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        gameLaunchers.add(gameLauncher);
        iceLaunchers.add(iceLauncher);
        return new MockClientLifecycle(
                MINIMAL_CONFIG,
                new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test"),
                iceConnection,
                gameLauncher,
                iceLauncher,
                new SessionTeardown(lobby));
    }

    /** The {@code ConnectToPeer} frame faf-server sends, in its wire shape. */
    private static String connectToPeer(final String login, final int id, final boolean offer) {
        ObjectNode command =
                MAPPER.createObjectNode().put("command", "ConnectToPeer").put("target", "game");
        command.putArray("args").add(login).add(id).add(offer);
        return command.toString();
    }

    private static JsonNode hostGameCommand() {
        ObjectNode command = MAPPER.createObjectNode().put("command", "HostGame");
        command.putArray("args").add("scmp_007");
        return command;
    }

    private static JsonNode joinGameCommand() {
        ObjectNode command = MAPPER.createObjectNode().put("command", "JoinGame");
        command.putArray("args").add("host-login").add(7);
        return command;
    }

    /** A JSON-RPC notification node as the adapter's reader would hand it to a handler. */
    private static JsonNode notification(final String method, final JsonNode params) {
        ObjectNode node = MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        node.set("params", params);
        return node;
    }

    /**
     * Wait for {@code method} to have been called on the stub adapter, and return its params.
     * Bounded: a missing call fails with the method name rather than hanging the suite.
     */
    private Object[] awaitCall(final String method) throws InterruptedException {
        long deadline = System.nanoTime() + FRAME_TIMEOUT.toNanos();
        do {
            Object[] params = adapter.receivedMessage(method);
            if (params != null) {
                return params;
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        return fail("no '" + method + "' call reached the adapter within " + FRAME_TIMEOUT);
    }

    /**
     * The next frame the client sent to the lobby, which must be a {@code command} one. Nothing
     * else sends on this socket in the states these tests sit in, so the next frame is the frame
     * under test; a different one is a failure worth seeing rather than something to skip past.
     */
    private String awaitLobbyFrame(final String command) throws Exception {
        String frame = server.pollReceived(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEquals(
                command,
                MAPPER.readTree(frame).path("command").asText(),
                "unexpected frame reached the lobby: " + frame);
        return frame;
    }

    /** Bounded wait for the FSM to reach {@code state}. */
    private static void awaitState(final MockClientLifecycle lifecycle, final ClientState state) {
        try {
            lifecycle.stateReached(state).get(FRAME_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            fail("FSM never reached " + state + "; it is in " + lifecycle.getState());
        } catch (ExecutionException e) {
            fail("failed waiting for " + state, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted waiting for " + state);
        }
    }
}
