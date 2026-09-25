package com.faforever.testharness.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.faforever.testharness.client.lobby.GameConfig;
import com.faforever.testharness.client.lobby.LobbyConnection;
import com.faforever.testharness.client.lobby.LobbySession;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.SessionTeardown;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.slf4j.LoggerFactory;

/**
 * The adapter bring-up gives up when the adapter process dies (WBS-3.1.3.3-fix, #266).
 *
 * <p>{@code launchGame} waits for the adapter's JSON-RPC socket from inside a transition action,
 * and {@code StateMachine.receiveEvent} is synchronized, so everything arriving meanwhile queues
 * behind it. On the happy path that is invisible. It matters when the adapter never binds: the
 * connect retries for its full budget — 20 s since WBS-3.1.2.7 widened it — and a failed launch is
 * noticed that much late, with {@code AdapterExited} sitting queued for the same window.
 *
 * <p>The case the budget exists for is an adapter that dies on the way up, which is what a usage
 * error produces — and it exits {@code 0} while doing so, so nothing else about the run says it is
 * gone. Racing the connect against the process's own exit future ends the wait then and there.
 *
 * <p>The connect here <em>never</em> completes, so without the race this test does not fail — it
 * hangs, which is why the class carries a {@link Timeout}. A budget-shaped wait would have been the
 * weaker test: it would pass on a slow machine for the wrong reason.
 *
 * <p>The same bring-up decides whether the launch failed (WBS-3.1.3.3-fix, #437), which {@code run}
 * reports in its exit code, so the tests below also pin when that verdict is set and when it is
 * not.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class AdapterConnectRaceTest {

    /** Comfortably shorter than the real 20 s connect budget, so a regression is unambiguous. */
    private static final int GIVE_UP_SECONDS = 10;

    /**
     * Upper bound on the raced wait. The fix takes this from the full connect budget (~20s) to the
     * process exit (~10ms), so a few hundred milliseconds is generous for the fixed behaviour and
     * still catches a regression that reintroduces even one retry delay.
     */
    private static final long RACE_BOUND_MILLIS = 500;

    private static final MockClientConfig MINIMAL_CONFIG =
            new MockClientConfig(
                    URI.create("wss://ws.faforever.xyz"),
                    URI.create("https://hydra.faforever.xyz/oauth2/token"),
                    URI.create("https://hydra.faforever.xyz/oauth2/auth"),
                    URI.create("http://127.0.0.1"),
                    "openid offline lobby",
                    "95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                    Path.of("/nonexistent/test-refresh-token"),
                    Optional.empty(),
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
                    0,
                    0,
                    -1);

    private static final GameConfig MINIMAL_GAME_CONFIG =
            new GameConfig(
                    42,
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

    private ScriptedWebSocketServer server;
    private LobbyConnection lobby;
    private DummyIceLauncher iceLauncher;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;
    private Level originalLevel;

    @BeforeEach
    void setUp() throws Exception {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // The failure is logged from the process-exit callback, not the test thread.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
        // A launch failure teardown caused is logged at DEBUG. Set here rather than inherited from
        // the Gradle task's LOG_LEVEL, so the assertion on it also holds from an IDE.
        originalLevel = root.getLevel();
        root.setLevel(Level.DEBUG);

        server = new ScriptedWebSocketServer();
        server.startAndAwait();
        lobby = new LobbyConnection(server.uri());
        lobby.connect().get(5, TimeUnit.SECONDS);
        server.awaitFirstClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (appender != null) {
            root.setLevel(originalLevel);
            appender.stop();
            root.detachAppender(appender);
        }
        if (iceLauncher != null && iceLauncher.getSubprocess() != null) {
            iceLauncher.getSubprocess().terminate();
        }
        if (lobby != null) {
            try {
                lobby.close().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Some paths have already closed it.
            }
        }
        server.stop(1000);
    }

    /**
     * An adapter that exits immediately, against a connect that never completes. Without the race
     * the transition action waits for a socket that will never open; with it, the process's death
     * is what ends the wait, and the failed transition drives the FSM to TERMINATED.
     */
    @Test
    @EnabledOnOs(
            value = {OS.LINUX, OS.MAC},
            disabledReason =
                    "POSIX-only: spawns a shell script or POSIX utility (CONTRIBUTING.md § 3)")
    void anAdapterThatDiesEndsTheConnectWaitAtOnce() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        // `true` exits 0 immediately — the same shape as the adapter's own usage-error exit, which
        // is what makes this the case the race exists for rather than a contrived kill.
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG, false, new ProcessBuilder("true"));
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new NeverConnectingIceAdapterConnection(MINIMAL_CONFIG.iceAdapterRpcPort()),
                        new DummyGameLauncher(MINIMAL_CONFIG),
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        // Sampled the moment TERMINATED commits (#437). A read after the wait could not tell a
        // verdict written before the commit from one written after it, which is where this
        // adapter's own exit lands, through logAdapterExitAfterTeardown.
        CompletableFuture<Boolean> failedAtCommit =
                lifecycle
                        .stateReached(ClientState.TERMINATED)
                        .thenApply(reached -> lifecycle.launchFailed());
        long start = System.nanoTime();
        // Posted off the test thread. post() is machine.receiveEvent, which is synchronous and
        // synchronized, so on a regression the test thread blocks inside this call and every
        // assertion below is unreachable — the run dies on the class @Timeout after 30s with the
        // informative assertion buried as a suppressed exception. Off-thread, the get() below is
        // the primary, self-describing failure.
        CompletableFuture<Void> posted =
                CompletableFuture.runAsync(
                        () -> lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG)));

        lifecycle.stateReached(ClientState.TERMINATED).get(GIVE_UP_SECONDS, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        posted.get(GIVE_UP_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        // Pinned to the mechanism, not just to the outcome. Without this the test passes for any
        // fast launch failure — verified: a binary that cannot be exec'd makes iceLauncher.start()
        // throw before the race is entered, and the old assertions still went green, so a machine
        // without `true` on PATH got a silent pass rather than a failure.
        ILoggingEvent cause =
                findEvent(
                        e ->
                                e.getFormattedMessage()
                                        .contains(
                                                "before its JSON-RPC port accepted a connection"));
        assertTrue(
                cause.getFormattedMessage()
                        .contains("before its JSON-RPC port accepted a connection"),
                "the wait must end because the process died, not because the launch failed; got: "
                        + cause.getFormattedMessage());
        // Milliseconds, not truncated seconds: the fix takes this from ~20s to ~10ms, and a whole-
        // second bound could only tell "under 10s" from "10s or more" — blind to a partial
        // regression such as a race that only fires after one retry delay.
        assertTrue(
                elapsedMillis < RACE_BOUND_MILLIS,
                "the wait must end with the process, not with the connect budget; took "
                        + elapsedMillis
                        + "ms");
        assertTrue(
                failedAtCommit.get(GIVE_UP_SECONDS, TimeUnit.SECONDS),
                "an adapter dying before it accepts fails the launch before TERMINATED commits");
    }

    /**
     * A launch the harness's own teardown cut short is not a failed launch (#437). The test thread
     * runs teardown mid-bring-up, as the CLI's shutdown hook does on a Ctrl-C, so the adapter dies
     * under the race above and the launch fails for a reason the harness caused.
     */
    @Test
    void aTeardownDuringBringUpIsNotALaunchFailure() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        // The default `sort` stays alive until something kills it, here the teardown.
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        CountDownLatch connecting = new CountDownLatch(1);
        SessionTeardown teardown = new SessionTeardown(lobby);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new NeverConnectingIceAdapterConnection(
                                MINIMAL_CONFIG.iceAdapterRpcPort(), connecting),
                        new DummyGameLauncher(MINIMAL_CONFIG),
                        iceLauncher,
                        teardown);

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        CompletableFuture<Void> posted =
                CompletableFuture.runAsync(
                        () -> lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG)));
        // connect() runs only once the adapter is registered for teardown, so teardown reaches it.
        assertTrue(
                connecting.await(GIVE_UP_SECONDS, TimeUnit.SECONDS),
                "the bring-up never reached the adapter connect");
        teardown.run();

        lifecycle.stateReached(ClientState.TERMINATED).get(GIVE_UP_SECONDS, TimeUnit.SECONDS);
        posted.get(GIVE_UP_SECONDS, TimeUnit.SECONDS);

        assertEquals(ClientState.TERMINATED, lifecycle.getState());
        assertFalse(lifecycle.launchFailed(), "a launch teardown cut short is not a finding");
        // Pinned to the mechanism: the race failed the launch, and it was seen as teardown's.
        ILoggingEvent cause =
                findEvent(
                        e ->
                                e.getFormattedMessage()
                                        .contains(
                                                "before its JSON-RPC port accepted a connection"));
        assertEquals(Level.DEBUG, cause.getLevel(), "got: " + cause.getFormattedMessage());
    }

    /**
     * An interrupted bring-up is a failed launch like any other (#437). Nothing in production
     * interrupts the thread that runs it today, which is why this is the only test that reaches
     * that catch arm.
     *
     * <p>Worth knowing if something ever does: the arm restores the interrupt flag before it
     * throws, so TERMINATED's entry hook runs the whole teardown on an interrupted thread, where
     * every bounded wait returns at once. SIGTERM, grace and SIGKILL collapse into one kill and the
     * lobby close is not awaited. Nothing is leaked, which is what the last assertion pins, but the
     * shutdown is abrupt.
     */
    @Test
    void anInterruptedBringUpIsALaunchFailure() throws Exception {
        LobbySession session = new LobbySession(lobby, "uid-fixture", "1.0.0", "mock-client-test");
        iceLauncher = new DummyIceLauncher(MINIMAL_CONFIG);
        CountDownLatch connecting = new CountDownLatch(1);
        MockClientLifecycle lifecycle =
                new MockClientLifecycle(
                        MINIMAL_CONFIG,
                        session,
                        new NeverConnectingIceAdapterConnection(
                                MINIMAL_CONFIG.iceAdapterRpcPort(), connecting),
                        new DummyGameLauncher(MINIMAL_CONFIG),
                        iceLauncher,
                        new SessionTeardown(lobby));

        lifecycle.post(new WelcomeReceived(SessionFixture.SESSION));
        // A thread of its own rather than the common pool, so the interrupt reaches nothing else.
        Thread launch =
                new Thread(() -> lifecycle.post(new LaunchGame(MINIMAL_GAME_CONFIG)), "launch");
        launch.start();
        assertTrue(
                connecting.await(GIVE_UP_SECONDS, TimeUnit.SECONDS),
                "the bring-up never reached the adapter connect");
        launch.interrupt();

        lifecycle.stateReached(ClientState.TERMINATED).get(GIVE_UP_SECONDS, TimeUnit.SECONDS);
        launch.join(TimeUnit.SECONDS.toMillis(GIVE_UP_SECONDS));

        assertTrue(lifecycle.launchFailed(), "an interrupted bring-up is a failed launch");
        ILoggingEvent cause =
                findEvent(
                        e ->
                                e.getFormattedMessage()
                                        .contains(
                                                "Could not connect or setup the ICE adapter"
                                                        + " (interrupted)"));
        assertEquals(Level.WARN, cause.getLevel(), "got: " + cause.getFormattedMessage());
        // A bounded wait, not an isAlive() probe: terminate() returns without awaiting anything on
        // an interrupted thread, so the kill it just sent is still in flight. The wait failing is
        // the assertion that teardown reaped the adapter at all.
        iceLauncher.getSubprocess().onExit().get(GIVE_UP_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Finds the first captured event matching {@code predicate}, failing with the captured log if
     * there is none.
     *
     * @param predicate what to look for
     * @return the matching event
     */
    private ILoggingEvent findEvent(final java.util.function.Predicate<ILoggingEvent> predicate) {
        return appender.list.stream()
                .filter(predicate)
                .findFirst()
                .orElseGet(
                        () -> {
                            throw new AssertionError(
                                    "no matching log event; captured: " + appender.list);
                        });
    }

    /**
     * A connection whose connect never resolves, so only the process exit can end the wait. It
     * counts {@code connecting} down when the connect starts, which is after the adapter has been
     * registered for teardown.
     */
    private static final class NeverConnectingIceAdapterConnection extends IceAdapterConnection {

        private final CountDownLatch connecting;

        NeverConnectingIceAdapterConnection(final int port) {
            this(port, new CountDownLatch(1));
        }

        NeverConnectingIceAdapterConnection(final int port, final CountDownLatch connecting) {
            super(port);
            this.connecting = connecting;
        }

        @Override
        public CompletableFuture<Void> connect() {
            connecting.countDown();
            return new CompletableFuture<>();
        }
    }
}
