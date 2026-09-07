package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Codifies the exit-code reference table documented in {@code mock-client/README.md}. Each test
 * here is a single row of that table; if a test fails the README is wrong, the implementation is
 * wrong, or both.
 */
final class MockClientCliExitCodeTest {

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
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(captured, true, StandardCharsets.UTF_8);
        int exitCode = Main.run(args, Map.of(), err);
        err.flush();
        return new MainOutcome(exitCode, stripAnsi(captured.toString(StandardCharsets.UTF_8)));
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
    void iceSmokeRunsWithoutAnyLobbyOrOauthFlags() {
        // This is ice-smoke's RUNTIME row of the table, and its no-credentials guarantee in one:
        // given only an adapter path and no credentials at all, a guaranteed-absent binary must
        // reach the command's own logic and report RUNTIME — not USAGE, which is what a
        // missing-required-options rejection would produce. A second test passing the full flag
        // set would assert nothing this one does not (IceSmokeCommandTest covers the message and
        // the absence of a stack trace).
        String absentBinary = tempDir.resolve("no-such-faf-ice-adapter").toString();
        assertEquals(
                ExitCodes.RUNTIME,
                execute(
                        new String[] {
                            "ice-smoke",
                            "--ice-adapter-binary-path=" + absentBinary,
                            "--timeout-seconds=2"
                        }));
    }

    @Test
    void iceSmokeWithNonPositiveTimeoutExitsUsage() {
        assertEquals(ExitCodes.USAGE, execute(new String[] {"ice-smoke", "--timeout-seconds=0"}));
    }

    @Test
    void iceSmokeWithEqualRpcAndGpgNetPortsExitsUsage() {
        // Both are TCP listeners in one adapter process, so equal values cannot both bind. Caught
        // as a usage error rather than surfacing later as an unexplained "unreachable".
        assertEquals(
                ExitCodes.USAGE,
                execute(
                        new String[] {
                            "ice-smoke",
                            "--ice-adapter-rpc-port=7236",
                            "--ice-adapter-gpg-net-port=7236"
                        }));
    }
}
