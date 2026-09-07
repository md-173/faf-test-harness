package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Unit-level tests for {@link LayeredDefaultProvider}: env-var derivation, JSON-key derivation via
 * the underlying field, blank-string handling, JSON numeric round trips, and constructor-time file
 * validation. Bypasses the full {@link ConfigLoader} pipeline so failures point at the provider
 * rather than picocli.
 */
final class LayeredDefaultProviderTest {

    /** Vertical tab — a Java {@code \R} terminator with no string-literal escape. */
    private static final char VT = 0x000B;

    /** Next line. The only terminator observed to survive Jackson into a parse message. */
    private static final char NEL = 0x0085;

    /** Line separator. */
    private static final char LS = 0x2028;

    /** Paragraph separator. */
    private static final char PS = 0x2029;

    @Test
    void envValueIsReturnedWhenSet() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", "ws://from-env/ws");

        LayeredDefaultProvider provider = new LayeredDefaultProvider(env, null);

        assertEquals(
                "ws://from-env/ws", provider.defaultValue(optionSpecFor("--lobby-websocket-url")));
    }

    @Test
    void blankEnvValueIsTreatedAsUnset(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, TestFixtures.minimalRequiredJson());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", "");

        LayeredDefaultProvider provider = new LayeredDefaultProvider(env, file);

        assertEquals(
                TestFixtures.LOBBY_URL,
                provider.defaultValue(optionSpecFor("--lobby-websocket-url")),
                "Blank env value should fall through to the file layer.");
    }

    @Test
    void jsonStringValueIsReturnedFromFile(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, TestFixtures.minimalRequiredJson());

        LayeredDefaultProvider provider = new LayeredDefaultProvider(Map.of(), file);

        assertEquals(
                TestFixtures.LOBBY_URL,
                provider.defaultValue(optionSpecFor("--lobby-websocket-url")));
    }

    @Test
    void jsonNumericValueRoundTripsAsString(@TempDir final Path tempDir) throws Exception {
        String json =
                """
                {
                  "iceAdapterRpcPort": 8765
                }
                """;
        Path file = tempDir.resolve("port.json");
        Files.writeString(file, json);

        LayeredDefaultProvider provider = new LayeredDefaultProvider(Map.of(), file);

        assertEquals(
                "8765",
                provider.defaultValue(optionSpecFor("--ice-adapter-rpc-port")),
                "Numeric JSON values should be stringified for picocli's int converter.");
    }

    @Test
    void unsetFieldReturnsNullSoPicocliFallsBackToBuiltInDefault() {
        LayeredDefaultProvider provider = new LayeredDefaultProvider(Map.of(), null);

        assertNull(provider.defaultValue(optionSpecFor("--ice-adapter-rpc-port")));
    }

    @Test
    void envBeatsFileForSameField(@TempDir final Path tempDir) throws Exception {
        Path file = TestFixtures.writeJson(tempDir, TestFixtures.minimalRequiredJson());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAF_MOCK_CLIENT_LOBBY_WEBSOCKET_URL", "ws://from-env/ws");

        LayeredDefaultProvider provider = new LayeredDefaultProvider(env, file);

        assertEquals(
                "ws://from-env/ws", provider.defaultValue(optionSpecFor("--lobby-websocket-url")));
    }

    @Test
    void unreadableConfigFileThrowsAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new LayeredDefaultProvider(
                                Map.of(), Path.of("/path/that/does/not/exist.json")));
    }

    @Test
    void oneLineEscapesEveryLineTerminator() {
        // Every terminator Java's \R recognises, not just the three obvious ones — and the exotic
        // end of this list is the reachable end: LF and CR never survive Jackson's token
        // accumulator, whereas NEL does. See parseFailureDiagnosticStaysOnOneLine below.
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a\nb"), "LF");
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a\r\nb"), "CRLF");
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a\rb"), "CR");
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a" + VT + "b"), "VT");
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a\fb"), "FF");
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a" + NEL + "b"), "NEL");
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a" + LS + "b"), "LS");
        assertEquals("a\\nb", LayeredDefaultProvider.oneLine("a" + PS + "b"), "PS");
        assertEquals("plain", LayeredDefaultProvider.oneLine("plain"));
        assertEquals("null", LayeredDefaultProvider.oneLine(null));
    }

    @Test
    void parseFailureDiagnosticStaysOnOneLine(@TempDir final Path tempDir) throws Exception {
        // Guards the call site, not the helper: reverting any oneLine(...) wrap inside
        // readJsonFile must fail a test. NEL is the reachable case — Jackson renders LF and CR as
        // "(CTRL-CHAR, code N)", but its unrecognised-token accumulator copies NEL through raw
        // into getOriginalMessage(), and Java's \R treats NEL as a line terminator.
        Path file = tempDir.resolve("nel.json");
        Files.writeString(file, "{\"a\": t" + NEL + "rue}");

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new LayeredDefaultProvider(Map.of(), file));

        assertEquals(
                1, e.getMessage().split("\\R").length, "diagnostic spans lines: " + e.getMessage());
    }

    @Test
    void oneLineDefeatsAForgedUsageBoundary() {
        // The escaping exists so no interpolated value can fake the line that separates the error
        // from picocli's usage block. This holds for a library's message as much as for a path:
        // every operand of these diagnostics goes through oneLine, not just the filename.
        String forged = "boom\nUsage: mock-client FORGED";
        String rendered = LayeredDefaultProvider.oneLine(forged);

        assertEquals(1, rendered.split("\\R").length, "value still spans lines: " + rendered);
        assertTrue(rendered.endsWith("FORGED"), "value was truncated: " + rendered);
    }

    /**
     * Borrow a real {@link OptionSpec} from a {@link MockClientCli} so the provider sees the same
     * option metadata picocli would supply at runtime — including the underlying {@code Field}
     * reference used for JSON-key derivation.
     */
    private static OptionSpec optionSpecFor(final String flag) {
        OptionSpec opt = new CommandLine(new MockClientCli()).getCommandSpec().findOption(flag);
        if (opt == null) {
            throw new IllegalStateException("Unknown CLI flag in test: " + flag);
        }
        return opt;
    }
}
