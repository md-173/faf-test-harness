package com.faforever.testharness.client.config;

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
 * Covers error paths: bad CLI values surface as picocli {@link CommandLine.ParameterException}s;
 * bad config-file inputs surface as {@link IllegalArgumentException} from {@link
 * LayeredDefaultProvider}.
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

    @Test
    void unreadableConfigFileThrowsIllegalArgumentException() {
        String[] args = new String[] {"--config", "/path/that/does/not/exist.json"};

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class, () -> ConfigLoader.load(args, Map.of()));

        assertTrue(
                ex.getMessage().contains("not readable"),
                "Error message should explain the file is unreadable. Got: " + ex.getMessage());
    }

    @Test
    void nonObjectRootJsonThrowsIllegalArgumentException(@TempDir final Path tempDir)
            throws Exception {
        Path file = tempDir.resolve("bad.json");
        Files.writeString(file, "[]");

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                ConfigLoader.load(
                                        new String[] {"--config", file.toString()}, Map.of()));

        assertTrue(
                ex.getMessage().contains("must be a JSON object"),
                "Error message should explain the root must be an object. Got: " + ex.getMessage());
    }

    @Test
    void malformedJsonThrowsIllegalArgumentException(@TempDir final Path tempDir) throws Exception {
        Path file = tempDir.resolve("malformed.json");
        Files.writeString(file, "{");

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
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
