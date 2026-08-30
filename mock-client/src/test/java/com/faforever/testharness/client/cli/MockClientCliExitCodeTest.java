package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.Main;
import com.faforever.testharness.client.config.ConfigLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

/**
 * Codifies the exit-code reference table documented in {@code mock-client/README.md}. Each test
 * here is a single row of that table; if a test fails the README is wrong, the implementation is
 * wrong, or both.
 */
final class MockClientCliExitCodeTest {

    /** Name the throwing test subcommand is registered under. */
    private static final String THROWING_SUBCOMMAND = "boom";

    /** Message {@link ThrowingCommand} throws, asserted on so the diagnostic is traced to it. */
    private static final String THROWN_MESSAGE = "simulated subcommand failure";

    @TempDir private Path tempDir;

    private static int execute(final String[] args) {
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute(args);
    }

    /**
     * The exit code plus everything the entry point wrote to stderr.
     *
     * @param exitCode the code {@code main} would have passed to {@link System#exit(int)}
     * @param err the captured stderr text
     */
    private record MainOutcome(int exitCode, String err) {}

    /**
     * Drives the real entry point, {@link Main#run}, rather than {@link ConfigLoader} directly.
     * Config-file failures are raised while the {@link CommandLine} is being built, so {@link
     * #execute(String[])} above cannot reach them — it would throw out of its own {@code
     * newCommandLine} call, exactly the crash this covers.
     *
     * @param args raw command-line arguments
     * @return the exit code and captured stderr
     */
    private static MainOutcome runMain(final String[] args) {
        return runMain(args, Map.of());
    }

