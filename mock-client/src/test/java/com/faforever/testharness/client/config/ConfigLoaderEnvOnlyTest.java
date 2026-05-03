package com.faforever.testharness.client.config;

import org.junit.jupiter.api.Test;

/**
 * Confirms the environment-variable layer alone produces a valid {@link MockClientConfig} via
 * {@link LayeredDefaultProvider}'s {@code FAF_MOCK_CLIENT_*} convention. No file or CLI input is
 * supplied.
 */
final class ConfigLoaderEnvOnlyTest {

    @Test
    void envSuppliesAllRequiredFieldsAndDefaultsApply() {
        MockClientConfig config =
                ConfigLoader.load(new String[] {}, TestFixtures.minimalRequiredEnv()).orElseThrow();

        TestFixtures.assertMatchesMinimalRequired(config);
    }
}
