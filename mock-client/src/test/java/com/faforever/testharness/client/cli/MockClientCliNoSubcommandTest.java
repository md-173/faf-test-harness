package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Verifies that bare {@code mock-client} (no subcommand) exits {@link ExitCodes#USAGE}. Empty args
 * trigger required-check on the root's required {@code @Option}s; with all flags supplied but no
 * subcommand, the root's {@code call()} prints help and returns the same code. Either path is
 * acceptable so long as the user sees a non-zero exit and a helpful message.
 */
final class MockClientCliNoSubcommandTest {

    @Test
    void emptyArgsExitsUsage() {
        String[] args = new String[0];
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute(args);

        assertEquals(
                ExitCodes.USAGE,
                exit,
                "Bare mock-client should exit USAGE (2); got "
                        + exit
                        + " out="
                        + out
                        + " err="
                        + err);
    }

    @Test
    void rootFlagsWithoutSubcommandPrintsHelpAndExitsUsage() {
        String[] args = CliTestFixtures.minimalRequiredFlags();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute(args);

        assertEquals(
                ExitCodes.USAGE,
                exit,
                "mock-client with all required flags but no subcommand should still exit USAGE;"
                        + " got "
                        + exit
                        + " out="
                        + out
                        + " err="
                        + err);
    }
}
