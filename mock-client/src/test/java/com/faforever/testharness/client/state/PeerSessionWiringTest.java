package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

    /**
     * A candidates payload shaped like the adapter's own {@code CandidatesMessage}, and carried as
     * a JSON string because that is what the adapter sends and expects (see {@code
     * IceSignalRelay}).
     */
    private static final String CANDIDATES_PAYLOAD =
            "{\"srcId\":1,\"destId\":4242,\"candidates\":[{\"type\":\"host\",\"port\":6112}]}";

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
                    Optional.empty(),
                    Optional.empty(),
                    0);

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

    /** Root logger the capture appender is attached to. */
    private Logger root;

    /**
     * Captures log records for the assertions that pin a warning rather than a state change.
     * Copy-on-write: an RPC continuation writes while the test thread reads.
     */
    private ListAppender<ILoggingEvent> captured;

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

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        captured = new ListAppender<>();
        captured.list = new CopyOnWriteArrayList<>();
        captured.setContext(context);
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (captured != null) {
            captured.stop();
            root.detachAppender(captured);
        }
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
    void adapterRejectingConnectToPeerEndsTheSession() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();
        adapter.setupCallFail("connectToPeer");

        server.broadcastText(connectToPeer(PEER_LOGIN, PEER_ID, true) + "\n");

        // The adapter refusing to set up the relay is not recoverable here: this session can never
        // reach that peer, so it ends rather than sitting in HOSTING looking healthy while the
        // other side waits for candidates that will never come.
        awaitState(lifecycle, ClientState.TERMINATED);
    }

    @Test
    void hostDropsTheDepartingPeerTheLobbyNames() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();

        // Verbatim from faf-server: a game connection aborting while the game is still in
        // GameState.LOBBY runs disconnect_all_peers(), which sends every other connection
        // DisconnectFromPeer(departing_player_id).
        server.broadcastText(disconnectFromPeer(PEER_ID) + "\n");

        Object[] call = awaitCall("disconnectFromPeer");
        assertEquals(PEER_ID, call[0], "the adapter must be told which peer left");
        assertEquals(
                1,
                call.length,
                "upstream's RPC takes the id alone; anything else is our invention");
        assertEquals(
                ClientState.HOSTING,
                lifecycle.getState(),
                "a peer leaving does not change the host's own phase");
    }

    @Test
    void joinerAlsoDropsADepartingPeer() throws Exception {
        MockClientLifecycle lifecycle = launchedLifecycle();
        lifecycle.post(new JoinGame(joinGameCommand()));
        assertEquals(ClientState.JOINING, lifecycle.getState());

        server.broadcastText(disconnectFromPeer(PEER_ID) + "\n");

        Object[] call = awaitCall("disconnectFromPeer");
        assertEquals(PEER_ID, call[0]);
        assertEquals(ClientState.JOINING, lifecycle.getState());
    }

    @Test
    void departureDuringStartupReachesTheAdapter() throws Exception {
        // STARTING_GAME is inside the server's LOBBY phase: faf-server marks the game joinable when
        // the host's game reports GameState Idle, one adapter round trip before the HostGame frame
        // that moves this client to HOSTING. Registering only HOSTING and JOINING would drop a
        // departure arriving in that window with a generic "No matching transitions" WARN.
        MockClientLifecycle lifecycle = launchedLifecycle();
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());

        server.broadcastText(disconnectFromPeer(PEER_ID) + "\n");

        Object[] call = awaitCall("disconnectFromPeer");
        assertEquals(PEER_ID, call[0]);
        assertEquals(ClientState.STARTING_GAME, lifecycle.getState());
    }

    @Test
    void malformedDisconnectFromPeerEndsTheSessionRatherThanGuessingWhoLeft() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();

        // No id. Treated exactly as a malformed ConnectToPeer is: the frame is machine-generated
        // with a fixed shape, so one we cannot read means our parsing or the server's has moved.
        ObjectNode command = MAPPER.createObjectNode().put("command", "DisconnectFromPeer");
        command.putArray("args");
        server.broadcastText(command + "\n");

        awaitState(lifecycle, ClientState.TERMINATED);
        assertNull(
                adapter.receivedMessage("disconnectFromPeer"),
                "a frame we could not read must not produce an RPC for a guessed id");
    }

    @Test
    void departureDuringAMatchIsDroppedRatherThanRelayed() throws Exception {
        MockClientLifecycle lifecycle = playingLifecycle();

        server.broadcastText(disconnectFromPeer(PEER_ID) + "\n");

        // The play-on rule, and the whole reason this state is special. Relaying would end the
        // match: the adapter forwards DisconnectFromPeer with no state guard, and the mock game
        // ends on it from LIVE exactly as it does from the lobby, but without emitting its closing
        // frames. The survivor would then report a delivery failure that never happened, which is
        // the class of mis-diagnosis this card exists to remove. The departed peer's relay is
        // reaped by the adapter's own connectivity checker about ten seconds later regardless.
        awaitLogged("peer disconnect ignored during a live match: id=" + PEER_ID);
        assertNull(
                adapter.receivedMessage("disconnectFromPeer"),
                "relaying here would end the live match through the adapter's forwarded frame");
        assertEquals(
                ClientState.PLAYING,
                lifecycle.getState(),
                "a lobby departure notice must not move a client out of a running match");
    }

    @Test
    void malformedDisconnectFromPeerDuringAMatchDoesNotKillIt() throws Exception {
        MockClientLifecycle lifecycle = playingLifecycle();

        ObjectNode command = MAPPER.createObjectNode().put("command", "DisconnectFromPeer");
        command.putArray("args");
        server.broadcastText(command + "\n");

        // Same rule, and the reason the malformed branch is checked after the state rather than
        // before it: everywhere else an unreadable frame ends the session, and in PLAYING it must
        // not.
        awaitLogged("ignoring malformed DisconnectFromPeer during a live match");
        assertEquals(
                ClientState.PLAYING,
                lifecycle.getState(),
                "an unreadable lobby frame must not end a running match");
        assertNull(
                adapter.receivedMessage("disconnectFromPeer"),
                "a frame we could not read must not produce an RPC for a guessed id");
    }

    @Test
    void adapterRejectingDisconnectFromPeerLeavesTheSessionRunning() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();
        adapter.setupCallFail("disconnectFromPeer");

        server.broadcastText(disconnectFromPeer(PEER_ID) + "\n");

        // The deliberate asymmetry with connectToPeer, which ends the session on the same failure.
        // A relay left behind for a peer that has gone is self-correcting: the adapter's own
        // connectivity checker drops it about ten seconds later. Asserted on the warning rather
        // than on the state alone, because "still HOSTING" would also pass on a build where the
        // handler was never registered and nothing happened at all.
        awaitLogged("peer relay teardown failed for id=" + PEER_ID);
        assertEquals(
                ClientState.HOSTING,
                lifecycle.getState(),
                "a failed teardown RPC must not end a live session");
    }

    @Test
    void launchedSessionRelaysIceCandidatesBothWays() throws Exception {
        launchedLifecycle();

        // Adapter → lobby (R39): a local candidate reaches the lobby as an IceMsg frame. Fired as
        // a JSON string, which is what the shipped adapter sends; an object here would exercise a
        // shape the real adapter never produces, and the encoding assertion below — the one that
        // fails if an already-stringified payload is stringified again — would prove nothing.
        adapter.fireNotification(
                "onIceMsg",
                notification(
                        "onIceMsg",
                        MAPPER.createArrayNode().add(1).add(PEER_ID).add(CANDIDATES_PAYLOAD)));

        JsonNode relayed = MAPPER.readTree(awaitLobbyFrame("IceMsg"));
        assertEquals(PEER_ID, relayed.path("args").path(0).asInt());
        assertEquals("game", relayed.path("target").asText());
        JsonNode outbound = relayed.path("args").path(1);
        assertTrue(outbound.isTextual(), "the payload crosses the lobby as a JSON string");
        assertEquals(
                "host",
                MAPPER.readTree(outbound.asText()).path("candidates").path(0).path("type").asText(),
                "one parse must recover the candidates; two would mean it was encoded twice");

        // Lobby → adapter (R39): a remote candidate reaches the adapter as an iceMsg call.
        server.broadcastText(
                "{\"command\":\"IceMsg\",\"args\":["
                        + PEER_ID
                        + ",\"{\\\"candidate\\\":\\\"srflx\\\"}\"]}\n");

        Object[] call = awaitCall("iceMsg");
        assertEquals(PEER_ID, call[0], "the sender id the lobby swapped in must be preserved");
        // A string, not a parsed object: the shipped adapter's iceMsg casts its second argument to
        // String and parses it itself. See IceSignalRelay's javadoc.
        assertEquals(
                "srflx",
                MAPPER.readTree((String) call[1]).path("candidate").asText(),
                "the payload must reach the adapter verbatim as its JSON string");
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

    /**
     * A lifecycle in PLAYING, which a joiner on the default auto-launch reaches while faf-server's
     * game is still in its LOBBY phase and still sending departure notices.
     */
    private MockClientLifecycle playingLifecycle() throws Exception {
        MockClientLifecycle lifecycle = hostingLifecycle();
        lifecycle.post(new StartMatch());
        assertEquals(ClientState.PLAYING, lifecycle.getState());
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

    /** The {@code DisconnectFromPeer} frame faf-server sends, in its wire shape. */
    private static String disconnectFromPeer(final int id) {
        ObjectNode command =
                MAPPER.createObjectNode()
                        .put("command", "DisconnectFromPeer")
                        .put("target", "game");
        command.putArray("args").add(id);
        return command.toString();
    }

    /**
     * Bounded wait for a log message starting with {@code prefix}. Bounded rather than sampled: the
     * line is emitted from the RPC call's continuation, not from the test thread.
     */
    private void awaitLogged(final String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + FRAME_TIMEOUT.toNanos();
        do {
            for (ILoggingEvent event : captured.list) {
                if (event.getFormattedMessage().startsWith(prefix)) {
                    return;
                }
            }
            Thread.sleep(POLL_SLICE.toMillis());
        } while (System.nanoTime() < deadline);
        fail(
                "no log message starting with '"
                        + prefix
                        + "' within "
                        + FRAME_TIMEOUT
                        + "; captured: "
                        + captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
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
