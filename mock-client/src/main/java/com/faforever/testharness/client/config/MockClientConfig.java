package com.faforever.testharness.client.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable, validated configuration for the Mock Client. Every other component reads from an
 * instance of this record. Produced exclusively by {@link ConfigLoader}.
 */
public record MockClientConfig(
        URI lobbyWebSocketUrl,
        URI oauthTokenUrl,
        String oauthClientId,
        String oauthClientSecret,
        String oauthUsername,
        String oauthPassword,
        String oauthAccessToken,
        Path oauthTokenFile,
        String uniqueId,
        Path iceAdapterBinaryPath,
        Path mockGameBinaryPath,
        int iceAdapterRpcPort,
        int iceAdapterGpgNetPort,
        String logLevel,
        Optional<Path> logFile,
        OptionalInt playerIdOverride) {}
