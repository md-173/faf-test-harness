package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Codifies the exit-code reference table documented in {@code mock-client/README.md}. Each test
 * here is a single row of that table; if a test fails the README is wrong, the implementation is
 * wrong, or both.
 */
final class MockClientCliExitCodeTest {

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
    void validRunInvocationExitsNotImplemented() {
        assertEquals(ExitCodes.NOT_IMPLEMENTED, execute(CliTestFixtures.withSubcommand("run")));
    }

    @Test
    void launchIceWithMissingBinaryExitsRuntime() {
        // launch-ice is implemented (WBS-3.1.2.2); the fixture's --ice-adapter-binary-path does
        // not exist, so the launcher reports "binary not found" and the command exits RUNTIME.
        assertEquals(ExitCodes.RUNTIME, execute(CliTestFixtures.withSubcommand("launch-ice")));
    }

    @Test
    void validLaunchGameInvocationExitsNotImplemented() {
        assertEquals(
                ExitCodes.NOT_IMPLEMENTED, execute(CliTestFixtures.withSubcommand("launch-game")));
    }

    @Test
    void validIceSmokeInvocationExitsNotImplemented() {
        assertEquals(
                ExitCodes.NOT_IMPLEMENTED, execute(CliTestFixtures.withSubcommand("ice-smoke")));
    }
}
