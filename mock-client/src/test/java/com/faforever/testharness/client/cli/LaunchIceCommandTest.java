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
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class LaunchIceCommandTest {

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
        Path stub =
                createStub(
                        "#!/bin/sh\n"
                                + "echo ICE-ADAPTER-STUB-UP\n"
                                + "while true; do sleep 1; done\n");

        int exit = execute(launchIceArgs(stub, "--duration-seconds=1"));

        assertEquals(ExitCodes.OK, exit, "a clean spawn-and-terminate cycle should exit OK");
        assertTrue(
                appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("exit code")),
                "the subprocess exit code must be logged. captured: " + appender.list);
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
     * {@code launch-ice} plus the required flags, with the adapter binary pointed at {@code bin}.
     */
    private static String[] launchIceArgs(final Path bin, final String... extra) {
        List<String> args = new ArrayList<>();
        args.add("launch-ice");
        args.add("--lobby-websocket-url=" + CliTestFixtures.LOBBY_URL);
        args.add("--oauth-token-url=" + CliTestFixtures.OAUTH_TOKEN_URL);
        args.add("--oauth-auth-endpoint=" + CliTestFixtures.OAUTH_AUTH_ENDPOINT);
        args.add("--oauth-redirect-uri=" + CliTestFixtures.OAUTH_REDIRECT_URI);
        args.add("--oauth-scopes=" + CliTestFixtures.OAUTH_SCOPES);
        args.add("--oauth-client-id=" + CliTestFixtures.OAUTH_CLIENT_ID);
        args.add("--oauth-refresh-token-file=" + CliTestFixtures.OAUTH_REFRESH_TOKEN_FILE);
        args.add("--unique-id=" + CliTestFixtures.UNIQUE_ID);
        args.add("--ice-adapter-binary-path=" + bin);
        args.add("--mock-game-binary-path=" + CliTestFixtures.MOCK_GAME_BIN);
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
