package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Covers error paths. Every loader-surfaced failure is wrapped as a {@link
 * CommandLine.ParameterException} so callers (e.g. {@code Main}) can apply a single config-error
 * exit policy. Bad config-file inputs originate as {@link IllegalArgumentException} inside {@link
 * LayeredDefaultProvider} and are converted by {@link ConfigLoader}.
 */
final class ConfigLoaderInvalidValuesTest {

    @Test
    void nonNumericPortThrowsParameterException() {
        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {"--ice-adapter-rpc-port=not-a-number"});

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        String lower = ex.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(
                lower.contains("ice-adapter-rpc-port") || lower.contains("not-a-number"),
                "Error message should reference the bad field or its value. Got: "
                        + ex.getMessage());
    }

    @Test
    void malformedUriThrowsParameterException() {
        String[] args =
                replaceArg(
                        TestFixtures.minimalRequiredCli(),
                        "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                        "--lobby-websocket-url=not a uri");

        assertThrows(CommandLine.ParameterException.class, () -> ConfigLoader.load(args, Map.of()));
    }

    /**
     * A config file that stops being JSON partway through used to load anyway (WBS-3.1.5.1-fix,
     * #290). {@code readTree} takes the first complete value and discards the rest, so a file
     * truncated and re-appended, or concatenated by a bad generator, produced a "missing required
     * option" naming a key the operator can see in their own file.
     */
    @Test
    void trailingContentAfterTheRootObjectFailsTheLoad(@TempDir final Path tempDir)
            throws Exception {
        assertConfigRejected(tempDir, "{\"lobbyWebSocketUrl\":\"wss://a\"} SLOP {\"x\":1}");
    }

    /** The concatenation case: two whole objects, of which only the first was ever read. */
    @Test
    void twoConcatenatedObjectsFailTheLoad(@TempDir final Path tempDir) throws Exception {
        assertConfigRejected(
                tempDir, "{\"lobbyWebSocketUrl\":\"wss://a\"}{\"clientVersion\":\"2\"}");
    }

    /**
     * Last-wins on a duplicate key is a defensible convention but not an unstated one: the same key
     * twice is a mistake far more often than it is a layering trick, and the loader already has
     * three explicit layers for that.
     */
    @Test
    void duplicateKeysFailTheLoad(@TempDir final Path tempDir) throws Exception {
        assertConfigRejected(
                tempDir, "{\"lobbyWebSocketUrl\":\"wss://a\",\"lobbyWebSocketUrl\":\"wss://b\"}");
    }

    /** A comment is not JSON either, and was silently truncating the file at the same point. */
    @Test
    void trailingCommentFailsTheLoad(@TempDir final Path tempDir) throws Exception {
        assertConfigRejected(tempDir, "{\"lobbyWebSocketUrl\":\"wss://a\"} // why not");
    }

    /** The control: a well-formed file is still read, so the strictness cost nothing legitimate. */
    @Test
    void aWellFormedConfigFileIsUnaffected(@TempDir final Path tempDir) throws Exception {
        Path file = tempDir.resolve("good.json");
        Files.writeString(file, "{\"clientVersion\":\"9.9.9-test\"}");

        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {"--config", file.toString()});

        assertEquals("9.9.9-test", ConfigLoader.load(args, Map.of()).orElseThrow().clientVersion());
    }

    /** Writes {@code content} as a config file and asserts the load rejects it, naming the file. */
    private static void assertConfigRejected(final Path tempDir, final String content)
            throws Exception {
        Path file = tempDir.resolve("bad.json");
        Files.writeString(file, content);

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                ConfigLoader.load(
                                        new String[] {"--config", file.toString()}, Map.of()));

        assertTrue(
                ex.getMessage().contains(file.toString()),
                "the diagnostic must name the offending file. Got: " + ex.getMessage());
        assertEquals(
                1,
                ex.getMessage().lines().count(),
                "config diagnostics stay on one line. Got: " + ex.getMessage());
    }

    @Test
    void unreadableConfigFileThrowsParameterException() {
        String[] args = {"--config", "/does/not/exist.json"};

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(
                ex.getMessage().contains("config file is not readable"),
                "Unreadable file should be self-describing. Got: " + ex.getMessage());
    }

    @Test
    void nonObjectRootJsonThrowsParameterException(@TempDir final Path tempDir) throws Exception {
        Path file = tempDir.resolve("bad.json");
        Files.writeString(file, "[]");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                ConfigLoader.load(
                                        new String[] {"--config", file.toString()}, Map.of()));

        assertTrue(
                ex.getMessage().contains("must be a JSON object"),
                "Error message should explain the root must be an object. Got: " + ex.getMessage());
    }

    @Test
    void malformedJsonThrowsParameterException(@TempDir final Path tempDir) throws Exception {
        Path file = tempDir.resolve("malformed.json");
        Files.writeString(file, "{");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () ->
                                ConfigLoader.load(
                                        new String[] {"--config", file.toString()}, Map.of()));

        assertTrue(
                ex.getMessage().toLowerCase(Locale.ROOT).contains("parse"),
                "Error message should mention parse failure. Got: " + ex.getMessage());
    }

    @Test
    void unknownCliFlagThrowsParameterException() {
        String[] args =
                concat(TestFixtures.minimalRequiredCli(), new String[] {"--no-such-flag=whatever"});

        assertThrows(CommandLine.ParameterException.class, () -> ConfigLoader.load(args, Map.of()));
    }

    private static String[] concat(final String[] a, final String[] b) {
        return Stream.concat(Arrays.stream(a), Arrays.stream(b)).toArray(String[]::new);
    }

    private static String[] replaceArg(
            final String[] original, final String oldArg, final String newArg) {
        String[] copy = original.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i].equals(oldArg)) {
                copy[i] = newArg;
                return copy;
            }
        }
        throw new IllegalStateException(
                "Did not find " + oldArg + " in " + Arrays.toString(original));
    }
}
