package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
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
}
