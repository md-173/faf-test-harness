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
 * Verifies that picocli routes {@code mock-client <subcommand> ...} to the matching command. {@code
 * ice-smoke} is still a stub returning {@link ExitCodes#NOT_IMPLEMENTED}; {@code run}, {@code
 * launch-ice}, and {@code launch-game} are implemented and exit {@link ExitCodes#RUNTIME} on the
 * minimal fixture (no usable token file / missing binary). Either way the exit code differs from a
 * parse error ({@link ExitCodes#USAGE}), so it is a sufficient dispatch signal.
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
        // run is implemented (WBS-3.1.1.4). The minimal fixture supplies a literal
        // --oauth-refresh-token but no --oauth-refresh-token-file; TokenSources only supports the
        // file channel, so run routes through and fails fast with RUNTIME before any network I/O —
        // a dispatch signal distinct from a parse error (USAGE), with no live-lobby dependency.
        assertEquals(ExitCodes.RUNTIME, dispatch("run"));
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
                execute(CliTestFixtures.withSubcommandAndIceBinary("launch-ice", absentBinary)));
    }

    @Test
    void launchGameDispatchesToLaunchGameCommand() {
        // launch-game is implemented (WBS-3.1.2.3). Same pattern as launch-ice: point at a
        // guaranteed-absent path under the temp dir so the dispatch assertion does not silently
        // depend on the Gradle install layout being present.
        String absentBinary = tempDir.resolve("no-such-mock-game").toString();
        assertEquals(
                ExitCodes.RUNTIME,
                execute(CliTestFixtures.withSubcommandAndGameBinary("launch-game", absentBinary)));
    }

    @Test
    void iceSmokeDispatchesToIceSmokeCommand() {
        assertEquals(ExitCodes.NOT_IMPLEMENTED, dispatch("ice-smoke"));
    }
}
