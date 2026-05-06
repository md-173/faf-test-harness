package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Verifies that picocli routes {@code mock-client <subcommand> ...} to the matching stub and that
 * each stub returns {@link ExitCodes#NOT_IMPLEMENTED}. If the wrong dispatch happened, the exit
 * code would differ (parse error → {@link ExitCodes#USAGE}, or some unrelated failure), so the exit
 * code is a sufficient signal for the scaffolding pass.
 */
final class MockClientCliDispatchTest {

    private static int dispatch(final String subcommand) {
        String[] args = CliTestFixtures.withSubcommand(subcommand);
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute(args);
    }

    @Test
    void runDispatchesToRunCommand() {
        assertEquals(ExitCodes.NOT_IMPLEMENTED, dispatch("run"));
    }

    @Test
    void launchIceDispatchesToLaunchIceCommand() {
        assertEquals(ExitCodes.NOT_IMPLEMENTED, dispatch("launch-ice"));
    }

    @Test
    void launchGameDispatchesToLaunchGameCommand() {
        assertEquals(ExitCodes.NOT_IMPLEMENTED, dispatch("launch-game"));
    }

    @Test
    void iceSmokeDispatchesToIceSmokeCommand() {
        assertEquals(ExitCodes.NOT_IMPLEMENTED, dispatch("ice-smoke"));
    }
}
