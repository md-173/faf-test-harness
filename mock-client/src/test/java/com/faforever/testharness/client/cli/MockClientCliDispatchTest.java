package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Verifies that picocli routes {@code mock-client <subcommand> ...} to the matching command. The
 * not-yet-implemented stubs return {@link ExitCodes#NOT_IMPLEMENTED}; {@code launch-ice} is
 * implemented and exits {@link ExitCodes#RUNTIME} on a missing binary. Either way the exit code
 * differs from a parse error ({@link ExitCodes#USAGE}), so it is a sufficient dispatch signal.
 */
final class MockClientCliDispatchTest {

    @TempDir private Path tempDir;

    private static int execute(final String[] args) {
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute(args);
    }

    private static int dispatch(final String subcommand) {
        return execute(CliTestFixtures.withSubcommand(subcommand));
    }

    @Test
    void runDispatchesToRunCommand() {
        assertEquals(ExitCodes.NOT_IMPLEMENTED, dispatch("run"));
    }

    @Test
    void launchIceDispatchesToLaunchIceCommand() {
        // launch-ice is implemented (WBS-3.1.2.2). Point it at a guaranteed-absent path inside the
        // test's temp dir so the command routes through, fails to find the binary, and exits
        // RUNTIME — a dispatch signal distinct from a parse error (USAGE), with no dependency on
        // any host filesystem fact.
        String absentBinary = tempDir.resolve("no-such-faf-ice-adapter").toString();
        assertEquals(
                ExitCodes.RUNTIME,
                execute(CliTestFixtures.withSubcommand("launch-ice", absentBinary)));
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
