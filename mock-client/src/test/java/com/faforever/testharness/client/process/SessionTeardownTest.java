package com.faforever.testharness.client.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.shared.process.SubprocessManager;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link SessionTeardown}. The subprocess handles are real {@link
 * SubprocessManager}s wrapping {@code sleep} children — {@code SubprocessManager} is final, and
 * observing real processes die is exactly the "no orphans" acceptance signal. The lobby handle is a
 * real {@link LobbyConnection}, never connected and with its close observed via its disconnect
 * listener, except in the tests of teardown's wait for the lobby's reply (#510), which connect it
 * to a {@link ScriptedWebSocketServer} that holds that reply. Only the adapter connection is a
 * recording stub.
 */
final class SessionTeardownTest {

    /** Teardown steps in observed order. */
    private final List<String> events = new CopyOnWriteArrayList<>();

    /** Sleeper children started by a test; terminated in {@link #tearDown()} as a safety net. */
    private final List<SubprocessManager> sleepers = new ArrayList<>();

    /** The scripted lobby a close test runs against, if one did; stopped in {@link #tearDown()}. */
    private ScriptedWebSocketServer lobbyServer;

    /** Lines of the loggers {@link #capture} attached it to, from any thread. */
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    /** Each logger {@link #capture} pinned, with the level it had; restored in tearDown. */
    private final Map<Logger, Level> pinned = new HashMap<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (SubprocessManager sleeper : sleepers) {
            sleeper.terminate();
        }
        if (lobbyServer != null) {
            lobbyServer.releaseCloseReplies();
            lobbyServer.stop(1000);
        }
        pinned.forEach(
                (logger, level) -> {
                    logger.detachAppender(logged);
                    logger.setLevel(level);
                });
    }

    /** Starts a long-sleeping child the test expects {@link SessionTeardown} to kill. */
    private SubprocessManager startSleeper() throws Exception {
        SubprocessManager sleeper =
                SubprocessManager.start(
                        new ProcessBuilder("sleep", "60"),
                        "teardown-test-child",
                        Duration.ofSeconds(2));
        sleepers.add(sleeper);
        return sleeper;
    }

    /** A real lobby connection (never connected) whose close is recorded via onDisconnect. */
    private LobbyConnection recordingLobby() {
        LobbyConnection lobby = new LobbyConnection(URI.create("ws://127.0.0.1:1"));
        lobby.onDisconnect(event -> events.add("lobby-closed"));
        return lobby;
    }

    /**
     * A real lobby connection, connected to a scripted lobby that holds its reply to the close
     * until the test releases it, or until {@link #tearDown()} does.
     */
    private LobbyConnection heldLobby() throws Exception {
        lobbyServer = new ScriptedWebSocketServer();
        lobbyServer.startAndAwait();
        LobbyConnection lobby = new LobbyConnection(lobbyServer.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        lobbyServer.awaitFirstClient();
        lobbyServer.holdCloseReplies();
        return lobby;
    }

    /**
     * Captures {@code type}'s lines at DEBUG, whatever level the build or an earlier class left.
     */
    private void capture(final Class<?> type) {
        if (!logged.isStarted()) {
            logged.list = new CopyOnWriteArrayList<>();
            logged.start();
        }
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        pinned.put(logger, logger.getLevel());
        logger.setLevel(Level.DEBUG);
        logger.addAppender(logged);
    }

    /** The messages {@link #capture} has seen, in order. */
    private List<String> loggedMessages() {
        return logged.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Teardown returns only once the lobby has answered its close (#510), so the close's own line
     * is logged before it returns. The scripted lobby holds that reply: a teardown that waited only
     * for its frame to be sent would return at once and leave the line to land after it.
     */
    @Test
    void teardownWaitsForTheLobbysReplyToItsClose() throws Exception {
        capture(LobbyConnection.class);
        SessionTeardown teardown = new SessionTeardown(heldLobby());
        CountDownLatch returned = new CountDownLatch(1);
        AtomicBoolean closeLoggedFirst = new AtomicBoolean();
        Thread runner =
                new Thread(
                        () -> {
                            teardown.run();
                            closeLoggedFirst.set(
                                    loggedMessages().stream()
                                            .anyMatch(
                                                    m ->
                                                            m.startsWith("lobby WebSocket closed:")
                                                                    && m.endsWith(
                                                                            "bucket=LOCAL_CLOSE")));
                            returned.countDown();
                        },
                        "teardown-under-test");
        runner.start();

        lobbyServer.awaitHeldClose(5, TimeUnit.SECONDS);
        assertFalse(
                returned.await(300, TimeUnit.MILLISECONDS),
                "teardown returned before the lobby answered its close");
        lobbyServer.releaseCloseReplies();

        assertTrue(returned.await(5, TimeUnit.SECONDS), "teardown never returned once answered");
        assertTrue(
                closeLoggedFirst.get(),
                "the close's own line must be logged before teardown returns: " + loggedMessages());
    }

    /**
     * A lobby that never answers costs teardown {@code LOBBY_CLOSE_TIMEOUT}, 5 s, and a WARN that
     * names the wait (#510), rather than a teardown that never returns.
     */
    @Test
    void aLobbyThatNeverAnswersCostsTheBoundAndAWarn() throws Exception {
        capture(SessionTeardown.class);
        SessionTeardown teardown = new SessionTeardown(heldLobby());

        teardown.run();

        assertEquals(
                List.of(
                        "lobby close did not complete cleanly: the close handshake did not finish"
                                + " within 5 s"),
                logged.list.stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList());
    }

    /** One call kills both processes, then closes the connections, adapter RPC before lobby. */
    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason =
                    "POSIX-only: spawns a shell script or POSIX utility (CONTRIBUTING.md § 3)")
    void runTerminatesProcessesThenClosesConnections() throws Exception {
        SubprocessManager game = startSleeper();
        SubprocessManager adapter = startSleeper();

        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerGameProcess(game);
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(
                new RecordingAdapterConnection(
                        () ->
                                events.add(
                                        game.isAlive() || adapter.isAlive()
                                                ? "rpc-closed-before-processes-died"
                                                : "rpc-closed-after-processes")));

        teardown.run();

        assertFalse(game.isAlive(), "game process must be terminated — the no-orphans signal");
        assertFalse(adapter.isAlive(), "adapter process must be terminated");
        assertEquals(
                List.of("rpc-closed-after-processes", "lobby-closed"),
                events,
                "documented order: processes first, then adapter RPC, then lobby");
    }

    /** A second run() call is a no-op — no step executes twice. */
    @Test
    void secondRunIsNoOp() {
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));

        teardown.run();
        teardown.run();

        assertEquals(List.of("rpc-closed", "lobby-closed"), events);
    }

    /**
     * Concurrent run() calls execute the sequence exactly once — the losing thread blocks on the
     * monitor, then no-ops. The monitor serializes the threads, so a broken guard would be caught
     * probabilistically; the test's main job is pinning the documented concurrent-no-op contract.
     */
    @Test
    void concurrentRunsExecuteTeardownOnce() throws Exception {
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));
        teardown.registerAfterGameStep(() -> events.add("step"));

        CountDownLatch go = new CountDownLatch(1);
        Runnable call =
                () -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    teardown.run();
                };
        Thread first = new Thread(call);
        Thread second = new Thread(call);
        first.start();
        second.start();
        go.countDown();
        first.join();
        second.join();

        assertEquals(
                List.of("step", "rpc-closed", "lobby-closed"),
                events,
                "each teardown step, the owner's included, must fire exactly once across concurrent"
                        + " callers");
    }

    /**
     * The owner's step runs once the game is down and before the adapter is touched (#454): where
     * the lifecycle reports the game's end, with the lobby still open.
     */
    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason =
                    "POSIX-only: spawns a shell script or POSIX utility (CONTRIBUTING.md § 3)")
    void theAfterGameStepRunsBetweenTheGameAndTheAdapter() throws Exception {
        SubprocessManager game = startSleeper();
        SubprocessManager adapter = startSleeper();
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerGameProcess(game);
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));
        teardown.registerAfterGameStep(
                () ->
                        events.add(
                                "step: game "
                                        + (game.isAlive() ? "alive" : "down")
                                        + ", adapter "
                                        + (adapter.isAlive() ? "alive" : "down")));

        teardown.run();

        assertEquals(
                List.of("step: game down, adapter alive", "rpc-closed", "lobby-closed"), events);
    }

    /** A step that throws is the owner's defect, logged, and the rest of teardown still runs. */
    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason =
                    "POSIX-only: spawns a shell script or POSIX utility (CONTRIBUTING.md § 3)")
    void aThrowingAfterGameStepStillTearsDownTheRest() throws Exception {
        SubprocessManager adapter = startSleeper();
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(new RecordingAdapterConnection(() -> events.add("rpc-closed")));
        teardown.registerAfterGameStep(
                () -> {
                    throw new IllegalStateException("defect in the owner's step");
                });

        teardown.run();

        assertFalse(adapter.isAlive(), "the adapter must still be terminated");
        assertEquals(List.of("rpc-closed", "lobby-closed"), events);
    }

    /** An idle, lobby-only session tears down cleanly — unregistered handles are skipped. */
    @Test
    void runToleratesUnregisteredHandles() {
        new SessionTeardown(recordingLobby()).run();

        assertEquals(List.of("lobby-closed"), events);
    }

    /** A step that throws is logged and skipped; the rest of the sequence still runs. */
    @Test
    void failingAdapterCloseStillClosesLobby() {
        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterRpc(
                new RecordingAdapterConnection(
                        () -> {
                            throw new RuntimeException("adapter socket already broken");
                        }));

        teardown.run();

        assertEquals(List.of("lobby-closed"), events);
    }

    /**
     * A never-connected adapter RPC connection (as every existing test above uses) reports {@link
     * IceAdapterConnection#isOpen()} {@code false} — {@link SessionTeardown} must skip the quit RPC
     * and terminate the adapter the same way it always has.
     */
    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason =
                    "POSIX-only: spawns a shell script or POSIX utility (CONTRIBUTING.md § 3)")
    void rpcNeverOpenedSkipsQuitAndTerminatesAsBefore() throws Exception {
        SubprocessManager adapter = startSleeper();
        RecordingAdapterConnection rpc =
                new RecordingAdapterConnection(() -> events.add("rpc-closed"));

        SessionTeardown teardown = new SessionTeardown(recordingLobby());
        teardown.registerAdapterProcess(adapter);
        teardown.registerAdapterRpc(rpc);

        assertFalse(rpc.isOpen(), "a never-connected connection must report closed");
        teardown.run();

        assertFalse(rpc.quitCalled, "quit must not be sent over an RPC connection that isn't open");
        assertFalse(adapter.isAlive(), "adapter must still be terminated via SIGTERM/SIGKILL");
    }

    /**
     * {@link SessionTeardown#signalled()} reads the supplier it was built with (#438), and a
     * teardown built without one never reports a signal.
     */
    @Test
    void signalledFollowsItsSupplier() {
        AtomicBoolean flag = new AtomicBoolean();
        SessionTeardown teardown = new SessionTeardown(recordingLobby(), flag::get);

        assertFalse(teardown.signalled());
        flag.set(true);
        assertTrue(teardown.signalled());
        assertFalse(new SessionTeardown(recordingLobby()).signalled(), "no supplier, no signal");
    }

    /** Adapter-connection stub: never connects, runs the given action when closed. */
    private static final class RecordingAdapterConnection extends IceAdapterConnection {
        private final Runnable onClose;
        private volatile boolean quitCalled;

        RecordingAdapterConnection(final Runnable onClose) {
            super(1);
            this.onClose = onClose;
        }

        @Override
        public CompletableFuture<JsonNode> call(final String method, final Object... params) {
            if ("quit".equals(method)) {
                quitCalled = true;
            }
            return super.call(method, params);
        }

        @Override
        public void close() {
            onClose.run();
        }
    }
}
