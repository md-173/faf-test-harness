package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Verifies that picocli routes {@code mock-client <subcommand> ...} to the matching command. The
 * not-yet-implemented stubs return {@link ExitCodes#NOT_IMPLEMENTED}; {@code launch-ice} is
 * implemented and exits {@link ExitCodes#RUNTIME} on the fixture's missing binary. Either way the
 * exit code differs from a parse error ({@link ExitCodes#USAGE}), so it is a sufficient dispatch
 * signal.
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
        // launch-ice is implemented (WBS-3.1.2.2). The fixture's binary path does not exist, so
        // the command routes through, fails to find the binary, and exits RUNTIME — still a
        // dispatch signal distinct from a parse error (USAGE).
        assertEquals(ExitCodes.RUNTIME, dispatch("launch-ice"));
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
