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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * Covers error paths. Every loader-surfaced failure is wrapped as a {@link
 * CommandLine.ParameterException} so callers (e.g. {@code Main}) can apply a single config-error
 * exit policy. Bad config-file inputs originate as {@link IllegalArgumentException} inside {@link
 * LayeredDefaultProvider} and are converted by {@link ConfigLoader}.
 */
final class ConfigLoaderInvalidValuesTest {

    /** How the message refusing a lobby URL the JDK's WebSocket client would refuse begins. */
    private static final String LOBBY_URL_RULE =
            "--lobby-websocket-url must be a ws:// or wss:// URL with a host, a port no higher than"
                    + " 65535 and no fragment";

    /** How the message refusing a token URL the JDK's HTTP client would refuse begins. */
    private static final String TOKEN_URL_RULE =
            "--oauth-token-url must be an http:// or https:// URL with a host and a port no higher"
                    + " than 65535";

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
     * The WBS-5.1 drop percentage, rejected at load time on the path an operator actually takes.
     * Its sibling {@code --ice-relay-delay-ms} got this test in the same branch, for the same
     * reason: the range check lives in {@link MockClientConfig}'s compact constructor, and without
     * a test here nothing covers the CLI route into it.
     *
     * @param value an out-of-range percentage
     */
    @ParameterizedTest
    @ValueSource(strings = {"-1", "101"})
    void outOfRangeGameUdpDropPercentThrowsParameterException(final String value) {
        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {"--mock-game-udp-drop-percent=" + value});

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        String lower = ex.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(
                lower.contains("mockgameudpdroppercent")
                        || lower.contains("mock-game-udp-drop-percent"),
                "the rejection must name the option the operator typed, got: " + ex.getMessage());
    }

    /**
     * The env var and the JSON key move with the flag. Both are derived from the option's long name
     * rather than declared anywhere, so a rename changes them silently and nothing else would catch
     * a mismatch. The env var is what CI sets and the JSON key is what a config file carries, so
     * neither is covered by the CLI cases above.
     */
    @Test
    void theEnvVarAndJsonKeyFollowTheRenamedFlag(@TempDir final Path tempDir) throws Exception {
        MockClientConfig fromEnv =
                ConfigLoader.load(
                                TestFixtures.minimalRequiredCli(),
                                Map.of("FAF_MOCK_CLIENT_MOCK_GAME_UDP_DROP_PERCENT", "37"))
                        .orElseThrow();
        assertEquals(37, fromEnv.mockGameUdpDropPercent(), "the env var must reach the field");

        Path cfg =
                Files.writeString(tempDir.resolve("cfg.json"), "{\"mockGameUdpDropPercent\": 23}");
        MockClientConfig fromFile =
                ConfigLoader.load(
                                concat(
                                        TestFixtures.minimalRequiredCli(),
                                        new String[] {"--config=" + cfg}),
                                Map.of())
                        .orElseThrow();
        assertEquals(23, fromFile.mockGameUdpDropPercent(), "the JSON key must reach the field");
    }

    /**
     * Both ends of the accepted range load. Without this the test above would pass just as well
     * against a check that rejected everything.
     *
     * @param value a percentage at the edge of the accepted range
     */
    @ParameterizedTest
    @ValueSource(strings = {"0", "100"})
    void boundaryGameUdpDropPercentLoads(final String value) {
        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {"--mock-game-udp-drop-percent=" + value});

        MockClientConfig config =
                ConfigLoader.load(args, Map.of())
                        .orElseThrow(() -> new AssertionError("config did not load for " + value));
        assertEquals(Integer.parseInt(value), config.mockGameUdpDropPercent());
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

    /**
     * A lobby URL the JDK's WebSocket client refuses used to parse, pass validation and fail only
     * at connect, with exit 70 (#522). In order: no scheme, and so no host; a host and port with no
     * scheme, where the host parses as the scheme and no host is left; a scheme other than ws or
     * wss; no host; a name with an underscore, from which the JDK reads no host; a port above
     * 65535; a fragment.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ws.faforever.xyz",
                "ws.faforever.xyz:443",
                "https://ws.faforever.xyz",
                "wss:///lobby",
                "ws://faf_lobby:8080",
                "wss://ws.faforever.xyz:65536",
                "wss://ws.faforever.xyz#lobby"
            })
    void aLobbyUrlTheJdkWouldRefuseThrowsParameterException(final String url) {
        String[] args =
                replaceArg(
                        TestFixtures.minimalRequiredCli(),
                        "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                        "--lobby-websocket-url=" + url);

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(ex.getMessage().contains(LOBBY_URL_RULE), ex.getMessage());
    }

    /**
     * A token URL the JDK's HTTP client refuses, on the refresh channel that reads it, passed
     * validation too and failed at the token exchange, after the lobby connected, also with exit 70
     * (#522). In order: no scheme, a scheme other than http or https, no host, a port above 65535.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "hydra.faforever.xyz/oauth2/token",
                "ftp://hydra.faforever.xyz/oauth2/token",
                "https:///oauth2/token",
                "https://hydra.faforever.xyz:65536/oauth2/token"
            })
    void aTokenUrlTheJdkWouldRefuseThrowsParameterException(final String url) {
        String[] args =
                replaceArg(
                        TestFixtures.minimalRequiredCli(),
                        "--oauth-token-url=" + TestFixtures.OAUTH_TOKEN_URL,
                        "--oauth-token-url=" + url);

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(ex.getMessage().contains(TOKEN_URL_RULE), ex.getMessage());
    }

    /**
     * Nothing the JDK's WebSocket client takes is refused (#522): a scheme in capitals, plain ws as
     * a local stack serves the lobby, the highest port, and the {@code /?verify=} query
     * FAForever/client opens its lobby access URL with. Each loads unchanged.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "WSS://ws.faforever.xyz",
                "ws://127.0.0.1:8080",
                "wss://ws.faforever.xyz:65535",
                "wss://ws.faforever.xyz/?verify=abc"
            })
    void aLobbyUrlTheJdkWouldTakeLoadsUnchanged(final String url) {
        String[] args =
                replaceArg(
                        TestFixtures.minimalRequiredCli(),
                        "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                        "--lobby-websocket-url=" + url);

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(url, config.lobbyWebSocketUrl().toString());
    }

    /**
     * Likewise for the token URL (#522): a scheme in capitals, plain http as a local Hydra serves
     * it, and a fragment, which the JDK's HTTP client does not check.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTPS://hydra.faforever.xyz/oauth2/token",
                "http://localhost:4444/oauth2/token",
                "https://hydra.faforever.xyz/oauth2/token#x"
            })
    void aTokenUrlTheJdkWouldTakeLoadsUnchanged(final String url) {
        String[] args =
                replaceArg(
                        TestFixtures.minimalRequiredCli(),
                        "--oauth-token-url=" + TestFixtures.OAUTH_TOKEN_URL,
                        "--oauth-token-url=" + url);

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(url, config.oauthTokenUrl().toString());
    }

    @Test
    void aLobbyUrlTheJdkWouldRefuseThrowsOnTheAccessTokenChannelToo() {
        // Every channel reads the lobby URL, and the access-token one is what CI's live session
        // and a consumer's job run on, so the check must not sit behind the token URL's (#522).
        String[] args =
                replaceArg(
                        replaceArg(
                                TestFixtures.minimalRequiredCli(),
                                "--oauth-refresh-token-file="
                                        + TestFixtures.OAUTH_REFRESH_TOKEN_FILE,
                                "--oauth-access-token-file=/nonexistent/test-access-token"),
                        "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                        "--lobby-websocket-url=ws.faforever.xyz:443");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(ex.getMessage().contains(LOBBY_URL_RULE), ex.getMessage());
    }

    @Test
    void aTokenUrlIsNotCheckedOnTheAccessTokenChannel() {
        // That channel reads no token URL (#340), so not even one the JDK would refuse stops it
        // (#522).
        String[] args =
                replaceArg(
                        replaceArg(
                                TestFixtures.minimalRequiredCli(),
                                "--oauth-refresh-token-file="
                                        + TestFixtures.OAUTH_REFRESH_TOKEN_FILE,
                                "--oauth-access-token-file=/nonexistent/test-access-token"),
                        "--oauth-token-url=" + TestFixtures.OAUTH_TOKEN_URL,
                        "--oauth-token-url=ftp://hydra.faforever.xyz/oauth2/token");

        assertTrue(ConfigLoader.load(args, Map.of()).isPresent());
    }

    @Test
    void aLobbyUrlTheJdkWouldRefuseFromTheEnvironmentThrowsParameterException() {
        // The record sees the value whichever layer set it, so the environment cannot slip an
        // unusable URL past the check (#522). A blank variable would count as unset instead.
        String[] args =
                Arrays.stream(TestFixtures.minimalRequiredCli())
                        .filter(arg -> !arg.startsWith("--lobby-websocket-url="))
                        .toArray(String[]::new);
        Map<String, String> env = Map.of("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", "ws.faforever.xyz");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class, () -> ConfigLoader.load(args, env));

        assertTrue(ex.getMessage().contains(LOBBY_URL_RULE), ex.getMessage());
    }

    @Test
    void aRefusedUrlShowsAnInvisibleCharacterAsItsCodePoint() {
        // A zero-width space, U+200B, pasted after the host leaves no host the JDK can read, and
        // quoted as typed the URL would look valid. Quoted by code point, it shows where it is
        // (#522).
        String[] args =
                replaceArg(
                        TestFixtures.minimalRequiredCli(),
                        "--lobby-websocket-url=" + TestFixtures.LOBBY_URL,
                        "--lobby-websocket-url=wss://ws.faforever.xyz"
                                + Character.toString(0x200B));

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(
                ex.getMessage().contains("got 'wss://ws.faforever.xyz<U+200B>'"), ex.getMessage());
    }

    @Test
    void aLoneSurrogateFromTheConfigFileIsStillAUsageError(@TempDir final Path tempDir)
            throws Exception {
        // A JSON escape can carry a lone surrogate, and the URI then parses with no host. Quoting
        // it must not fail: URI.toASCIIString threw a NullPointerException on one, which exited 70
        // rather than 2 (#522).
        Path cfg =
                Files.writeString(
                        tempDir.resolve("cfg.json"),
                        "{\"lobbyWebSocketUrl\": \"wss://ws.faforever.xyz\\uD800\"}");
        String[] args =
                concat(
                        Arrays.stream(TestFixtures.minimalRequiredCli())
                                .filter(arg -> !arg.startsWith("--lobby-websocket-url="))
                                .toArray(String[]::new),
                        new String[] {"--config=" + cfg});

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        assertTrue(ex.getMessage().contains(LOBBY_URL_RULE), ex.getMessage());
        assertTrue(ex.getMessage().contains("<U+D800>"), ex.getMessage());
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

    /**
     * A duplicate key whose name carries a newline must not forge a usage boundary.
     *
     * <p>This is a strictly easier vector than the one {@code parseFailureDiagnosticStaysOnOneLine}
     * covers. That test deliberately uses NEL, because on the unrecognised-token path Jackson
     * truncates the token at an LF and the newline never reaches the message. The duplicate-field
     * path added here behaves differently: {@code STRICT_DUPLICATE_DETECTION} interpolates the
     * offending field name into its message verbatim, so a plain {@code \n} passes straight
     * through. {@code oneLine} in the parse-failure diagnostic is what closes it.
     */
    @Test
    void aDuplicateKeyCannotForgeAUsageBoundary(@TempDir final Path tempDir) throws Exception {
        assertConfigRejected(tempDir, "{\"a\\nUsage: forged\":1,\"a\\nUsage: forged\":2}");
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
