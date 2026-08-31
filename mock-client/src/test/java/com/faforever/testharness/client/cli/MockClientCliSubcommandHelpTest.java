package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Verifies that {@code mock-client --help} and {@code mock-client <subcommand> --help} produce
 * usable, subcommand-scoped help. Regression guard for two failure modes:
 *
 * <ul>
 *   <li>The root help must list every subcommand, so users can discover them.
 *   <li>Per-subcommand {@code --help} must short-circuit before required-check on the parent's
 *       required flags — same guarantee the existing {@code ConfigLoaderHelpTest} enforces for the
 *       root command.
 * </ul>
 */
final class MockClientCliSubcommandHelpTest {

    private static int execute(
            final String[] args, final StringWriter out, final StringWriter err) {
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void rootHelpListsAllFourSubcommands() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = execute(new String[] {"--help"}, out, err);

        String text = out.toString();
        assertEquals(0, exit, "--help should exit 0; got " + exit + " stderr=" + err);
        assertTrue(text.contains("Usage:"), "Help should contain Usage: header. Got: " + text);
        assertTrue(text.contains("Commands:"), "Help should list Commands: section. Got: " + text);
        assertTrue(text.contains("run"), "Help should mention 'run'. Got: " + text);
        assertTrue(text.contains("launch-ice"), "Help should mention 'launch-ice'. Got: " + text);
        assertTrue(text.contains("launch-game"), "Help should mention 'launch-game'. Got: " + text);
        // Load-bearing beyond discoverability: a command can be implemented, tested, and
        // documented while silently missing from MockClientCli's `subcommands` list — which is
        // exactly what a merge dropped once. This assertion is what catches that.
        assertTrue(text.contains("ice-smoke"), "Help should mention 'ice-smoke'. Got: " + text);
    }

    @Test
    void runSubcommandHelpShortCircuitsRequiredCheck() {
        assertSubcommandHelpWorks("run");
    }

    @Test
    void launchIceSubcommandHelpShortCircuitsRequiredCheck() {
        assertSubcommandHelpWorks("launch-ice");
    }

    @Test
    void launchGameSubcommandHelpShortCircuitsRequiredCheck() {
        assertSubcommandHelpWorks("launch-game");
    }

    @Test
    void iceSmokeSubcommandHelpShortCircuitsRequiredCheck() {
        assertSubcommandHelpWorks("ice-smoke");
    }

    private static void assertSubcommandHelpWorks(final String subcommand) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = execute(new String[] {subcommand, "--help"}, out, err);

        String text = out.toString();
        assertEquals(0, exit, subcommand + " --help should exit 0; got " + exit + " err=" + err);
        assertTrue(
                text.contains("Usage: mock-client " + subcommand),
                subcommand
                        + " --help should contain 'Usage: mock-client "
                        + subcommand
                        + "'. Got: "
                        + text);
        assertFalse(
                text.contains("Missing required options"),
                subcommand
                        + " --help must not trigger required-check; if it does, --help isn't "
                        + "short-circuiting. Got: "
                        + text);
    }
}
