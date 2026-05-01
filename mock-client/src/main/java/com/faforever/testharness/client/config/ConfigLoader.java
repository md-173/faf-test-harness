package com.faforever.testharness.client.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Loads {@link MockClientConfig} by merging four layers in strict precedence order
 * (lowest to highest): built-in defaults, JSON config file, environment variables, CLI flags.
 *
 * <p>Higher-priority sources override lower ones on a per-key basis. After merging, every
 * required key is verified; missing or malformed values are reported in a single
 * {@link ConfigValidationException} naming each failing field together with its env var and
 * CLI flag so the user can see exactly how to fix it.
 *
 * <p>Production callers use {@link #load(String[])}, which reads {@code System.getenv()} and
 * the {@code --config <path>} flag once at the boundary. Tests should use
 * {@link #load(Sources)} with a fully-supplied {@link Sources} record so they never touch
 * real environment or filesystem state.
 */
public final class ConfigLoader {

    /** CLI flag that points at the JSON config file. */
    public static final String CONFIG_FLAG = "--config";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ConfigLoader() {}

    /**
     * Production entry point: reads the real process environment and the {@code --config}
     * flag from {@code args}, then merges all four layers.
     *
     * @param args raw command-line arguments
     * @return validated {@link MockClientConfig}
     * @throws ConfigValidationException if any required field is missing or any value is
     *     malformed
     */
    public static MockClientConfig load(final String[] args) {
        Map<String, String> cli = parseCliArgs(args);
        Optional<Path> configFile = Optional.ofNullable(cli.remove(CONFIG_FLAG)).map(Path::of);
        return load(new Sources(configFile, System.getenv(), cli));
    }

    /**
     * Test-friendly entry point. Caller supplies every layer explicitly; nothing is read
     * from the JVM environment or system properties.
     */
    public static MockClientConfig load(final Sources sources) {
        Map<ConfigKey, String> raw = new EnumMap<>(ConfigKey.class);

        for (ConfigKey key : ConfigKey.values()) {
            key.defaultValue().ifPresent(v -> raw.put(key, v));
        }

        sources.configFile()
                .ifPresent(path -> raw.putAll(readJsonFile(path)));

        for (Map.Entry<String, String> e : sources.env().entrySet()) {
            ConfigKey.fromEnvVar(e.getKey())
                    .ifPresent(k -> putIfPresent(raw, k, e.getValue()));
        }

        for (Map.Entry<String, String> e : sources.cli().entrySet()) {
            ConfigKey.fromCliFlag(e.getKey())
                    .ifPresent(k -> putIfPresent(raw, k, e.getValue()));
        }

        return assemble(raw);
    }

    private static void putIfPresent(
            final Map<ConfigKey, String> target, final ConfigKey key, final String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static Map<ConfigKey, String> readJsonFile(final Path path) {
        if (!Files.isReadable(path)) {
            throw new ConfigValidationException(List.of(
                    new ConfigValidationException.Issue(
                            "config file",
                            "config file is not readable: " + path,
                            CONFIG_FLAG,
                            null)));
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(path));
            if (!root.isObject()) {
                throw new ConfigValidationException(List.of(
                        new ConfigValidationException.Issue(
                                "config file",
                                "config file root must be a JSON object: " + path,
                                CONFIG_FLAG,
                                null)));
            }
            Map<ConfigKey, String> out = new EnumMap<>(ConfigKey.class);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                ConfigKey.fromJsonKey(field.getKey()).ifPresent(k -> {
                    JsonNode v = field.getValue();
                    if (!v.isNull()) {
                        out.put(k, v.isTextual() ? v.asText() : v.toString());
                    }
                });
            }
            return out;
        } catch (IOException e) {
            throw new ConfigValidationException(List.of(
                    new ConfigValidationException.Issue(
                            "config file",
                            "failed to parse JSON: " + e.getMessage(),
                            CONFIG_FLAG,
                            null)));
        }
    }

    private static MockClientConfig assemble(final Map<ConfigKey, String> raw) {
        List<ConfigValidationException.Issue> issues = new ArrayList<>();
        Map<ConfigKey, Object> parsed = new EnumMap<>(ConfigKey.class);

        for (ConfigKey key : ConfigKey.values()) {
            String rawValue = raw.get(key);
            if (rawValue == null || rawValue.isBlank()) {
                if (key.required()) {
                    issues.add(new ConfigValidationException.Issue(
                            key.jsonKey(),
                            "required field is not set",
                            key.cliFlag(),
                            key.envVar()));
                }
                continue;
            }
            try {
                parsed.put(key, key.parse(rawValue));
            } catch (IllegalArgumentException ex) {
                issues.add(new ConfigValidationException.Issue(
                        key.jsonKey(), ex.getMessage(), key.cliFlag(), key.envVar()));
            }
        }

        validateAuthChoice(parsed, issues);

        if (!issues.isEmpty()) {
            throw new ConfigValidationException(Collections.unmodifiableList(issues));
        }

        return new MockClientConfig(
                (URI) parsed.get(ConfigKey.LOBBY_WEBSOCKET_URL),
                (URI) parsed.get(ConfigKey.OAUTH_TOKEN_URL),
                (String) parsed.get(ConfigKey.OAUTH_CLIENT_ID),
                (String) parsed.get(ConfigKey.OAUTH_CLIENT_SECRET),
                (String) parsed.get(ConfigKey.OAUTH_USERNAME),
                (String) parsed.get(ConfigKey.OAUTH_PASSWORD),
                (String) parsed.get(ConfigKey.OAUTH_ACCESS_TOKEN),
                (Path) parsed.get(ConfigKey.OAUTH_TOKEN_FILE),
                (String) parsed.get(ConfigKey.UNIQUE_ID),
                (Path) parsed.get(ConfigKey.ICE_ADAPTER_BINARY_PATH),
                (Path) parsed.get(ConfigKey.MOCK_GAME_BINARY_PATH),
                (int) parsed.get(ConfigKey.ICE_ADAPTER_RPC_PORT),
                (int) parsed.get(ConfigKey.ICE_ADAPTER_GPG_NET_PORT),
                (String) parsed.get(ConfigKey.LOG_LEVEL),
                Optional.ofNullable((Path) parsed.get(ConfigKey.LOG_FILE)),
                parsed.containsKey(ConfigKey.PLAYER_ID_OVERRIDE)
                        ? OptionalInt.of((int) parsed.get(ConfigKey.PLAYER_ID_OVERRIDE))
                        : OptionalInt.empty());
    }

    /**
     * Cross-field rule: at least one credential channel must be supplied.
     * Either an access token (literal or via file), or username + password + client secret.
     */
    private static void validateAuthChoice(
            final Map<ConfigKey, Object> parsed,
            final List<ConfigValidationException.Issue> issues) {
        boolean hasToken = parsed.containsKey(ConfigKey.OAUTH_ACCESS_TOKEN)
                || parsed.containsKey(ConfigKey.OAUTH_TOKEN_FILE);
        boolean hasPasswordGrant = parsed.containsKey(ConfigKey.OAUTH_USERNAME)
                && parsed.containsKey(ConfigKey.OAUTH_PASSWORD)
                && parsed.containsKey(ConfigKey.OAUTH_CLIENT_SECRET);
        if (!hasToken && !hasPasswordGrant) {
            issues.add(new ConfigValidationException.Issue(
                    "oauth credentials",
                    "no OAuth credentials supplied: set "
                            + ConfigKey.OAUTH_ACCESS_TOKEN.jsonKey()
                            + " / "
                            + ConfigKey.OAUTH_TOKEN_FILE.jsonKey()
                            + ", or "
                            + ConfigKey.OAUTH_USERNAME.jsonKey()
                            + " + "
                            + ConfigKey.OAUTH_PASSWORD.jsonKey()
                            + " + "
                            + ConfigKey.OAUTH_CLIENT_SECRET.jsonKey(),
                    null,
                    null));
        }
    }

    /**
     * Parse {@code --key=value}, {@code --key value}, and bare {@code --flag} forms into a
     * map keyed by full flag (including the leading {@code --}). Unknown flags are tolerated
     * here and surface later as {@code ConfigKey.fromCliFlag(...)} returning empty — the
     * caller decides whether unknowns are fatal.
     */
    static Map<String, String> parseCliArgs(final String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        if (args == null) {
            return out;
        }
        int i = 0;
        while (i < args.length) {
            String token = args[i];
            if (token == null || !token.startsWith("--")) {
                i++;
                continue;
            }
            int eq = token.indexOf('=');
            if (eq >= 0) {
                out.put(token.substring(0, eq), token.substring(eq + 1));
                i++;
                continue;
            }
            String next = i + 1 < args.length ? args[i + 1] : null;
            if (next != null && !next.startsWith("--")) {
                out.put(token, next);
                i += 2;
            } else {
                out.put(token, "true");
                i++;
            }
        }
        return out;
    }

    /**
     * The four input layers, supplied to {@link #load(Sources)}. Built-in defaults come
     * from {@link ConfigKey#defaultValue()} and are not part of this record.
     *
     * @param configFile optional path to a JSON config file
     * @param env environment variables (only {@code FAF_MOCK_*} keys are consulted)
     * @param cli pre-parsed CLI flag map (full flag including {@code --} → value)
     */
    public record Sources(
            Optional<Path> configFile, Map<String, String> env, Map<String, String> cli) {
        public Sources {
            env = env == null ? Map.of() : Map.copyOf(env);
            cli = cli == null ? Map.of() : Map.copyOf(cli);
            configFile = configFile == null ? Optional.empty() : configFile;
        }
    }
}