    /**
     * As {@link #runMain(String[])}, with a caller-supplied environment. {@link Main#run} takes the
     * env map precisely so its guard can be driven from a test; passing {@link Map#of()} everywhere
     * left the stale-{@code FAF_MOCK_CLIENT_*} case its javadoc names asserted in prose only.
     *
     * @param args raw command-line arguments
     * @param env environment map handed to the layered default-value provider
     * @return the exit code and captured stderr
     */
    private static MainOutcome runMain(final String[] args, final Map<String, String> env) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(captured, true, StandardCharsets.UTF_8);
        int exitCode = Main.run(args, env, err);
        err.flush();
        return new MainOutcome(exitCode, stripAnsi(captured.toString(StandardCharsets.UTF_8)));
    }

    /**
     * A subcommand whose {@code call()} throws an unchecked exception, standing in for anything the
     * real subcommands do not catch — the lobby session raising a {@link RuntimeException} past
     * {@code RunCommand}'s four {@code catch} clauses, say. Registered on a real {@link
     * CommandLine} from {@link ConfigLoader#newCommandLine}, so the assertions exercise the handler
     * as the production factory wires it rather than one the test installed itself.
     */
    @CommandLine.Command(name = THROWING_SUBCOMMAND)
    private static final class ThrowingCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            throw new IllegalStateException(THROWN_MESSAGE);
        }
    }

    /**
     * Runs {@link ThrowingCommand} through the production {@link CommandLine} and captures what
     * picocli's own error writer received.
     *
     * @return the exit code and captured stderr
     */
    private static MainOutcome executeThrowingSubcommand() {
        String[] args = {THROWING_SUBCOMMAND};
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        // Register before setOut/setErr, not after: picocli propagates those writers to the
        // subcommands present at the moment they are set, so a subcommand added afterwards keeps
        // System.err and the capture below comes back empty — a silently passing assertion.
        cmd.addSubcommand(THROWING_SUBCOMMAND, new ThrowingCommand());
        StringWriter captured = new StringWriter();
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(captured));
        int exitCode = cmd.execute(args);
        return new MainOutcome(exitCode, stripAnsi(captured.toString()));
    }

    /**
     * Runs {@link ThrowingCommand} with the root logger forced to {@code level}, capturing every
     * record it emits. The level is restored on the way out so neighbouring tests keep the level
     * {@code logback.xml} gave them.
     *
     * @param level the root level to install for the duration of the run
     * @return the log records emitted while the command ran
     */
    private static List<ILoggingEvent> captureLogsAt(final Level level) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Level original = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        root.setLevel(level);
        try {
            executeThrowingSubcommand();
            return List.copyOf(appender.list);
        } finally {
            root.setLevel(original);
            root.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * Removes ANSI escape sequences so the assertions below describe the text, not the styling.
     * Picocli colours its usage block whenever {@code Ansi.ansiPossible()} says it may — which
     * {@code CLICOLOR_FORCE} and {@code -Dpicocli.ansi=true} both force on, and Gradle hands the
     * ambient environment to its test workers. Without this, a developer who exports {@code
     * CLICOLOR_FORCE} gets a red build from a green codebase.
     *
     * @param text captured stderr, possibly styled
     * @return the same text with CSI sequences removed
     */
    private static String stripAnsi(final String text) {
        return text.replaceAll("\\e\\[[0-?]*[ -/]*[@-~]", "");
    }

    /**
     * The diagnostic lines printed ahead of picocli's usage block.
     *
     * @param err captured stderr
     * @return every line before the first {@code Usage:} line
     */
    private static List<String> errorLines(final String err) {
        List<String> lines = new ArrayList<>();
        for (String line : err.split("\\R")) {
            if (line.startsWith("Usage:")) {
                break;
            }
            lines.add(line);
        }
        return lines;
    }

    /**
     * Asserts the captured stderr carries no stack trace. Meaningful only for failures raised while
     * the {@link CommandLine} is being built, which is all {@link Main#run} writes to the injected
     * stream; anything picocli emits from inside {@code execute} goes to its own writer and would
     * not appear here, so this would pass on an empty capture.
     *
     * @param err captured stderr
     */
    private static void assertNoStackTrace(final String err) {
        assertFalse(err.contains("Exception in thread"), "stderr leaked an uncaught exception");
        assertFalse(err.contains("\tat "), "stderr leaked a stack trace frame");
        assertFalse(err.contains("picocli.CommandLine$ParameterException"), "stderr leaked a type");
    }

    @Test
    void rootHelpExitsZero() {
        assertEquals(ExitCodes.OK, execute(new String[] {"--help"}));
    }

    @Test
    void rootVersionExitsZero() {
        assertEquals(ExitCodes.OK, execute(new String[] {"--version"}));
    }

    @Test
    void subcommandHelpExitsZero() {
        assertEquals(ExitCodes.OK, execute(new String[] {"run", "--help"}));
    }

    @Test
    void unknownSubcommandExitsUsage() {
        assertEquals(ExitCodes.USAGE, execute(new String[] {"wat"}));
    }

    @Test
    void unknownFlagOnSubcommandExitsUsage() {
        String[] args =
                Stream.concat(
                                Stream.of("run", "--bogus=x"),
                                Stream.of(CliTestFixtures.minimalRequiredFlags()))
                        .toArray(String[]::new);
        assertEquals(ExitCodes.USAGE, execute(args));
    }

    @Test
    void missingRequiredOnSubcommandExitsUsage() {
        assertEquals(ExitCodes.USAGE, execute(new String[] {"run"}));
    }

    @Test
    void unreadableConfigFileExitsUsage() {
        Path absent = tempDir.resolve("no-such-config.json");
        MainOutcome outcome = runMain(new String[] {"run", "--config", absent.toString()});

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertEquals(List.of("config file is not readable: " + absent), errorLines(outcome.err()));
        assertTrue(outcome.err().contains("Usage: mock-client"), "usage text was not printed");
        assertNoStackTrace(outcome.err());
    }

    @Test
    void malformedJsonConfigFileExitsUsage() throws IOException {
        Path malformed = Files.writeString(tempDir.resolve("malformed.json"), "{ not json");
        MainOutcome outcome = runMain(new String[] {"run", "--config", malformed.toString()});

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        // Jackson's own getMessage() spans two lines; the loader folds the file and the location
        // it reported into one, so the diagnostic stays a single line and still says where to
        // look. The exact reason text is Jackson's and is not pinned here.
        List<String> lines = errorLines(outcome.err());
        assertEquals(1, lines.size(), "expected a single-line error, got: " + lines);
        assertTrue(
                lines.get(0).startsWith("failed to parse config file " + malformed + " (line "),
                "error line named neither the file nor the location: " + lines.get(0));
        assertTrue(outcome.err().contains("Usage: mock-client"), "usage text was not printed");
        assertNoStackTrace(outcome.err());
    }

    @Test
    void invalidConfigPathExitsUsage() {
        // Path.of rejects a NUL on every platform, and on Windows also < > : " | ? * — so a
        // typo'd --config can be syntactically invalid, not merely absent. That parse happens
        // before the CommandLine exists, so it must be mapped to USAGE like every other one.
        String nulPath = "a" + ((char) 0) + "b.json";
        MainOutcome outcome = runMain(new String[] {"run", "--config", nulPath});

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertEquals(1, errorLines(outcome.err()).size(), "expected a single-line error");
        assertTrue(
                errorLines(outcome.err()).get(0).startsWith("invalid --config path: "),
                "unexpected error line: " + errorLines(outcome.err()).get(0));
        assertNoStackTrace(outcome.err());
    }

    @Test
    void configPathContainingNewlineStaysOnOneLine() {
        // A newline is legal in a POSIX filename. Interpolated raw it would split the diagnostic,
        // and a path containing "\nUsage:" would forge the boundary between the error and picocli's
        // usage block for anything reading stderr — so the path is escaped, not passed through.
        Path forged = tempDir.resolve("x\nUsage: mock-client FORGED\ny.json");
        MainOutcome outcome = runMain(new String[] {"run", "--config", forged.toString()});

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        List<String> lines = errorLines(outcome.err());
        assertEquals(1, lines.size(), "path newline split the diagnostic: " + lines);
        assertTrue(
                lines.get(0).startsWith("config file is not readable: "),
                "unexpected error line: " + lines.get(0));
        // The line-count check alone is not enough: unescaped, the forged "Usage:" line would be
        // read as the usage block and errorLines would still report one line. Requiring the whole
        // path on that line is what proves it was escaped rather than truncated at the newline.
        assertTrue(
                lines.get(0).endsWith("y.json"),
                "path was truncated at its newline: " + lines.get(0));
        assertTrue(outcome.err().contains("Usage: mock-client [-hV]"), "no real usage block");
        assertNoStackTrace(outcome.err());
    }

    @Test
    void runWithUnreadableTokenFileExitsRuntime() {
        // run is implemented (WBS-3.1.1.4). The minimal fixture's --oauth-refresh-token-file points
        // at a guaranteed-absent placeholder path, so TokenSources fails to read it and run exits
        // RUNTIME fast, before any network I/O.
        assertEquals(ExitCodes.RUNTIME, execute(CliTestFixtures.withSubcommand("run")));
    }

    @Test
    void launchIceWithMissingBinaryExitsRuntime() {
        // launch-ice is implemented (WBS-3.1.2.2). Point --ice-adapter-binary-path at a
        // guaranteed-absent path under the test's temp dir, so the launcher reports "binary not
        // found" and the command exits RUNTIME — no reliance on a host path being absent.
        String absentBinary = tempDir.resolve("no-such-faf-ice-adapter").toString();
        assertEquals(
                ExitCodes.RUNTIME,
                execute(CliTestFixtures.withSubcommandAndIceBinary("launch-ice", absentBinary)));
    }

    @Test
    void launchGameWithMissingBinaryExitsRuntime() {
        // launch-game is implemented (WBS-3.1.2.3). Same pattern as launch-ice: point at a
        // guaranteed-absent path in the temp dir so the test does not silently depend on the
        // Gradle install layout being present.
        String absentBinary = tempDir.resolve("no-such-mock-game").toString();
        assertEquals(
                ExitCodes.RUNTIME,
                execute(CliTestFixtures.withSubcommandAndGameBinary("launch-game", absentBinary)));
    }

    @Test
    void validIceSmokeInvocationExitsNotImplemented() {
        assertEquals(
                ExitCodes.NOT_IMPLEMENTED, execute(CliTestFixtures.withSubcommand("ice-smoke")));
    }

    @Test
    void staleEnvVarExitsUsage() {
        // Main.run's javadoc names this as a case its guard handles. The guard runs inside
        // LayeredDefaultProvider's constructor, before the CommandLine can catch anything, so
        // execute() above cannot reach it — only the entry point can, and only with a real env map.
        MainOutcome outcome =
                runMain(
                        new String[] {"run"},
                        Map.of("FAF_MOCK_CLIENT_OAUTH_PASSWORD", "stale-secret"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        List<String> lines = errorLines(outcome.err());
        assertEquals(1, lines.size(), "expected a single-line error, got: " + lines);
        assertTrue(
                lines.get(0)
                        .startsWith(
                                "deprecated env var FAF_MOCK_CLIENT_OAUTH_PASSWORD is no longer"),
                "error line did not name the stale variable: " + lines.get(0));
        assertTrue(outcome.err().contains("Usage: mock-client"), "usage text was not printed");
        assertNoStackTrace(outcome.err());
    }

    @Test
    void unhandledSubcommandExceptionExitsRuntimeWithoutAStackTrace() {
        // Left to picocli, this is exit 1 plus the full trace on stderr — the failure shape #284
        // removed from the construction side of the entry-point seam, still reachable on this one.
        MainOutcome outcome = executeThrowingSubcommand();

        assertEquals(
                ExitCodes.RUNTIME, outcome.exitCode(), "exit 1 is not in the documented table");
        // Explicit, because errorLines("") is [""] — the size-one check below would pass on an
        // empty capture, which is what a mis-ordered addSubcommand/setErr would produce.
        assertFalse(outcome.err().isEmpty(), "nothing was written to picocli's error writer");
        List<String> lines = errorLines(outcome.err());
        assertEquals(1, lines.size(), "expected a single-line error, got: " + lines);
        assertTrue(
                lines.get(0)
                        .startsWith(
                                "mock-client "
                                        + THROWING_SUBCOMMAND
                                        + " failed: java.lang.IllegalStateException: "
                                        + THROWN_MESSAGE),
                "error line named neither the command nor the cause: " + lines.get(0));
        assertTrue(
                lines.get(0).contains(ExecutionExceptionHandler.DEBUG_HINT),
                "the line must say how to recover the suppressed trace: " + lines.get(0));
        assertNoStackTrace(outcome.err());
    }

    @Test
    void unhandledSubcommandExceptionIsLoggedWithoutItsTraceAtTheDefaultLevel() {
        // Logback's console appender writes to stdout, so an attached throwable would print to the
        // terminal the very trace the test above asserts stderr is free of. The record exists so a
        // harness reading log records alone still sees the failure; the trace is withheld from it.
        ILoggingEvent error = onlyEventAt(captureLogsAt(Level.INFO), Level.ERROR);

        assertEquals(
                "mock-client "
                        + THROWING_SUBCOMMAND
                        + " failed: java.lang.IllegalStateException: "
                        + THROWN_MESSAGE,
                error.getFormattedMessage());
        assertNull(
                error.getThrowableProxy(),
                "the ERROR record must be a plain line, not a logged stack trace");
    }

    @Test
    void unhandledSubcommandExceptionAttachesItsTraceToTheDebugRecord() {
        // Half of "the trace is recoverable at DEBUG": that the handler emits it when DEBUG is
        // enabled. This forces the level programmatically, so it says nothing about whether
        // --log-level can enable DEBUG in the first place — LogLevelFlagEndToEndTest covers that,
        // in a child JVM, because Logback resolves the level once per process.
        ILoggingEvent debug = onlyEventAt(captureLogsAt(Level.DEBUG), Level.DEBUG);

        IThrowableProxy thrown = debug.getThrowableProxy();
        assertNotNull(thrown, "the DEBUG record must carry the throwable");
        assertEquals(IllegalStateException.class.getName(), thrown.getClassName());
        assertEquals(THROWN_MESSAGE, thrown.getMessage());
        assertTrue(
                thrown.getStackTraceElementProxyArray().length > 0,
                "the throwable must carry frames, or nothing was actually recovered");
    }

    @Test
    void everyCommandDeclaresRuntimeAsItsExecutionExceptionExitCode() {
        // The backstop for the two routes that reach picocli's handleUnhandled without consulting
        // the handler. Picocli reads this off the *leaf* command's spec, so asserting it on the
        // root alone would not prove exit 1 unreachable — hence the walk over the subcommands.
        assertDeclaresRuntime(ConfigLoader.newCommandLine(new String[0], Map.of()));
    }

    /**
     * Asserts {@code command} and every command beneath it declares {@link ExitCodes#RUNTIME} as
     * its execution-exception exit code. Recursive rather than one level deep: picocli reads the
     * annotation off whichever command actually threw, so a sub-subcommand added later would be the
     * one leaf still able to exit 1 — precisely the case this test exists to prevent.
     *
     * @param command the command to check, along with its whole subcommand subtree
     */
    private static void assertDeclaresRuntime(final CommandLine command) {
        assertEquals(
                ExitCodes.RUNTIME,
                command.getCommandSpec().exitCodeOnExecutionException(),
                command.getCommandSpec().qualifiedName() + " can still exit 1");
        for (CommandLine sub : command.getSubcommands().values()) {
            assertDeclaresRuntime(sub);
        }
    }

    /**
     * Returns the single record the handler logged at {@code level}, failing if there is not
     * exactly one. Records are matched on the handler's own logger so a third party logging at the
     * same level cannot satisfy or break the assertion. Exactness matters: a second ERROR record
     * would mean the failure is reported twice in the log, and a second DEBUG record would leave
     * "the trace is in the log" ambiguous about which one carries it.
     *
     * @param events every record captured during the run
     * @param level the level to select
     * @return the one record the handler emitted at that level
     */
    private static ILoggingEvent onlyEventAt(final List<ILoggingEvent> events, final Level level) {
        List<ILoggingEvent> matching =
                events.stream()
                        .filter(e -> e.getLevel() == level)
                        .filter(
                                e ->
                                        ExecutionExceptionHandler.class
                                                .getName()
                                                .equals(e.getLoggerName()))
                        .toList();
        assertEquals(1, matching.size(), "expected one " + level + " record, got: " + matching);
        return matching.get(0);
    }
}
