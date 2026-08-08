package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceEventLogger;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import com.faforever.testharness.shared.logging.JsonLineEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the harness log contract the multi-peer cards build on (WBS-3.1.6.2). Captured events are
 * run through the real {@link JsonLineEncoder} and parsed as JSON, so these assertions read the
 * same records a harness reads from the JSONL file rather than matching console text.
 *
 * <p>Changing any format asserted here is a breaking change for WBS 4.2.2 and the Phase 5 fault
 * injection cards. See {@code mock-client/README.md} § "Harness log contract".
 */
final class HarnessLogContractTest {

    /** Parses the encoded JSONL records. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Encodes captured events exactly as the file appender would. */
    private static final JsonLineEncoder ENCODER = new JsonLineEncoder();

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
                    "WARN",
                    Optional.empty(),
                    OptionalInt.empty(),
                    "Rhiza",
                    Optional.empty(),
                    Optional.empty());

    private static final GameConfig MINIMAL_GAME_CONFIG =
            new GameConfig(
                    12345,
                    "faf",
                    "Test Game Name",
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

    /** A {@code HostGame} command carrying the textual map argument the FSM action requires. */
    private static final JsonNode HOST_GAME_MESSAGE;

    static {
        ObjectNode node =
                MAPPER.createObjectNode().put("command", "HostGame").put("target", "game");
        node.set("args", MAPPER.createArrayNode().add("scmp_007"));
        HOST_GAME_MESSAGE = node;
    }

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private ListAppender<ILoggingEvent> appender;
    private List<Logger> loggers;

    @BeforeEach
    void setUp() throws Exception {
        server = new ScriptedWebSocketServer();
        server.startAndAwait();
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new ListAppender<>();
        // The lifecycle also logs from lobby and adapter callback threads, so the default
        // ArrayList would be mutated while a test iterates it.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(context);
        appender.start();
        // One appender on both loggers, so entries land in a single list in emission order. That
        // is what makes the state line's position relative to teardown's own output observable;
        // teardown logs under its own class, not the lifecycle's.
        loggers =
                List.of(
                        context.getLogger(MockClientLifecycle.class),
                        context.getLogger(SessionTeardown.class),
                        context.getLogger(IceEventLogger.class));
        loggers.forEach(l -> l.addAppender(appender));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (appender != null) {
            appender.stop();
            loggers.forEach(l -> l.detachAppender(appender));
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // some paths close the underlying socket already
            }
        }
        server.stop(1000);
    }

    /**
     * Encodes every captured event as JSONL and parses it back.
     *
     * @return one parsed record per captured event
     */
    private List<JsonNode> records() {
        return appender.list.stream()
                .map(event -> new String(ENCODER.encode(event), StandardCharsets.UTF_8))
                .map(HarnessLogContractTest::parse)
                .toList();
    }

    /**
     * Parses one encoded record, failing the test if the encoder did not produce valid JSONL.
     *
     * @param line the encoded record, including its trailing newline
     * @return the parsed JSON object
     */
    private static JsonNode parse(final String line) {
        assertTrue(line.endsWith("\n"), "each record must be one line: " + line);
        try {
            return MAPPER.readTree(line);
        } catch (IOException e) {
            throw new AssertionError("record is not valid JSON: " + line, e);
        }
    }

    /**
     * The {@code message} values of every captured record, in order.
     *
     * @return the messages a harness would read
     */
    private List<String> messages() {
        return records().stream().map(record -> record.get("message").asText()).toList();
    }

    private MockClientLifecycle newLifecycle() {
        return newLifecycle(new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()));
    }

    private MockClientLifecycle newLifecycle(DummyIceAdapterConnection adapter) {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        return new MockClientLifecycle(
                MINIMAL_CONFIG,
                session,
                adapter,
                new DummyGameLauncher(MINIMAL_CONFIG),
                new DummyIceLauncher(MINIMAL_CONFIG),
                new SessionTeardown(lobby));
    }

    @Test
    void reportsTheInitialStateAtConstruction() {
        newLifecycle();

        assertTrue(
                messages().contains("state entry: CONNECTING"),
                "the initial state fires no entry hook, so it must be reported explicitly");
    }

    @Test
    void reportsEveryStateEntryInOrder() {
        MockClientLifecycle lifecycle = newLifecycle();

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());
        lifecycle.post(new GameExited(0));

        assertEquals(
                List.of(
                        "state entry: CONNECTING",
                        "state entry: IDLE",
                        "state entry: STARTING_GAME",
                        "state entry: HOSTING",
                        "state entry: PLAYING",
                        "state entry: TERMINATED"),
                messages().stream().filter(m -> m.startsWith("state entry:")).toList(),
                "a harness must be able to follow the whole walk from the log alone");
    }

    @Test
    void emitsNoDuplicateStateLineWhenStayingInState() {
        MockClientLifecycle lifecycle = newLifecycle();
        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));
        lifecycle.post(new HostGame(HOST_GAME_MESSAGE));
        lifecycle.post(new StartMatch());

        lifecycle.post(new Disconnected(null));

        assertEquals(
                ClientState.PLAYING, lifecycle.getState(), "sanity: the lobby-loss path stays put");
        assertEquals(
                1,
                messages().stream().filter(m -> m.equals("state entry: PLAYING")).count(),
                "a stay-in-state transition must not look like a fresh entry");
    }

    @Test
    void reportsStateLineBeforeThatStatesSideEffects() {
        MockClientLifecycle lifecycle = newLifecycle();

        lifecycle.shutdown();

        List<String> captured = messages();
        int stateLine = captured.indexOf("state entry: TERMINATED");
        int sideEffect = captured.indexOf("tearing down session");
        assertTrue(stateLine >= 0, "TERMINATED must be reported: " + captured);
        assertTrue(sideEffect >= 0, "sanity: teardown runs on TERMINATED entry: " + captured);
        assertTrue(
                stateLine < sideEffect,
                "the state line must precede that state's side effects: " + captured);
    }

    @Test
    void reportsPeerStateFromAnOrchestratedSession() {
        DummyIceAdapterConnection adapter =
                new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort());
        MockClientLifecycle lifecycle = newLifecycle(adapter);
        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        ObjectNode notification = MAPPER.createObjectNode();
        notification.set("params", MAPPER.createArrayNode().add(1).add(2).add(true));
        adapter.fireNotification("onConnected", notification);

        assertTrue(
                messages().contains("peer connected: local=1 remote=2 connected=true"),
                "a launched session must wire the adapter event logger, not merely define it");
    }

    @Test
    void reportsTerminatedOnceWhenLaunchFails() {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new DummyIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        new DummyGameLauncher(MINIMAL_CONFIG),
                        new DummyIceLauncher(MINIMAL_CONFIG, true),
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertEquals(
                List.of("state entry: CONNECTING", "state entry: IDLE", "state entry: TERMINATED"),
                messages().stream().filter(m -> m.startsWith("state entry:")).toList(),
                "a failed launch reports the state it lands in, and never entered STARTING_GAME");
    }

    @Test
    void reportsTheGameUidOnLaunch() {
        MockClientLifecycle lifecycle = newLifecycle();

        lifecycle.post(new WelcomeReceived(null));
        lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG));

        assertTrue(
                messages().contains("game launch: uid=12345 mod=faf name=Test Game Name"),
                "the uid is the join target a second instance needs, and reaches no other output");
    }

    /**
     * The component label comes from {@code LoggingSetup.configure}, which only a real process
     * calls, so this asserts the field is present and carries the rest of the record shape a
     * harness relies on. The label's own resolution is covered in the shared module.
     */
    @Test
    void recordsParseAsJsonlWithTheFieldsAHarnessReads() {
        newLifecycle();

        JsonNode record = records().get(0);
        assertTrue(record.hasNonNull("component"), "records are attributable to a component");
        assertEquals("INFO", record.get("level").asText(), "state lines are INFO, not DEBUG");
        assertTrue(record.hasNonNull("timestamp"), "records carry a timestamp");
        assertEquals("state entry: CONNECTING", record.get("message").asText());
    }
}
