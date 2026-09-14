package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Pins {@link MockGameSettings} as a faithful narrowing of {@link MockClientConfig}.
 *
 * <p>Neither narrowing record had a direct test, while the full record has {@code
 * MockClientCliRecordSyncTest} as a drift guard. That gap is what let {@code launch-game} and the
 * lobby-driven path disagree about game options while {@code check} stayed green (WBS-3.1.6.3
 * review), so the field-for-field comparison below is the point of this class rather than a
 * formality.
 */
final class MockGameSettingsTest {

    private static MockGameSettings valid(final Map<String, String> options) {
        return new MockGameSettings(
                Path.of("/bin/mock-game"),
                7237,
                7238,
                4711,
                OptionalInt.of(42),
                "Rhiza",
                5,
                options,
                "INFO",
                Optional.empty());
    }

    @Test
    void aBlankPlayerLoginIsRejected() {
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new MockGameSettings(
                                        Path.of("/bin/mock-game"),
                                        7237,
                                        7238,
                                        4711,
                                        OptionalInt.empty(),
                                        "   ",
                                        5,
                                        Map.of(),
                                        "INFO",
                                        Optional.empty()));

        assertTrue(
                ex.getMessage().contains("--player-login"),
                "the rejection must name the flag it becomes; got: " + ex.getMessage());
    }

    @Test
    void gameOptionsKeepTheirInsertionOrder() {
        // buildArgv iterates this to emit --game-option, and the runbook transcribes that line, so
        // the order is observable. Map.copyOf would randomise it per JVM.
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("Victory", "demoralization");
        ordered.put("Share", "ShareUntilDeath");
        ordered.put("Slots", "8");
        ordered.put("Fog", "explored");

        assertEquals(
                List.copyOf(ordered.keySet()),
                List.copyOf(valid(ordered).gameOptions().keySet()),
                "game options must iterate in the order they were configured");
    }

    @Test
    void gameOptionsAreImmutable() {
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        valid(new LinkedHashMap<>(Map.of("Victory", "demoralization")))
                                .gameOptions()
                                .put("Share", "ShareUntilDeath"));
    }

    @Test
    void launchGameDropsHostOptionsWhenNoHostIsConfigured() {
        // The divergence this class exists for. toValidatedGameSettings forwarded the raw
        // --host-game-option map while from(config) gates it on hostConfig, so `launch-game
        // --host-game-option X=Y` with no host flags emitted --game-option on one path and not the
        // other — contradicting MockGameSettings' javadoc that both routes launch mock-game
        // identically.
        CommandLine cmd = ConfigLoader.newCommandLine(TestFixtures.minimalRequiredCli(), Map.of());
        cmd.parseArgs(
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {"--host-game-option", "Victory=demoralization"}));
        MockClientCli cli = cmd.getCommand();

        MockGameSettings settings = cli.toValidatedGameSettings(cmd.getCommandSpec());

        assertTrue(
                settings.gameOptions().isEmpty(),
                "a run with no host flags configures no host, so it sends no game options; got: "
                        + settings.gameOptions());
    }

    @Test
    void launchGameForwardsHostOptionsWhenAHostIsConfigured() {
        CommandLine cmd = ConfigLoader.newCommandLine(TestFixtures.minimalRequiredCli(), Map.of());
        String[] args =
                concat(
                        TestFixtures.minimalRequiredCli(),
                        new String[] {
                            "--host-title=Test game",
                            "--host-map=scmp_007",
                            "--host-mod=faf",
                            "--host-visibility=public",
                            "--host-game-option",
                            "Victory=demoralization"
                        });
        cmd.parseArgs(args);
        MockClientCli cli = cmd.getCommand();

        MockGameSettings settings = cli.toValidatedGameSettings(cmd.getCommandSpec());

        assertEquals(Map.of("Victory", "demoralization"), settings.gameOptions());
    }

    private static String[] concat(final String[] a, final String[] b) {
        return Stream.concat(Stream.of(a), Stream.of(b)).toArray(String[]::new);
    }

    @Test
    void fromConfigNarrowsEveryFieldItClaimsTo() {
        // The comparison that would have caught launch-game forwarding host options the
        // lobby-driven path drops. Loaded through the real CLI, so the narrowing is checked
        // against a config an operator could actually produce.
        MockClientConfig config =
                ConfigLoader.load(TestFixtures.minimalRequiredCli(), Map.of()).orElseThrow();
        MockGameSettings narrowed = MockGameSettings.from(config);

        assertEquals(config.mockGameBinaryPath(), narrowed.binaryPath());
        assertEquals(config.iceAdapterGpgNetPort(), narrowed.gpgNetPort());
        assertEquals(config.iceAdapterLobbyPort(), narrowed.lobbyPort());
        assertEquals(config.iceAdapterGameId(), narrowed.gameUid());
        assertEquals(config.playerIdOverride(), narrowed.playerIdOverride());
        assertEquals(config.playerLogin(), narrowed.playerLogin());
        assertEquals(config.mockGameLaunchDelaySeconds(), narrowed.launchDelaySeconds());
        assertEquals(config.logLevel(), narrowed.logLevel());
        assertEquals(config.logFile(), narrowed.logFile());
        assertEquals(
                config.hostConfig().map(GameHostConfig::gameOptions).orElseGet(Map::of),
                narrowed.gameOptions(),
                "game options come from hostConfig; a joiner has none to send");
    }
}
