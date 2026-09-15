package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The {@code session} refusals that must happen before any process starts (WBS-4.2.1). Every case
 * here is a usage error, so nothing logs in and no binary is needed; the passing path is covered by
 * the live run.
 */
final class SessionCommandTest {

    @TempDir private Path dir;

    @Test
    void fewerTokenFilesThanPeersIsUsage() throws IOException {
        Outcome outcome = execute("--peer-refresh-token-file=" + token("a"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err().contains("--peers is 2 but 1 --peer-refresh-token-file given"),
                outcome.err());
    }

    @Test
    void noTokenFilesIsUsage() {
        Outcome outcome = execute();

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains("but 0 --peer-refresh-token-file"), outcome.err());
    }

    @Test
    void onePeerIsUsage() throws IOException {
        Outcome outcome =
                execute("--peers=1", "--peer-refresh-token-file=" + token("a") + "," + token("b"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains("--peers must be between 2 and 26"), outcome.err());
    }

    @Test
    void twoPeersOnOneTokenFileIsUsage() throws IOException {
        Path shared = token("a");
        Outcome outcome =
                execute(
                        "--peer-refresh-token-file=" + shared,
                        "--peer-refresh-token-file=" + shared);

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains("also peer A's"), outcome.err());
    }

    @Test
    void anUnreadableTokenFileIsUsageNamingThePeer() throws IOException {
        Outcome outcome =
                execute(
                        "--peer-refresh-token-file=" + token("a"),
                        "--peer-refresh-token-file=" + dir.resolve("missing.txt"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains("peer B: cannot read"), outcome.err());
    }

    /**
     * An exit code and what picocli wrote to its error stream.
     *
     * @param exitCode the exit code
     * @param err the error output
     */
    private record Outcome(int exitCode, String err) {}

    private static Outcome execute(final String... sessionArgs) {
        List<String> args = new ArrayList<>(List.of(CliTestFixtures.withSubcommand("session")));
        args.addAll(List.of(sessionArgs));
        String[] argv = args.toArray(new String[0]);
        CommandLine cmd = ConfigLoader.newCommandLine(argv, Map.of());
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(argv);
        return new Outcome(exitCode, err.toString());
    }

    private Path token(final String name) throws IOException {
        return Files.writeString(dir.resolve("refresh_token_" + name + ".txt"), "token-" + name);
    }
}
