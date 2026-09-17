package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * here is a usage error, so nothing logs in; the passing path is covered by the live run.
 *
 * <p>The adapter path is a file that does not exist, so a case whose credentials are valid is
 * refused next for the missing binary. That refusal is what shows a credential choice went through,
 * and a losing credential list is always too short for {@code --peers}, so choosing it by mistake
 * would fail with a different message.
 */
final class SessionCommandTest {

    /** Message for the refusal that follows a credential choice that went through. */
    private static final String PASSED_CREDENTIALS = "faf-ice-adapter binary not found";

    @TempDir private Path dir;

    @Test
    void fewerTokenFilesThanPeersIsUsage() throws IOException {
        Outcome outcome = execute(Map.of(), "--peer-refresh-token-file=" + token("a"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err().contains("--peers is 2 but 1 --peer-refresh-token-file given"),
                outcome.err());
    }

    @Test
    void fewerAccessTokenFilesThanPeersIsUsageNamingThatFlag() throws IOException {
        Outcome outcome = execute(Map.of(), "--peer-access-token-file=" + token("a"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err().contains("--peers is 2 but 1 --peer-access-token-file given"),
                outcome.err());
    }

    @Test
    void noCredentialFilesIsUsageNamingBothFlags() {
        Outcome outcome = execute(Map.of());

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err().contains("session needs one credential file per peer"),
                outcome.err());
        assertTrue(outcome.err().contains("--peer-access-token-file"), outcome.err());
        assertTrue(outcome.err().contains("are not used by session"), outcome.err());
    }

    @Test
    void onePeerIsUsage() throws IOException {
        Outcome outcome =
                execute(
                        Map.of(),
                        "--peers=1",
                        "--peer-refresh-token-file=" + token("a") + "," + token("b"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains("--peers must be between 2 and 26"), outcome.err());
    }

    @Test
    void twoPeersOnOneTokenFileIsUsage() throws IOException {
        Path shared = token("a");
        Outcome outcome =
                execute(
                        Map.of(),
                        "--peer-refresh-token-file=" + shared,
                        "--peer-refresh-token-file=" + shared);

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains("also peer A's"), outcome.err());
    }

    @Test
    void twoPeersOnOneAccessTokenFileIsUsage() throws IOException {
        Path shared = token("a");
        Outcome outcome =
                execute(
                        Map.of(),
                        "--peer-access-token-file=" + shared,
                        "--peer-access-token-file=" + shared);

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains("peer B: access-token file"), outcome.err());
        assertTrue(outcome.err().contains("also peer A's"), outcome.err());
    }

    @Test
    void anUnreadableTokenFileIsUsageNamingThePeer() throws IOException {
        Outcome outcome =
                execute(
                        Map.of(),
                        "--peer-refresh-token-file=" + token("a"),
                        "--peer-refresh-token-file=" + dir.resolve("missing.txt"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err().contains("peer B: could not read OAuth refresh-token file"),
                outcome.err());
    }

    @Test
    void anEmptyAccessTokenFileIsUsageNamingTheReason() throws IOException {
        Path empty = Files.writeString(dir.resolve("empty.jwt"), "\n");
        Outcome outcome =
                execute(
                        Map.of(),
                        "--peer-access-token-file=" + token("a"),
                        "--peer-access-token-file=" + empty);

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err().contains("peer B: OAuth access-token file is empty"), outcome.err());
    }

    @Test
    void bothPeerListsOnTheCommandLineIsUsageNamingTheLayer() throws IOException {
        Outcome outcome =
                execute(
                        Map.of(),
                        "--peer-refresh-token-file=" + token("a") + "," + token("b"),
                        "--peer-access-token-file=" + token("c") + "," + token("d"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err()
                        .contains(
                                "--peer-refresh-token-file and --peer-access-token-file are both"
                                        + " set on the command line"),
                outcome.err());
    }

    @Test
    void bothPeerListsInTheEnvironmentIsUsageNamingTheLayer() throws IOException {
        Outcome outcome =
                execute(
                        Map.of(
                                "FAF_MOCK_CLIENT_PEER_REFRESH_TOKEN_FILE",
                                token("a") + "," + token("b"),
                                "FAF_MOCK_CLIENT_PEER_ACCESS_TOKEN_FILE",
                                token("c") + "," + token("d")));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(
                outcome.err().contains("are both set in FAF_MOCK_CLIENT_* environment variables"),
                outcome.err());
    }

    @Test
    void accessTokensOnTheCommandLineBeatRefreshTokensFromTheEnvironment() throws IOException {
        // The environment's list is too short for two peers, so choosing it would be refused for
        // the count instead of reaching the binary check.
        Outcome outcome =
                execute(
                        Map.of("FAF_MOCK_CLIENT_PEER_REFRESH_TOKEN_FILE", token("a").toString()),
                        "--peer-access-token-file=" + token("c") + "," + token("d"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains(PASSED_CREDENTIALS), outcome.err());
    }

    @Test
    void refreshTokensOnTheCommandLineBeatAccessTokensFromTheEnvironment() throws IOException {
        Outcome outcome =
                execute(
                        Map.of("FAF_MOCK_CLIENT_PEER_ACCESS_TOKEN_FILE", token("c").toString()),
                        "--peer-refresh-token-file=" + token("a") + "," + token("b"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains(PASSED_CREDENTIALS), outcome.err());
    }

    @Test
    void accessTokenFilesAreReadFromTheConfigFile() throws IOException {
        Path config =
                Files.writeString(
                        dir.resolve("mock-client.json"),
                        "{\"peerAccessTokenFiles\": \"" + token("c") + "," + token("d") + "\"}");

        Outcome outcome = execute(Map.of(), "--config=" + config);

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertTrue(outcome.err().contains(PASSED_CREDENTIALS), outcome.err());
    }

    @Test
    void aRootAccessTokenDoesNotCollideWithPeerRefreshTokens() throws IOException {
        Outcome outcome =
                execute(
                        Map.of(),
                        "--oauth-access-token-file=" + token("root"),
                        "--peer-refresh-token-file=" + token("a") + "," + token("b"));

        assertEquals(ExitCodes.USAGE, outcome.exitCode(), outcome.err());
        assertFalse(outcome.err().contains("two OAuth credential channels"), outcome.err());
        assertTrue(outcome.err().contains(PASSED_CREDENTIALS), outcome.err());
    }

    /**
     * An exit code and what picocli wrote to its error stream.
     *
     * @param exitCode the exit code
     * @param err the error output
     */
    private record Outcome(int exitCode, String err) {}

    private Outcome execute(final Map<String, String> env, final String... sessionArgs) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                CliTestFixtures.withSubcommandAndIceBinary(
                                        "session", dir.resolve("no-such-adapter.jar").toString())));
        args.addAll(List.of(sessionArgs));
        String[] argv = args.toArray(new String[0]);
        CommandLine cmd = ConfigLoader.newCommandLine(argv, env);
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute(argv);
        return new Outcome(exitCode, err.toString());
    }

    private Path token(final String name) throws IOException {
        return Files.writeString(dir.resolve("token_" + name + ".txt"), "token-" + name);
    }
}
