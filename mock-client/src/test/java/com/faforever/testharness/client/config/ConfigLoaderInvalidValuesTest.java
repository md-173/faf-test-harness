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

    /**
     * The WBS-5.1 relay delay, rejected at load time rather than from inside a live session. The
     * range check lives in {@link MockClientConfig}'s compact constructor and had no test on this
     * path, which is the one an operator actually takes.
     */
    @Test
    void negativeIceRelayDelayThrowsParameterException() {
        String[] args =
                concat(TestFixtures.minimalRequiredCli(), new String[] {"--ice-relay-delay-ms=-1"});

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        String lower = ex.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(
                lower.contains("icerelaydelayms") || lower.contains("ice-relay-delay-ms"),
                "Error message should name the offending option. Got: " + ex.getMessage());
    }

    /**
     * {@code --queue-faction} without {@code --queue-name} used to build no queue config at all, so
     * the session started, never queued, and sat in IDLE until killed (#304 review). {@code
     * buildQueueConfig} now triggers on either option so the record names the missing one.
     */
    @Test
    void queueFactionWithoutQueueNameThrowsParameterException() {
        String[] args =
                concat(TestFixtures.minimalRequiredCli(), new String[] {"--queue-faction=3"});

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(
                ex.getMessage().contains("--queue-name"),
                "Error message should name the missing option. Got: " + ex.getMessage());
    }

    /**
     * An out-of-range faction used to reach the wire and fail opaquely inside faf-server's {@code
     * Faction.from_value} (#304 review). The bound is the server enum's, so {@code nomad=5} stays
     * valid and only genuinely undecodable values are rejected.
     */
    @Test
    void outOfRangeQueueFactionThrowsParameterException() {
        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {"--queue-name=ladder1v1", "--queue-faction=7"});

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(
                ex.getMessage().contains("--queue-faction"),
                "Error message should name the offending option. Got: " + ex.getMessage());
    }

    /** The server's own upper bound, {@code nomad=5}, must not be rejected. */
    @Test
    void queueFactionAtServerUpperBoundIsAccepted() {
        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {"--queue-name=ladder1v1", "--queue-faction=5"});

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertTrue(config.queueConfig().isPresent(), "queue config should be built");
        assertTrue(
                config.queueConfig().get().faction().isPresent()
                        && config.queueConfig().get().faction().get() == 5,
                "nomad should survive validation");
    }

    /**
     * Hosting, joining and queueing all fire on the same IDLE entry, so combining them sends
     * conflicting intents; queueing while in a custom game is also how a matchmaker violation is
     * earned (#304 review, #224 operational risk).
     */
    @Test
    void queueingAndHostingTogetherThrowsParameterException() {
        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {
                            "--queue-name=ladder1v1",
                            "--host-title=Test Game",
                            "--host-map=scmp_007",
                            "--host-mod=faf",
                            "--host-visibility=public",
                        });

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        String message = ex.getMessage();
        assertTrue(
                message.contains("--queue-name") && message.contains("--host-"),
                "Error message should name both conflicting intents. Got: " + message);
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
