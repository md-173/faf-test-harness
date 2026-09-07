package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.ConfigLoader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * End-to-end tests for the {@code launch-ice} subcommand (WBS-3.1.2.2): a stub shell script stands
 * in for the real {@code faf-ice-adapter} binary. Covers the spawn/run/terminate happy path and the
 * clear-error / non-zero-exit contract for a missing binary.
 *
 * <p>Since WBS-3.1.6.3 (#279) the command also attaches a JSON-RPC peer, which is what lets a
 * separate {@code launch-game} complete a GPGNet handshake against the adapter it is holding open.
 * A shell stub cannot serve JSON-RPC, so the happy-path cases point {@code --ice-adapter-rpc-port}
 * at a bare {@link ServerSocket} this class owns: {@code IceAdapterConnection.connect()} completes
 * on the TCP connect, and nothing here calls anything over it.
 *
 * <p>The invocations carry no lobby or OAuth flags at all, which is WBS-3.1.5.2-fix (#308): the
 * command validates only the adapter settings, so the eight placeholder values these tests used to
 * pass are gone.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class LaunchIceCommandTest {

    @TempDir private Path tempDir;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    /** Stands in for the adapter's JSON-RPC listener; accepts and holds, never speaks. */
    private ServerSocket rpcListener;

    private Thread rpcAcceptor;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // Subprocess reader threads can append while the test thread reads captured events.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            appender.stop();
            root.detachAppender(appender);
        }
        if (rpcListener != null) {
            try {
                rpcListener.close();
            } catch (IOException ignored) {
                // Best effort; the accept thread is a daemon and ends with it.
            }
        }
    }

    /**
     * Opens a listener on an ephemeral port and returns the {@code --ice-adapter-rpc-port} flag
     * pointing at it. Accepts one connection and holds it, which is all the command's peer needs.
     */
    private String rpcPortFlagForAListenerThatAccepts() throws IOException {
        rpcListener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        rpcAcceptor =
                new Thread(
                        () -> {
                            try {
                                Socket held = rpcListener.accept();
                                // Held open until the test ends; closing here would make the
                                // command's peer see an immediate remote close.
                                synchronized (this) {
                                    while (!rpcListener.isClosed() && held.isConnected()) {
                                        wait(50);
                                    }
                                }
                            } catch (IOException | InterruptedException ignored) {
                                // The listener was closed in teardown, or the test ended.
                            }
                        },
                        "stub-rpc-acceptor");
        rpcAcceptor.setDaemon(true);
        rpcAcceptor.start();
        return "--ice-adapter-rpc-port=" + rpcListener.getLocalPort();
    }

    @Test
    void missingBinaryExitsRuntimeWithSingleLineErrorAndNoStackTrace() {
        Path missing = tempDir.resolve("not-here");
        int exit = execute(launchIceArgs(missing));

        assertEquals(ExitCodes.RUNTIME, exit, "a missing binary must exit non-zero");
        ILoggingEvent error = findEvent(e -> e.getLevel() == Level.ERROR);
        assertTrue(
                error.getMessage().contains("binary not found"),
                "error should explain the failure; got: " + error.getMessage());
        assertNull(
                error.getThrowableProxy(),
                "the error must be a plain line, not a logged stack trace");
    }

    @Test
    void nonPositiveDurationExitsUsage() {
        // The duration guard runs before the launcher, so the binary path is irrelevant here.
        int exit = execute(launchIceArgs(tempDir.resolve("unused"), "--duration-seconds=0"));

        assertEquals(
                ExitCodes.USAGE,
                exit,
                "a non-positive --duration-seconds must be rejected as a usage error");
    }

    @Test
    void stubAdapterRunsForTheWindowThenTerminatesAndLogsExitCode() throws Exception {
        Path stub = createSleepingStub();

        int exit =
                execute(
                        launchIceArgs(
                                stub,
                                "--duration-seconds=1",
                                rpcPortFlagForAListenerThatAccepts()));

        assertEquals(ExitCodes.OK, exit, "a clean spawn-and-terminate cycle should exit OK");
        assertTrue(
                appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("exit code")),
                "the subprocess exit code must be logged. captured: " + appender.list);
    }

    /**
     * The JSON-RPC peer is what makes {@code launch-ice} and {@code launch-game} compose (#279), so
     * it is reported and attributed rather than logged at INFO alongside a healthy-looking run.
     */
    @Test
    void theAttachedRpcPeerIsReported() throws Exception {
        Path stub = createSleepingStub();

        execute(launchIceArgs(stub, "--duration-seconds=1", rpcPortFlagForAListenerThatAccepts()));

        assertTrue(
                appender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains("JSON-RPC peer attached")),
                "the run must say the adapter can now serve a game. captured: " + appender.list);
    }

    /**
     * A deliberate behaviour change from #279. An adapter with no reachable RPC port cannot serve a
     * game at all — its {@code GPGNetClient} constructor blocks forever waiting for a peer — so
     * reporting OK here would mean reporting success for an adapter that will drop the first game
     * that connects to it.
     */
    @Test
    void anAdapterWithNoRpcPortExitsRuntime() throws Exception {
        Path stub = createSleepingStub();

        // Port 1: below every platform's ephemeral range, so nothing in this JVM can hold it.
        int exit = execute(launchIceArgs(stub, "--duration-seconds=1", "--ice-adapter-rpc-port=1"));

        assertEquals(
                ExitCodes.RUNTIME,
                exit,
                "an adapter no peer can attach to is not a usable adapter");
        ILoggingEvent error = findEvent(e -> e.getLevel() == Level.ERROR);
        assertTrue(
                error.getFormattedMessage().contains("could not attach a JSON-RPC peer"),
                "the failure must name what went wrong; got: " + error.getFormattedMessage());
    }

    /** A stub adapter that starts, says so, and stays up until it is terminated. */
    private Path createSleepingStub() throws IOException {
        return createStub(
                "#!/bin/sh\n" + "echo ICE-ADAPTER-STUB-UP\n" + "while true; do sleep 1; done\n");
    }

    private Path createStub(final String body) throws IOException {
        Path script = tempDir.resolve("stub-adapter");
        Files.writeString(script, body);
        assertTrue(script.toFile().setExecutable(true), "could not mark stub executable");
        return script;
    }

    private static int execute(final String[] args) {
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute(args);
    }

    /**
     * {@code launch-ice} with the adapter binary pointed at {@code bin} — and nothing else.
     *
     * <p>No lobby or OAuth flags, which is the point of #308: this command opens no lobby
     * connection, so every one of the eight values this helper used to pass was a placeholder
     * invented to get past validation.
     */
    private static String[] launchIceArgs(final Path bin, final String... extra) {
        List<String> args = new ArrayList<>();
        args.add("launch-ice");
        args.add("--ice-adapter-binary-path=" + bin);
        args.addAll(List.of(extra));
        return args.toArray(new String[0]);
    }

    private ILoggingEvent findEvent(final java.util.function.Predicate<ILoggingEvent> matcher) {
        for (ILoggingEvent e : appender.list) {
            if (matcher.test(e)) {
                return e;
            }
        }
        fail("no log event matched. captured: " + appender.list);
        throw new AssertionError("unreachable");
    }
}
