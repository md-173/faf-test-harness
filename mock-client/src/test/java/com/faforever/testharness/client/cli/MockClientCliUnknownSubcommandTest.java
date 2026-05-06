package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Verifies that {@code mock-client <unknown>} produces a non-zero exit and an error message that
 * names the offending token. Picocli's default {@link CommandLine.IParameterExceptionHandler}
 * supplies a perfectly serviceable error here; this test guards against accidentally swapping it
 * out for one that hides the problem.
 */
final class MockClientCliUnknownSubcommandTest {

    @Test
    void unknownSubcommandExitsTwoAndMentionsTheToken() {
        // Supply all required flags first so required-check passes; then the bogus token is the
        // only remaining problem and picocli's UnmatchedArgumentException reports it directly.
        String[] required = CliTestFixtures.minimalRequiredFlags();
        String[] args = new String[required.length + 1];
        System.arraycopy(required, 0, args, 0, required.length);
        args[required.length] = "definitely-not-a-real-subcommand";

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute(args);

        assertEquals(
                ExitCodes.USAGE, exit, "Unknown subcommand should exit USAGE (2); got " + exit);

        String combined = out.toString() + err.toString();
        assertTrue(
                combined.contains("definitely-not-a-real-subcommand"),
                "Error output should reference the unknown token. Got: " + combined);
    }

    @Test
    void bareUnknownTokenExitsUsage() {
        // Even without required flags supplied, an unknown subcommand token must still exit USAGE.
        // The error message may be 'Missing required options' or 'Unmatched argument'; both
        // satisfy the issue's "friendly error" requirement.
        String[] args = {"definitely-not-a-real-subcommand"};
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exit = cmd.execute(args);

        assertEquals(ExitCodes.USAGE, exit, "Bare unknown token should exit USAGE; got " + exit);
    }
}
