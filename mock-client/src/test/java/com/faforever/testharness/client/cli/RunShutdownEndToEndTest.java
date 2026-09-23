package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.faforever.testharness.client.Main;
import com.faforever.testharness.client.lobby.ScriptedWebSocketServer;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
 * (WBS-3.1.5.2-fix, #297, and WBS-3.1.3.2-fix, #446).
 *
 * <p>{@code mock-client/README.md} documents that {@code Ctrl-C} or {@code SIGTERM} closes the
 * WebSocket cleanly and exits 130 or 143. {@code SignalExitCodeEndToEndTest} pins the JDK behaviour
 * underneath, through a stand-in. This drives the product path: the real {@code Main}, the real
 * shutdown hook and teardown, and what an operator reads in the log. The child JVM runs {@code run}
 * against a scripted lobby in this one; an access-token file and a fixed unique id stand in for the
 * OAuth exchange and {@code faf-uid}, and reaching IDLE starts no subprocess, so nothing here needs
 * the network or a binary.
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

    /** How every verdict line {@code RunCommand} logs ends. */
    private static final String VERDICT_ENDING = "reporting it in this run's exit code";

    /** Logged just before the main thread parks: the session is idle. */
    private static final String IDLE_LINE = "mock client idle as player";

    /** SIGINT is signal 2, so bit 1 of a {@code SigIgn} mask. */
    private static final long SIGINT_MASK = 1L << 1;

    private static final String WELCOME =
            "{\"command\":\"welcome\",\"me\":{\"id\":7,\"login\":\"MockPlayer\"},"
                    + "\"current_time\":\"2026-09-23T00:00:00Z\"}";

    /** A custom game's launch, carrying every field the handler requires. */
    private static final String GAME_LAUNCH =
            "{\"command\":\"game_launch\",\"uid\":4242,\"mod\":\"faf\",\"name\":\"shutdown"
                    + " test\",\"game_type\":\"custom\",\"rating_type\":\"global\","
                    + "\"init_mode\":0}";

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
                "SIGINT is ignored in this JVM or cannot be checked, so a child would inherit it"
                        + " and never run its hook");
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
     * A run that ends on its own names no signal, whatever its code (#446): a launch that never
     * came up, because the adapter binary does not exist, exits 70 with its cause and its verdict
     * and without the signal line. Teardown closes the lobby before {@code call()} returns, and the
     * hook that {@code Main}'s {@code System.exit} starts found that close still unechoed, so the
     * line used to follow the verdict. Also pins #437's 70 against the real process.
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
        assertNoErrors(records);
        assertEquals(
                1000,
                lobby.awaitClose(STEP_BUDGET_SECONDS, TimeUnit.SECONDS),
                "teardown must close the lobby cleanly");
    }

    /**
     * What a run a signal ended must show: the signal named once, no verdict (the code is the
     * signal's own), nothing at ERROR, and a normal close frame at the lobby.
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
    }

    /**
     * Starts {@code run} in a child JVM and plays the lobby's side of the handshake until the child
     * reports itself idle.
     */
    private void startRunAndReachIdle() throws Exception {
        Path token = Files.writeString(dir.resolve("access-token"), "placeholder-token");
        List<String> command =
                List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        Main.class.getName(),
                        "run",
                        "--lobby-websocket-url=" + lobby.uri(),
                        "--oauth-access-token-file=" + token,
                        "--unique-id=00000000-0000-0000-0000-000000000000",
                        "--ice-adapter-binary-path=" + dir.resolve("no-such-adapter"),
                        "--mock-game-binary-path=" + dir.resolve("no-such-game"),
                        "--log-file=" + jsonl());
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

        assertEquals("ask_session", nextFrame().path("command").asText());
        lobby.broadcastText("{\"command\":\"session\",\"session\":42}");
        assertEquals("auth", nextFrame().path("command").asText());
        lobby.broadcastText(WELCOME);
        awaitLogged(IDLE_LINE);
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

    private static List<String> verdicts(final List<JsonNode> records) {
        return messages(records).stream().filter(m -> m.endsWith(VERDICT_ENDING)).toList();
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
