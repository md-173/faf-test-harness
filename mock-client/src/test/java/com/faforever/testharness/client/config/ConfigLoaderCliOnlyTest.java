package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Confirms the CLI-flag layer alone produces a valid {@link MockClientConfig}. No env vars, no
 * config file, just {@code --kebab-case} flags.
 */
final class ConfigLoaderCliOnlyTest {

    @Test
    void cliSuppliesAllRequiredFieldsAndDefaultsApply() {
        MockClientConfig config =
                ConfigLoader.load(TestFixtures.minimalRequiredCli(), Map.of()).orElseThrow();

        TestFixtures.assertMatchesMinimalRequired(config);
    }

    @Test
    void cliFlagsCanOverrideADefaultPort() {
        String[] args =
                Stream.concat(
                                Arrays.stream(TestFixtures.minimalRequiredCli()),
                                Stream.of("--ice-adapter-rpc-port=9000"))
                        .toArray(String[]::new);

        MockClientConfig config = ConfigLoader.load(args, Map.of()).orElseThrow();

        assertEquals(9000, config.iceAdapterRpcPort());
    }
}
