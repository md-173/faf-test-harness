package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.faforever.testharness.client.Main;
import com.faforever.testharness.client.config.IceAdapterSettings;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.client.process.FakeAdapterStub;
import com.faforever.testharness.client.process.FakeIceAdapter;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the real {@code run} ends, when a signal stops it and when it stops on its own
 * (WBS-3.1.5.2-fix, #297, and WBS-3.1.3.2-fix, #446), a lobby it cannot reach (#455) and a {@code
 * game_launch} it cannot use (#457) among them.
 *
 * <p>{@code mock-client/README.md} documents that {@code Ctrl-C} or {@code SIGTERM} closes the
 * WebSocket cleanly and exits 130 or 143. {@code SignalExitCodeEndToEndTest} pins the JDK behaviour
 * underneath, through a stand-in. This drives the product path: the real {@code Main}, the real
 * shutdown hook and teardown, and what an operator reads in the log. The child JVM runs {@code run}
 * against a scripted lobby in this one; an access-token file and a fixed unique id stand in for the
 * OAuth exchange and {@code faf-uid}, and reaching IDLE starts no subprocess, so nothing here needs
 * the network or a real binary. The one case that hosts runs {@code FakeIceAdapter} and a game
 * script as its subprocesses (#438, #454).
 *
 * <p>The child's console goes to a file, and its records are read from its JSONL once it has
 * exited: {@code SignalExitCodeEndToEndTest} explains why a pipe loses the last lines. Budgets are
 * generous because a cold child JVM competes with this one for the machine. They bound a failure; a
 * healthy case finishes in a few seconds.
 *
 * <p>Windows has no {@code SIGTERM} in this sense, and the harness targets Linux and macOS.
 */
@DisabledOnOs(OS.WINDOWS)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
final class RunShutdownEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** How long the child gets for each step: reaching the lobby, reaching IDLE, exiting. */
    private static final int STEP_BUDGET_SECONDS = 60;

    /** The hook's line, which must mark a signal and nothing else (#446). */
    private static final String SIGNAL_LINE = "shutdown signal received; tearing down session";

    /** How every verdict line {@code RunCommand} logs ends, bar the lobby drop's. */
    private static final String VERDICT_ENDING = "reporting it in this run's exit code";

    /** The lobby drop's verdict line, the one that does not end that way. */
    private static final String LOBBY_DROP_VERDICT = "lobby connection dropped unexpectedly";

    /** The lifecycle's WARN when a failed login ends the session (#455). */
    private static final String HANDSHAKE_WARN = "Handshake could not be completed";

    /** An error notice, as faf-server sends a banned player before it closes the login (#473). */
    private static final String BAN_NOTICE =
            "{\"command\":\"notice\",\"style\":\"error\","
                    + "\"text\":\"You are banned from FAF forever.\\nReason: rig\"}";

    /** Logged just before the main thread parks: the session is idle. */
    private static final String IDLE_LINE = "mock client idle as player";

    /** SIGINT is signal 2, so bit 1 of a {@code SigIgn} mask. */
    private static final long SIGINT_MASK = 1L << 1;

    private static final String WELCOME =
            "{\"command\":\"welcome\",\"me\":{\"id\":7,\"login\":\"MockPlayer\"},"
                    + "\"current_time\":\"2026-09-23T00:00:00Z\"}";

    /** A custom game's launch whose args carry a slash flag the client does not allow (#457). */
    private static final String UNUSABLE_GAME_LAUNCH =
            "{\"command\":\"game_launch\",\"uid\":4243,\"mod\":\"faf\",\"name\":\"unusable\","
                    + "\"game_type\":\"custom\",\"rating_type\":\"global\",\"init_mode\":0,"
                    + "\"args\":[\"/numgames\",0,\"/newflag\"]}";

    /** A custom game's launch, carrying every field the handler requires. */
    private static final String GAME_LAUNCH =
            "{\"command\":\"game_launch\",\"uid\":4242,\"mod\":\"faf\",\"name\":\"shutdown"
                    + " test\",\"game_type\":\"custom\",\"rating_type\":\"global\","
                    + "\"init_mode\":0}";

    /** The server's instruction to host, which takes a launched session to HOSTING. */
    private static final String HOST_GAME =
            "{\"command\":\"HostGame\",\"target\":\"game\",\"args\":[\"scmp_007\"]}";

    @TempDir private Path dir;

    private ScriptedWebSocketServer lobby;

    private Process child;

    @BeforeEach
    void setUp() throws Exception {
        lobby = new ScriptedWebSocketServer();
        lobby.startAndAwait();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (child != null) {
            // A SIGKILL runs no shutdown hook, so a test that failed before its signal would leave
            // the child's adapter and game running. Kill them first, while they are its
            // descendants.
            child.descendants().forEach(ProcessHandle::destroyForcibly);
            child.destroyForcibly();
            child.waitFor(STEP_BUDGET_SECONDS, TimeUnit.SECONDS);
        }
        lobby.stop(1000);
    }

    /** SIGTERM while idle: exit 143, the signal named once, no verdict, a clean close. */
    @Test
    void aSigtermWhileIdleExits143AndClosesCleanly() throws Exception {
        startRunAndReachIdle();

        child.destroy(); // SIGTERM on Linux and macOS

        assertExitCode(143);
        assertEndedCleanlyBySignal();
    }

    /**
     * SIGINT while idle, as {@code Ctrl-C} sends it: exit 130, the same contract.
     *
     * <p>Skipped when this JVM ignores SIGINT, and wherever that cannot be read. A JVM that starts
     * with SIGINT ignored never installs its handler, a child inherits the ignored disposition, and
     * a Gradle started from a background job in a non-interactive shell has one. The signal would
     * then do nothing and the case would fail on its timeout, not on {@code run}.
     */
    @Test
    void aSigintWhileIdleExits130AndClosesCleanly() throws Exception {
        assumeTrue(
                sigintReachesAChild(),
                "SIGINT is ignored in this JVM, so a child would inherit it and never run its hook,"
                        + " or this platform has no /proc/self/status to tell");
        startRunAndReachIdle();

        Process kill =
                new ProcessBuilder("sh", "-c", "kill -INT " + child.pid())
                        .redirectErrorStream(true)
                        .start();
        assertEquals(0, kill.waitFor(), "kill -INT failed");

        assertExitCode(130);
        assertEndedCleanlyBySignal();
    }

    /**
     * A lobby that refuses the connection ends the run with {@code 70} and one ERROR naming the
     * failure's type, its root cause and the lobby, where it used to read {@code lobby session
     * failed: null} (WBS-3.1.1.4-fix, #455). The lobby's disconnect ends the session before the
     * handshake's failure is posted, and neither that failure nor the framework may then warn about
     * it. The port is bound and never listened on, so the connect is refused and nothing can take
     * it meanwhile. A refused connect has a root cause because {@code java.net.http} retries it on
     * the channel the refusal closed; which class that is, the JDK chooses, so it is not asserted.
     */
    @Test
    void aRefusedConnectExits70NamingTheLobbyOnce() throws Exception {
        String lobbyUrl;
        try (Socket reserved = new Socket()) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0));
            lobbyUrl = "ws://127.0.0.1:" + reserved.getLocalPort();
            startRun(URI.create(lobbyUrl), List.of());

            assertExitCode(ExitCodes.RUNTIME);
        }

        List<JsonNode> records = records();
        List<String> errors = messagesAt(records, "ERROR");
        List<String> warnings = messagesAt(records, "WARN");
        String error = "lobby session with " + lobbyUrl + " failed: ";
        assertEquals(1, errors.size(), "exactly one ERROR: " + messages(records));
        assertTrue(
                errors.get(0).startsWith(error + "ConnectException"),
                "the ERROR must name the failure and the lobby: " + errors);
        String failure = errors.get(0).substring(error.length());
        assertTrue(
                failure.contains(", caused by "),
                "the ERROR must name the failure's root cause: " + errors);
        assertTrue(
                warnings.contains("lobby WebSocket connect to " + lobbyUrl + " failed: " + failure),
                "the connect WARN must name the same failure and the lobby: " + warnings);
        assertTrue(
                errors.stream().noneMatch(m -> m.endsWith("null"))
                        && warnings.stream().noneMatch(m -> m.endsWith("null")),
                "no line may name the failure as null: " + messages(records));
        assertTrue(
                warnings.stream()
                        .noneMatch(
                                m ->
                                        m.startsWith("No matching transitions")
                                                || m.startsWith(
                                                        "Handshake could not be completed")),
                "nothing may warn about the handshake once the session has ended: " + warnings);
        assertEquals(0, count(records, SIGNAL_LINE), "no signal was sent: " + messages(records));
        assertEquals(List.of(), verdicts(records), "a session that never opened names no verdict");
    }

    /**
     * An {@code invalid} in answer to {@code auth} ends the run with {@code 70} at once, with one
     * ERROR naming it (#473), where the run used to wait out its 45 s setup timeout and then blame
     * the timeout. faf-server sends it when handling the login raised, a refused {@code unique_id}
     * included, and closes the connection straight after.
     */
    @Test
    void anInvalidAnswerToAuthExits70NamingIt() throws Exception {
        startRunAndReachAuth(List.of());

        lobby.broadcastText("{\"command\":\"invalid\"}");
        lobby.closeAllClean(1000, "");

        assertExitCode(ExitCodes.RUNTIME);
        assertLoginEndedAtOnce(
                "the lobby answered the login with invalid, a server-side error such as a refused"
                        + " unique_id or a token it could not read",
                List.of());
    }

    /**
     * A lobby that closes the connection before {@code welcome} ends the run with {@code 70} at
     * once, with one ERROR naming the close (#473), as faf-server ends a banned player's login: an
     * error {@code notice}, then a Close frame 1000 with no reason.
     */
    @Test
    void aBannedLoginExits70NamingTheClose() throws Exception {
        startRunAndReachAuth(List.of());

        lobby.broadcastText(BAN_NOTICE);
        lobby.closeAllClean(1000, "");

        assertExitCode(ExitCodes.RUNTIME);
        assertLoginEndedAtOnce(
                "the lobby closed the connection before welcome (code 1000)",
                List.of(
                        "unhandled lobby command 'notice'"
                                + " (will be silent for subsequent occurrences)"));
    }

    /**
     * A lobby lost without a Close frame after welcome ends the run with {@code 70} and the lobby
     * drop as its verdict (#473). The JDK reports such a drop as a close with code 1006, which used
     * to be read as a clean close, so the run exited {@code 0} as if the lobby had ended the
     * session.
     */
    @Test
    void aLobbyDroppedAfterWelcomeExits70() throws Exception {
        startRunAndReachIdle();

        lobby.abruptlyTerminate();

        assertExitCode(ExitCodes.RUNTIME);
        List<JsonNode> records = records();
        assertEquals(List.of(LOBBY_DROP_VERDICT), verdicts(records), "the drop is the verdict");
        assertNoErrors(records);
        assertEquals(0, count(records, SIGNAL_LINE), "no signal was sent: " + messages(records));
    }

    /**
     * A {@code game_launch} the client cannot use ends the run with {@code 70} (WBS-3.1.1.6-fix,
     * #457), where the frame used to be dropped with a WARN and the run left idle until killed. One
     * line names what is wrong with the frame, and it is the only WARN besides the launch's verdict
     * that follows, so neither the handler nor the validator may add a cause line of its own.
     * Nothing is logged at ERROR, and teardown closes the lobby cleanly without a {@code GameState
     * Ended}: a refused frame starts no launch, and #462 reports only a launch that started.
     */
    @Test
    void aGameLaunchTheClientCannotUseExits70() throws Exception {
        startRunAndReachIdle();

        lobby.broadcastText(UNUSABLE_GAME_LAUNCH);

        assertExitCode(ExitCodes.RUNTIME);
        List<JsonNode> records = records();
        assertEquals(
                List.of(
                        "Could not read the game_launch frame (game_launch.args contains unknown"
                                + " slash-flag: /newflag)"),
                messages(records).stream().filter(m -> m.startsWith("Could not")).toList(),
                "one line must name what is wrong with the frame: " + messages(records));
        assertEquals(
                List.of(
                        "the ICE adapter or game never came up; reporting it in this run's exit"
                                + " code"),
                verdicts(records),
                "exactly one verdict, the launch's: " + messages(records));
        assertEquals(
                List.of(
                        "Could not read the game_launch frame (game_launch.args contains unknown"
                                + " slash-flag: /newflag)",
                        "the ICE adapter or game never came up; reporting it in this run's exit"
                                + " code"),
                messagesAt(records, "WARN"),
                "the cause line and the verdict, nothing else: " + messages(records));
        assertEquals(0, count(records, SIGNAL_LINE), "no signal was sent: " + messages(records));
        assertNoErrors(records);
        assertEquals(
                1000,
                lobby.awaitClose(STEP_BUDGET_SECONDS, TimeUnit.SECONDS),
                "teardown must close the lobby cleanly");
        assertEquals(0, gameStateEndedSent(), "a refused game_launch started no launch to report");
    }

    /**
     * A run that ends on its own names no signal, whatever its code (#446): a launch that never
     * came up, because the adapter binary does not exist, exits 70 with its cause and its verdict
     * and without the signal line. Teardown closes the lobby before {@code call()} returns, and the
     * hook that {@code Main}'s {@code System.exit} starts found that close still unechoed, so the
     * line used to follow the verdict. Also pins #437's 70 against the real process, and #462: the
     * lobby still gets one {@code GameState Ended} before the close, as the real client sends after
     * every {@code game_launch} it acted on.
     */
    @Test
    void aLaunchThatNeverCameUpExits70AndNamesNoSignal() throws Exception {
        startRunAndReachIdle();

        lobby.broadcastText(GAME_LAUNCH);

        assertExitCode(ExitCodes.RUNTIME);
        List<JsonNode> records = records();
        assertEquals(0, count(records, SIGNAL_LINE), "no signal was sent: " + messages(records));
        assertEquals(
                List.of(
                        "the ICE adapter or game never came up; reporting it in this run's exit"
                                + " code"),
                verdicts(records),
                "exactly one verdict, the launch's: " + messages(records));
        assertTrue(
                messages(records).stream()
                        .anyMatch(m -> m.startsWith("Could not launch the ICE adapter")),
                "the cause must be named: " + messages(records));
        assertTrue(
                messages(records).stream()
                        .noneMatch(m -> m.startsWith("failed to send GameState Ended")),
                "the frame must go out cleanly: " + messages(records));
        assertNoErrors(records);
        assertEquals(
                1000,
                lobby.awaitClose(STEP_BUDGET_SECONDS, TimeUnit.SECONDS),
                "teardown must close the lobby cleanly");
        assertEquals(1, gameStateEndedSent(), "a failed launch still reports the game's end");
    }

    /**
     * SIGTERM while HOSTING, with an adapter and a game running (#438, #454): exit 143, the signal
     * named once, no verdict and no adapter finding, and the game's end reported to the lobby
     * before its close.
     *
     * <p>The only automated check that {@code call()} hands its shutdown flag to the teardown. On a
     * SIGTERM, {@code SubprocessRegistry}'s hook kills the adapter at once while the run's own hook
     * tears down, and this game takes over a second to die, so by the time teardown judges the
     * adapter it is dead with 143. Only that flag keeps it from reading as a lost adapter: a
     * teardown built without it logs {@code ICE adapter exited abnormally}. The adapter is {@code
     * FakeIceAdapter} run through the real launcher, answering every call.
     *
     * <p>Nothing-at-ERROR is not asserted here: with a game running, teardown can log {@code Error
     * reading subprocess stream} (#361), which is not this card's.
     */
    @Test
    void aSigtermWhileHostingNamesNoAdapterVerdictAndReportsTheGame() throws Exception {
        IceAdapterSettings adapter =
                FakeAdapterStub.create(dir, FakeIceAdapter.Mode.FULL).settings();
        startRunAndReachIdle(
                List.of(
                        "--ice-adapter-binary-path=" + adapter.binaryPath(),
                        "--ice-adapter-rpc-port=" + adapter.rpcPort(),
                        "--ice-adapter-gpg-net-port=" + adapter.gpgNetPort(),
                        "--ice-adapter-lobby-port=" + adapter.lobbyPort(),
                        "--mock-game-binary-path=" + slowDyingGame()));
        lobby.broadcastText(GAME_LAUNCH);
        lobby.broadcastText(HOST_GAME);
        awaitLogged("state entry: HOSTING");

        child.destroy(); // SIGTERM on Linux and macOS

        assertExitCode(143);
        List<JsonNode> records = records();
        assertEquals(1, count(records, SIGNAL_LINE), "signal named once: " + messages(records));
        assertEquals(List.of(), verdicts(records), "a signalled run names no verdict");
        assertTrue(
                messages(records).stream()
                        .noneMatch(m -> m.startsWith("ICE adapter exited abnormally")),
                "an adapter the signal killed is not a finding: " + messages(records));
        assertEquals(
                1000,
                lobby.awaitClose(STEP_BUDGET_SECONDS, TimeUnit.SECONDS),
                "the WebSocket must close cleanly");
        assertEquals(
                1, gameStateEndedSent(), "the lobby must hear GameState Ended before the close");
    }

    /**
     * A game stand-in that runs until SIGTERM and then takes about a second and a half to exit.
     *
     * @return the script
     * @throws IOException if it cannot be written
     */
    private Path slowDyingGame() throws IOException {
        Path script = dir.resolve("slow-dying-game");
        Files.writeString(
                script,
                "#!/bin/sh\ntrap 'sleep 1.5; exit 143' TERM\nwhile :; do sleep 0.1; done\n");
        assertTrue(script.toFile().setExecutable(true), "could not mark the game executable");
        return script;
    }

    /**
     * How many {@code GameState Ended} frames the child sent. Call once the lobby has seen its
     * clean close: the server handles a connection's frames in order, so every frame sent before
     * the close is queued by then, and the first empty poll means there are no more.
     *
     * @return how many of its remaining frames were it
     */
    private long gameStateEndedSent() throws Exception {
        long sent = 0;
        while (true) {
            JsonNode frame;
            try {
                frame = MAPPER.readTree(lobby.pollReceived(250, TimeUnit.MILLISECONDS));
            } catch (AssertionError none) {
                return sent;
            }
            if ("GameState".equals(frame.path("command").asText())
                    && "Ended".equals(frame.path("args").path(0).asText())) {
                sent++;
            }
        }
    }

    /**
     * What an idle run a signal ended must show: the signal named once, no verdict (the code is the
     * signal's own), nothing at ERROR, a normal close frame at the lobby, and no {@code GameState
     * Ended} before it.
     */
    private void assertEndedCleanlyBySignal() throws Exception {
        List<JsonNode> records = records();
        assertEquals(
                1,
                count(records, SIGNAL_LINE),
                "the signal must be named exactly once: " + messages(records));
        assertEquals(List.of(), verdicts(records), "a signalled run names no verdict");
        assertNoErrors(records);
        assertEquals(
                1000,
                lobby.awaitClose(STEP_BUDGET_SECONDS, TimeUnit.SECONDS),
                "the WebSocket must close cleanly");
        // Teardown's step runs on every teardown, this idle one included, but no game_launch was
        // acted on, so there is no game end to report (#454, #462).
        assertEquals(0, gameStateEndedSent(), "an idle run has no game end to report");
    }

    /**
     * Starts {@code run} in a child JVM and plays the lobby's side of the handshake until the child
     * reports itself idle.
     */
    private void startRunAndReachIdle() throws Exception {
        startRunAndReachIdle(
                List.of(
                        "--ice-adapter-binary-path=" + dir.resolve("no-such-adapter"),
                        "--mock-game-binary-path=" + dir.resolve("no-such-game")));
    }

    /**
     * As {@link #startRunAndReachIdle()}, with the game session's own options.
     *
     * @param sessionArgs the adapter and game options, binary paths included
     */
    private void startRunAndReachIdle(final List<String> sessionArgs) throws Exception {
        startRunAndReachAuth(sessionArgs);
        lobby.broadcastText(WELCOME);
        awaitLogged(IDLE_LINE);
    }

    /**
     * Starts {@code run} in a child JVM and plays the lobby's side of the handshake up to the
     * child's {@code auth}, which the caller answers.
     *
     * @param sessionArgs the adapter and game options, binary paths included
     */
    private void startRunAndReachAuth(final List<String> sessionArgs) throws Exception {
        startRun(lobby.uri(), sessionArgs);
        assertEquals("ask_session", nextFrame().path("command").asText());
        lobby.broadcastText("{\"command\":\"session\",\"session\":42}");
        assertEquals("auth", nextFrame().path("command").asText());
    }

    /**
     * Asserts a login the lobby ended before {@code welcome} (#473): one ERROR naming how, where
     * the setup timeout used to be blamed, no verdict and no signal line. The WARNs are the given
     * ones, besides the lifecycle's {@link #HANDSHAKE_WARN}: that one is written on the lobby's
     * thread after the main thread has woken, so the JVM can exit before it reaches the log.
     *
     * @param failure how the ERROR names the end of the login
     * @param warnings the WARNs expected besides the handshake's
     */
    private void assertLoginEndedAtOnce(final String failure, final List<String> warnings)
            throws IOException {
        List<JsonNode> records = records();
        assertEquals(
                List.of(
                        "lobby session with "
                                + lobby.uri()
                                + " failed: AuthenticationException: "
                                + failure),
                messagesAt(records, "ERROR"),
                "one ERROR must name how the login ended");
        assertEquals(
                warnings,
                messagesAt(records, "WARN").stream()
                        .filter(m -> !m.equals(HANDSHAKE_WARN))
                        .toList(),
                "the WARNs besides the handshake's");
        assertEquals(List.of(), verdicts(records), "a session that never opened names no verdict");
        assertEquals(0, count(records, SIGNAL_LINE), "no signal was sent: " + messages(records));
    }

    /**
     * Starts {@code run} in a child JVM against {@code lobbyUrl}, with a placeholder access token
     * and unique id, logging to {@link #jsonl()}.
     *
     * @param lobbyUrl the lobby the child connects to
     * @param sessionArgs the child's other options, such as its binary paths
     */
    private void startRun(final URI lobbyUrl, final List<String> sessionArgs) throws Exception {
        Path token = Files.writeString(dir.resolve("access-token"), "placeholder-token");
        List<String> command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                Main.class.getName(),
                                "run",
                                "--lobby-websocket-url=" + lobbyUrl,
                                "--oauth-access-token-file=" + token,
                                "--unique-id=00000000-0000-0000-0000-000000000000",
                                "--log-file=" + jsonl()));
        command.addAll(sessionArgs);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(dir.toFile());
        // The child reads the live environment, so anything exported for this JVM would configure
        // it from outside the test: scrub what the harness reads, as LogLevelFlagEndToEndTest does.
        pb.environment().remove(LoggingSetup.LOG_LEVEL_ENV);
        pb.environment().remove(LoggingSetup.LOG_FILE_ENV);
        pb.environment().remove(LoggingSetup.INSTANCE_NAME_ENV);
        pb.environment().keySet().removeIf(name -> name.startsWith("FAF_MOCK_CLIENT_"));
        pb.redirectErrorStream(true);
        pb.redirectOutput(console().toFile());
        child = pb.start();
    }

    /**
     * The next frame the child sent the lobby, failing at once if the child has exited instead.
     *
     * @return the frame, parsed
     */
    private JsonNode nextFrame() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STEP_BUDGET_SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                return MAPPER.readTree(lobby.pollReceived(250, TimeUnit.MILLISECONDS));
            } catch (AssertionError nothingYet) {
                // The fixture signals an empty poll this way; keep waiting unless the child died.
                if (!child.isAlive()) {
                    fail("the child exited " + child.exitValue() + " early: " + readConsole());
                }
            }
        }
        return fail("no frame from the child within " + STEP_BUDGET_SECONDS + " s");
    }

    /**
     * Waits until the child's log contains {@code text}, failing at once if the child exits.
     *
     * @param text the text to wait for
     */
    private void awaitLogged(final String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STEP_BUDGET_SECONDS);
        while (System.nanoTime() < deadline) {
            if (Files.exists(jsonl()) && Files.readString(jsonl()).contains(text)) {
                return;
            }
            if (!child.isAlive()) {
                fail("the child exited " + child.exitValue() + " early: " + readConsole());
            }
            Thread.sleep(50);
        }
        fail("the child never logged \"" + text + "\": " + readConsole());
    }

    /**
     * Waits for the child to exit and checks its code.
     *
     * @param expected the exit code it must report
     */
    private void assertExitCode(final int expected) throws Exception {
        assertTrue(
                child.waitFor(STEP_BUDGET_SECONDS, TimeUnit.SECONDS),
                "the child did not exit: " + readConsole());
        assertEquals(expected, child.exitValue(), "console: " + readConsole());
    }

    /**
     * Whether a child started from this JVM would receive SIGINT, read from this JVM's own ignored
     * mask, which a child inherits. {@code false} where the mask cannot be read.
     *
     * @return {@code true} if SIGINT is not ignored here
     * @throws IOException if the status file cannot be read
     */
    private static boolean sigintReachesAChild() throws IOException {
        Path status = Path.of("/proc/self/status");
        if (!Files.isReadable(status)) {
            return false;
        }
        for (String line : Files.readAllLines(status)) {
            if (line.startsWith("SigIgn:")) {
                long ignored =
                        Long.parseUnsignedLong(line.substring("SigIgn:".length()).trim(), 16);
                return (ignored & SIGINT_MASK) == 0;
            }
        }
        return false;
    }

    private Path jsonl() {
        return dir.resolve("child.jsonl");
    }

    private Path console() {
        return dir.resolve("child.out");
    }

    private String readConsole() throws IOException {
        return Files.exists(console()) ? Files.readString(console()) : "<no output>";
    }

    /**
     * Every record the child wrote, read once it has exited so no line is partial.
     *
     * @return the records, in order
     */
    private List<JsonNode> records() throws IOException {
        List<JsonNode> records = new ArrayList<>();
        for (String line : Files.readAllLines(jsonl())) {
            records.add(MAPPER.readTree(line));
        }
        return records;
    }

    private static List<String> messages(final List<JsonNode> records) {
        return records.stream().map(r -> r.path("message").asText()).toList();
    }

    private static List<String> messagesAt(final List<JsonNode> records, final String level) {
        return records.stream()
                .filter(r -> level.equals(r.path("level").asText()))
                .map(r -> r.path("message").asText())
                .toList();
    }

    private static List<String> verdicts(final List<JsonNode> records) {
        return messages(records).stream()
                .filter(m -> m.endsWith(VERDICT_ENDING) || m.equals(LOBBY_DROP_VERDICT))
                .toList();
    }

    private static long count(final List<JsonNode> records, final String message) {
        return messages(records).stream().filter(message::equals).count();
    }

    private static void assertNoErrors(final List<JsonNode> records) {
        List<String> errors =
                records.stream()
                        .filter(r -> "ERROR".equals(r.path("level").asText()))
                        .map(r -> r.path("message").asText())
                        .toList();
        assertEquals(List.of(), errors, "nothing may be logged at ERROR");
    }
}
