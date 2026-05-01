package com.faforever.testharness.client.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single source of truth for every Mock Client configuration field.
 *
 * <p>Each constant binds a {@link MockClientConfig} record component to its JSON key, environment
 * variable name, CLI flag, default value, requiredness, and {@link ValueType}. The {@code
 * ConfigLoader} uses this registry to merge the four configuration layers (defaults, file, env,
 * CLI) and to produce actionable validation errors that name the failing field along with both
 * the env var and CLI flag a user can set.
 *
 * <p>Env vars follow the convention {@code FAF_MOCK_<UPPER_SNAKE_CASE>} and CLI flags follow
 * {@code --kebab-case}, both derived mechanically from the JSON key.
 */
public enum ConfigKey {
    LOBBY_WEBSOCKET_URL(
            "lobbyWebSocketUrl",
            "FAF_MOCK_LOBBY_WEBSOCKET_URL",
            "--lobby-websocket-url",
            null,
            true,
            ValueType.URI,
            "WebSocket endpoint of the FAF lobby server."),
    OAUTH_TOKEN_URL(
            "oauthTokenUrl",
            "FAF_MOCK_OAUTH_TOKEN_URL",
            "--oauth-token-url",
            null,
            true,
            ValueType.URI,
            "OAuth2 token endpoint used to acquire lobby access tokens."),
    OAUTH_CLIENT_ID(
            "oauthClientId",
            "FAF_MOCK_OAUTH_CLIENT_ID",
            "--oauth-client-id",
            null,
            true,
            ValueType.STRING,
            "OAuth2 client identifier."),
    OAUTH_CLIENT_SECRET(
            "oauthClientSecret",
            "FAF_MOCK_OAUTH_CLIENT_SECRET",
            "--oauth-client-secret",
            null,
            false,
            ValueType.STRING,
            "OAuth2 client secret. Prefer environment variables or CI secrets."),
    OAUTH_USERNAME(
            "oauthUsername",
            "FAF_MOCK_OAUTH_USERNAME",
            "--oauth-username",
            null,
            false,
            ValueType.STRING,
            "OAuth username for local/test environments that support password authentication."),
    OAUTH_PASSWORD(
            "oauthPassword",
            "FAF_MOCK_OAUTH_PASSWORD",
            "--oauth-password",
            null,
            false,
            ValueType.STRING,
            "OAuth password for local/test environments. Prefer env vars or CI secrets."),
    OAUTH_ACCESS_TOKEN(
            "oauthAccessToken",
            "FAF_MOCK_OAUTH_ACCESS_TOKEN",
            "--oauth-access-token",
            null,
            false,
            ValueType.STRING,
            "Pre-obtained OAuth access token. Prefer env vars or CI secrets."),
    OAUTH_TOKEN_FILE(
            "oauthTokenFile",
            "FAF_MOCK_OAUTH_TOKEN_FILE",
            "--oauth-token-file",
            null,
            false,
            ValueType.PATH,
            "Path to a file containing a pre-obtained OAuth access token."),
    UNIQUE_ID(
            "uniqueId",
            "FAF_MOCK_UNIQUE_ID",
            "--unique-id",
            null,
            true,
            ValueType.STRING,
            "Stable synthetic hardware identifier sent in the lobby auth message."),
    ICE_ADAPTER_BINARY_PATH(
            "iceAdapterBinaryPath",
            "FAF_MOCK_ICE_ADAPTER_BINARY_PATH",
            "--ice-adapter-binary-path",
            null,
            true,
            ValueType.PATH,
            "Path to the faf-ice-adapter executable."),
    MOCK_GAME_BINARY_PATH(
            "mockGameBinaryPath",
            "FAF_MOCK_MOCK_GAME_BINARY_PATH",
            "--mock-game-binary-path",
            null,
            true,
            ValueType.PATH,
            "Path to the mock-game executable."),
    ICE_ADAPTER_RPC_PORT(
            "iceAdapterRpcPort",
            "FAF_MOCK_ICE_ADAPTER_RPC_PORT",
            "--ice-adapter-rpc-port",
            "7236",
            true,
            ValueType.PORT,
            "Local JSON-RPC port exposed by faf-ice-adapter."),
    ICE_ADAPTER_GPG_NET_PORT(
            "iceAdapterGpgNetPort",
            "FAF_MOCK_ICE_ADAPTER_GPG_NET_PORT",
            "--ice-adapter-gpg-net-port",
            "7237",
            true,
            ValueType.PORT,
            "Local GPGNet port exposed by faf-ice-adapter."),
    LOG_LEVEL(
            "logLevel",
            "FAF_MOCK_LOG_LEVEL",
            "--log-level",
            "INFO",
            true,
            ValueType.STRING,
            "Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR)."),
    LOG_FILE(
            "logFile",
            "FAF_MOCK_LOG_FILE",
            "--log-file",
            null,
            false,
            ValueType.PATH,
            "Optional JSONL log file path."),
    PLAYER_ID_OVERRIDE(
            "playerIdOverride",
            "FAF_MOCK_PLAYER_ID_OVERRIDE",
            "--player-id-override",
            null,
            false,
            ValueType.INT,
            "Optional player ID override for deterministic local testing.");

