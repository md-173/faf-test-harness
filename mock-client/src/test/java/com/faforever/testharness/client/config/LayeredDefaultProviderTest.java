package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
