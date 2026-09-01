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
import com.faforever.testharness.client.process.FakeAdapterStub;
import com.faforever.testharness.client.process.FakeIceAdapter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * End-to-end tests for the {@code ice-smoke} subcommand (WBS-3.1.4.3), driven through picocli
 * exactly as {@code Main} drives it. {@link
 * com.faforever.testharness.client.process.FakeIceAdapter} stands in for the real binary; {@link
 * com.faforever.testharness.client.process.IceReachabilityCheckTest} covers the verdicts
 * themselves, so what is pinned here is the command's contract: which exit code each verdict maps
 * to, what the operator sees, and that no lobby credentials are needed to get any of it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class IceSmokeCommandTest {

    @TempDir private Path tempDir;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

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
    }

    @Test
    void reachableAdapterExitsZeroAndLogsAPassLine() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.FULL);

        int exit = execute(iceSmokeArgs(stub, "--timeout-seconds=20"));

        assertEquals(
                ExitCodes.OK, exit, "a reachable adapter must exit 0. captured: " + captured());
        assertTrue(
                appender.list.stream()
                        .anyMatch(e -> e.getFormattedMessage().contains("ice-smoke: PASS")),
                "the operator needs a verdict line. captured: " + captured());
    }

    @Test
    void missingBinaryExitsRuntimeWithSingleLineErrorAndNoStackTrace() {
        Path missing = tempDir.resolve("not-here");
        int[] ports = FakeAdapterStub.freePorts();

        int exit =
                execute(
                        new String[] {
                            "ice-smoke",
                            "--ice-adapter-binary-path=" + missing,
                            "--ice-adapter-rpc-port=" + ports[0],
                            "--ice-adapter-gpg-net-port=" + ports[1],
                            "--ice-adapter-lobby-port=" + ports[2],
                            "--timeout-seconds=3"
                        });

        assertEquals(ExitCodes.RUNTIME, exit, "a missing binary must exit non-zero");
        ILoggingEvent error = findEvent(e -> e.getLevel() == Level.ERROR);
        assertTrue(
                error.getFormattedMessage().contains("binary not found"),
                "the error should explain the failure; got: " + error.getFormattedMessage());
        assertNull(
                error.getThrowableProxy(),
                "the error must be a plain line, not a logged stack trace");
    }

    @Test
    void unreachableAdapterExitsRuntimeAndNamesTheFailedPhase() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.RPC_ONLY);

        int exit = execute(iceSmokeArgs(stub, "--timeout-seconds=5"));

        assertEquals(ExitCodes.RUNTIME, exit, "an unserved endpoint must exit non-zero");
        ILoggingEvent error = findEvent(e -> e.getLevel() == Level.ERROR);
        assertTrue(
                error.getFormattedMessage().contains("GPGNET_UNREACHABLE"),
                "the verdict should be named so a CI log explains itself; got: "
                        + error.getFormattedMessage());
    }

    /** {@code ice-smoke} against {@code stub}, with no lobby or OAuth flags whatsoever. */
    private static String[] iceSmokeArgs(final FakeAdapterStub stub, final String... extra) {
        List<String> args =
                new java.util.ArrayList<>(
                        List.of(
                                "ice-smoke",
                                "--ice-adapter-binary-path=" + stub.binaryPath(),
                                "--ice-adapter-rpc-port=" + stub.rpcPort(),
                                "--ice-adapter-gpg-net-port=" + stub.gpgNetPort(),
                                "--ice-adapter-lobby-port=" + stub.lobbyPort()));
        args.addAll(List.of(extra));
        return args.toArray(new String[0]);
    }

    private static int execute(final String[] args) {
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute(args);
    }

    private ILoggingEvent findEvent(final Predicate<ILoggingEvent> matcher) {
        for (ILoggingEvent e : appender.list) {
            if (matcher.test(e)) {
                return e;
            }
        }
        fail("no log event matched. captured: " + captured());
        throw new AssertionError("unreachable");
    }

    private String captured() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString();
    }
}
