package com.faforever.testharness.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.config.ExitCodes;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The half of game crash injection (WBS-5.2) that only a real process can show: {@code
 * Runtime.halt} actually ends the JVM, with the documented exit code, having written none of the
 * frames an orderly end would write.
 *
 * <p>{@link com.faforever.testharness.game.lifecycle.CrashInjectionTest} covers the arming rule and
 * the scheduling against a recording stand-in, because the real halt would take the Gradle test
 * worker with it. That stand-in returns, so it can never show what a halted process leaves behind,
 * which is the whole point of preferring halt over {@link System#exit(int)}. This test closes that
 * gap by spawning a child that really does die.
 *
 * <p><b>Why a child JVM rather than the built jar.</b> Launching {@code java -cp} with this test
 * worker's own classpath needs no shadow jar, no build ordering and nothing external; the child is
 * the same classes this module just compiled. There is no in-repo precedent for that shape ({@code
 * GpgNetConnectionLiveSmokeTest} runs an external adapter jar off {@code java.home}), so it is
 * spelled out here rather than left to be inferred.
 *
 * <p><b>Why this is not tagged {@code integration}.</b> That tag means "launches the real
 * faf-ice-adapter binary" and is excluded from {@code build}, which is where CI runs. This test
 * needs only loopback and about a second, and it is the only automated evidence for the card's
 * first acceptance criterion, so it belongs in the suite that actually runs.
 */
final class CrashInjectionProcessTest {

    /** Generous budget for a child JVM to boot, connect, arm, and die. */
    private static final long PROCESS_TIMEOUT_SECONDS = 60;

    /** Budget for one frame to cross the loopback socket. */
    private static final long FRAME_TIMEOUT_SECONDS = 20;

    /**
     * Crash delay for the child, in seconds.
     *
     * <p>Deliberately not zero. A halt gives the kernel no chance to drain, and closing a socket
     * with unread data in its receive queue sends RST rather than FIN, which can make the peer
     * discard bytes it had already buffered. At zero the crash lands microseconds after {@code
     * GameState Launching} is written, so the frame this test reads back would be racing the reset.
     * One second is long enough that the scripted server has certainly drained the session's
     * frames, which makes "and no GameEnded followed" a real assertion rather than a coin toss.
     */
    private static final String CRASH_AFTER_SECONDS = "1";

    /** Comfortably past {@link #CRASH_AFTER_SECONDS}, so a wrongly-armed timer would have fired. */
    private static final long CRASH_DELAY_MARGIN_SECONDS = 5;

    /** Scripted stand-in for the adapter's GPGNet server. */
    private ScriptedGpgNetServer gpgnet;

    /** A real bound socket for the child's peer traffic to go somewhere harmless. */
    private DatagramSocket peer;

    /**
     * The port the child binds for its own lobby traffic.
     *
     * <p>Distinct from {@link #peer}'s port, and not merely for tidiness: the child binds whatever
     * {@code CreateLobby} names, so naming the port this test already holds made every run log
     * "failed to bind lobby port (Address already in use)" and left a red herring in the output of
     * a test that passed.
     */
    private int lobbyPort;

    /** The child, killed in teardown if a failure left it alive. */
    private Process child;

    @BeforeEach
    void setup() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
        peer = new DatagramSocket(0);
        lobbyPort = TestPorts.freeUdpPort();
    }

    @AfterEach
    void tearDown() {
        if (child != null) {
            try {
                child.destroyForcibly().waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        gpgnet.stop();
        peer.close();
    }

    /**
     * A real mock-game, launched the way the client launches it, halts with the documented code and
     * emits none of the frames a clean end would.
     *
     * @param tempDir a private directory for the child's own log file
     */
    @Test
    void aRealGameHaltsWithTheInjectedCodeAndNoClosingFrames(@TempDir final Path tempDir)
            throws Exception {
        gpgnet.start();
        child = startChild(tempDir);
        awaitChildConnected();

        // GameState Idle, then the lobby handshake, exactly as the adapter would drive it.
        assertEquals(
                "GameState",
                gpgnet.pollReceived(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS).command());
        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, lobbyPort, "Rhiza", 1, 1)));
        assertEquals(
                "GameState",
                gpgnet.pollReceived(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS).command());

        // A peer arrives, which is what arms the crash when auto-launch is off.
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "JoinGame", List.of("127.0.0.1:" + peer.getLocalPort(), "Smith", 2)));

        assertTrue(
                child.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the injected crash must end the process rather than leave it running");
        assertEquals(
                ExitCodes.INJECTED_CRASH,
                child.exitValue(),
                "a halted game must report the documented injected-crash code");

        List<String> commands = drainCommands();
        assertTrue(
                commands.stream().noneMatch(c -> c.equals("GameEnded")),
                "a crashed game must never send GameEnded. saw: " + commands);
        assertTrue(
                commands.stream().noneMatch(c -> c.equals("GameResult")),
                "a crashed game must never report a result. saw: " + commands);

        // The assertions above are necessary but not sufficient, and it is worth being explicit
        // about why. This run dies out of JOINING, so it never reached the LIVE to ENDED
        // transition that sends the closing frames, and GameShutdown sends none of its own. A
        // System.exit in place of the halt would therefore produce the same exit code and the same
        // empty frame list, and every assertion above would still pass.
        //
        // What separates the two is the shutdown sequence itself. Only GameShutdown.run() logs
        // "mock game shutdown complete", and only a JVM shutdown hook reaches it. Its absence,
        // alongside the crash line that does appear, is the evidence that no orderly teardown ran.
        String log = Files.readString(tempDir.resolve("mock-game.jsonl"));
        assertTrue(
                log.contains("injected crash firing"),
                "the pre-halt warning must reach disk, since nothing flushes after it");
        assertFalse(
                log.contains("mock game shutdown complete"),
                "a halted game must not run its shutdown sequence; System.exit would have");
    }

    /** The clean-run control: the same argv without the flag must not produce the crash code. */
    @Test
    void withoutTheFlagTheSameRunDoesNotCrash(@TempDir final Path tempDir) throws Exception {
        gpgnet.start();
        child = startChild(tempDir, "--crash-after-seconds", "-1");
        awaitChildConnected();

        // Driven to the same arming point as the positive case, and that is the whole point of
        // this control. An earlier version stopped at the first frame, so the child sat in IDLE
        // where armCrash is unreachable whatever the flag says, and the test would have passed
        // just as happily with the fault switched on.
        assertEquals(
                "GameState",
                gpgnet.pollReceived(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS).command());
        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, lobbyPort, "Rhiza", 1, 1)));
        assertEquals(
                "GameState",
                gpgnet.pollReceived(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS).command());
        gpgnet.sendFrame(
                new GpgNetFrame(
                        "JoinGame", List.of("127.0.0.1:" + peer.getLocalPort(), "Smith", 2)));

        // Nothing drives this run to an end, so the assertion is about what it is not: with the
        // fault disabled the game keeps running past the delay the positive case dies at. Killed
        // by teardown.
        assertFalse(
                child.waitFor(CRASH_DELAY_MARGIN_SECONDS, TimeUnit.SECONDS),
                "with the fault disabled nothing may halt the process");
    }

    /**
     * Waits for the child to connect, on the same generous budget as the rest of this test.
     *
     * <p>{@code ScriptedGpgNetServer.awaitClient()}'s no-argument form allows a hardcoded five
     * seconds, which here has to cover fork and exec, JVM boot, logging setup, argument parsing and
     * the GPGNet connect. That is comfortable locally and marginal on a cold or loaded CI runner,
     * and it was the tightest budget in a test whose every other budget is generous.
     */
    private void awaitChildConnected() throws InterruptedException {
        assertTrue(
                gpgnet.awaitClient(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "the child JVM must connect to the scripted GPGNet server");
    }

    /** Every command the scripted server has queued, without blocking once it runs dry. */
    private List<String> drainCommands() throws InterruptedException {
        List<String> commands = new ArrayList<>();
        while (true) {
            try {
                commands.add(gpgnet.pollReceived(1, TimeUnit.SECONDS).command());
            } catch (AssertionError drained) {
                return commands;
            }
        }
    }

    /**
     * Starts a child JVM running {@link Main} against the scripted server.
     *
     * <p>{@code LOG_FILE} and the working directory are redirected into the test's own temp
     * directory: the child inherits this module's project directory otherwise, and would append to
     * the same rolling log file the test worker is writing. Output is inherited rather than piped,
     * because a child blocked writing into a pipe nobody drains never reaches its own crash.
     *
     * @param tempDir the child's private directory for logs
     * @param extra arguments appended after the standard argv
     * @return the started process
     */
    private Process startChild(final Path tempDir, final String... extra) throws IOException {
        List<String> argv =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                Main.class.getName(),
                                "--gpgnet-port",
                                Integer.toString(gpgnet.port()),
                                "--lobby-port",
                                Integer.toString(lobbyPort),
                                "--player-id",
                                "1",
                                "--player-login",
                                "Rhiza",
                                "--game-uid",
                                "9001",
                                // Auto-launch off, which is how a multi-peer session runs and the
                                // configuration a LIVE-anchored crash would never have fired in.
                                "--launch-delay-seconds",
                                "-1"));
        if (extra.length == 0) {
            argv.add("--crash-after-seconds");
            argv.add(CRASH_AFTER_SECONDS);
        } else {
            argv.addAll(List.of(extra));
        }

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(tempDir.toFile());
        pb.environment().put("LOG_FILE", tempDir.resolve("mock-game.jsonl").toString());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        return pb.start();
    }
}