    private static final Map<String, ConfigKey> BY_JSON_KEY = indexBy(ConfigKey::jsonKey);
    private static final Map<String, ConfigKey> BY_ENV_VAR = indexBy(ConfigKey::envVar);
    private static final Map<String, ConfigKey> BY_CLI_FLAG = indexBy(ConfigKey::cliFlag);

    static {
        int recordComponents = MockClientConfig.class.getRecordComponents().length;
        if (values().length != recordComponents) {
            throw new IllegalStateException(
                    "ConfigKey registry has "
                            + values().length
                            + " entries but MockClientConfig has "
                            + recordComponents
                            + " record components. Keep them in sync.");
        }
    }

    private final String jsonKey;
    private final String envVar;
    private final String cliFlag;
    private final String defaultValue;
    private final boolean required;
    private final ValueType type;
    private final String description;

    ConfigKey(
            final String jsonKey,
            final String envVar,
            final String cliFlag,
            final String defaultValue,
            final boolean required,
            final ValueType type,
            final String description) {
        this.jsonKey = jsonKey;
        this.envVar = envVar;
        this.cliFlag = cliFlag;
        this.defaultValue = defaultValue;
        this.required = required;
        this.type = type;
        this.description = description;
    }

    /** JSON property name as it appears in {@code mock-client.json}. */
    public String jsonKey() {
        return jsonKey;
    }

    /** Environment variable name (uppercase, {@code FAF_MOCK_} prefix). */
    public String envVar() {
        return envVar;
    }

    /** CLI flag (kebab-case, leading {@code --}). */
    public String cliFlag() {
        return cliFlag;
    }

    /** Built-in default value, or empty when there is no default. */
    public Optional<String> defaultValue() {
        return Optional.ofNullable(defaultValue);
    }

    /** Whether the loader must fail when this key is unset across all layers. */
    public boolean required() {
        return required;
    }

    /** Parser/validator for the raw string form of this key. */
    public ValueType type() {
        return type;
    }

    /** Human-readable description shown in error messages and the README. */
    public String description() {
        return description;
    }

    /**
     * Parse {@code raw} into the typed value for this key.
     *
     * @throws IllegalArgumentException if {@code raw} does not satisfy {@link #type()}
     */
    public Object parse(final String raw) {
        return type.parse(jsonKey, raw);
    }

    /** Look up a key by its JSON property name. */
    public static Optional<ConfigKey> fromJsonKey(final String jsonKey) {
        return Optional.ofNullable(BY_JSON_KEY.get(jsonKey));
    }

    /** Look up a key by its environment variable name. */
    public static Optional<ConfigKey> fromEnvVar(final String envVar) {
        return Optional.ofNullable(BY_ENV_VAR.get(envVar));
    }

    /** Look up a key by its CLI flag (with leading {@code --}). */
    public static Optional<ConfigKey> fromCliFlag(final String cliFlag) {
        return Optional.ofNullable(BY_CLI_FLAG.get(cliFlag));
    }

    private static Map<String, ConfigKey> indexBy(final Function<ConfigKey, String> selector) {
        return Arrays.stream(values())
                .collect(Collectors.toUnmodifiableMap(selector, Function.identity()));
    }

    /** Supported scalar config value types. */
    public enum ValueType {
        /** Non-blank string. */
        STRING {
            @Override
            Object parse(final String fieldName, final String raw) {
                if (raw == null || raw.isBlank()) {
                    throw invalid(fieldName, "must not be blank");
                }
                return raw;
            }
        },
        /** Absolute or relative URI with a scheme. */
        URI {
            @Override
            Object parse(final String fieldName, final String raw) {
                try {
                    java.net.URI uri = new java.net.URI(raw);
                    if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                        throw invalid(fieldName, "must include a URI scheme");
                    }
                    return uri;
                } catch (URISyntaxException e) {
                    throw invalid(fieldName, "must be a valid URI");
                }
            }
        },
        /** Filesystem path; existence is not checked here. */
        PATH {
            @Override
            Object parse(final String fieldName, final String raw) {
                if (raw == null || raw.isBlank()) {
                    throw invalid(fieldName, "must not be blank");
                }
                return Path.of(raw);
            }
        },
        /** Signed 32-bit integer. */
        INT {
            @Override
            Object parse(final String fieldName, final String raw) {
                try {
                    return Integer.parseInt(raw);
                } catch (NumberFormatException e) {
                    throw invalid(fieldName, "must be an integer");
                }
            }
        },
        /** TCP/UDP port: integer in {@code [1, 65535]}. */
        PORT {
            @Override
            Object parse(final String fieldName, final String raw) {
                int port = (int) INT.parse(fieldName, raw);
                if (port < 1 || port > 65535) {
                    throw invalid(fieldName, "must be between 1 and 65535");
                }
                return port;
            }
        };

        abstract Object parse(String fieldName, String raw);

        private static IllegalArgumentException invalid(
                final String fieldName, final String reason) {
            return new IllegalArgumentException(
                    "Invalid value for " + fieldName + ": " + reason);
        }
    }
}