package com.faforever.testharness.client.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import picocli.CommandLine.IDefaultValueProvider;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Bridges environment variables and a JSON config file into picocli's default-value resolution.
 * Picocli consults this provider once per option after CLI parsing and before falling back to the
 * option's built-in {@code defaultValue} attribute.
 *
 * <p>Resolution order, highest to lowest:
 *
 * <ol>
 *   <li>CLI flag (handled by picocli before this provider runs)
 *   <li>Environment variable {@code FAF_MOCK_CLIENT_<UPPER_SNAKE>}
 *   <li>JSON config file (camelCase key)
 *   <li>{@code @Option(defaultValue = ...)} (handled by picocli after this provider returns null)
 * </ol>
 *
 * <p>Stale password-grant fields ({@code oauthUsername}, {@code oauthPassword}, {@code
 * oauthClientSecret}) are rejected at construction time with a deprecation error pointing at the
 * lobby spec — the de-risking in WBS-2.2.10 confirmed neither ROPC nor client_credentials are
 * viable against FAF Hydra (see {@code documentation/research/lobby-protocol-spec.md} §2).
 */
final class LayeredDefaultProvider implements IDefaultValueProvider {

    /** Prefix applied to environment variables owned by the Mock Client. */
    private static final String ENV_PREFIX = "FAF_MOCK_CLIENT_";

    /** Shared Jackson mapper used to read JSON config files. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * JSON keys that belonged to the removed password-grant schema. Listed here so the loader
     * surfaces a deprecation error rather than silently ignoring them.
     */
    private static final Set<String> STALE_JSON_KEYS =
            Set.of("oauthUsername", "oauthPassword", "oauthClientSecret");

    /**
     * Environment-variable names that belonged to the removed password-grant schema, mirroring
     * {@link #STALE_JSON_KEYS} under the {@code FAF_MOCK_CLIENT_*} convention.
     */
    private static final Set<String> STALE_ENV_KEYS =
            Set.of(
                    ENV_PREFIX + "OAUTH_USERNAME",
                    ENV_PREFIX + "OAUTH_PASSWORD",
                    ENV_PREFIX + "OAUTH_CLIENT_SECRET");

    /** Pointer included in every deprecation message so users can self-serve the migration. */
    private static final String DEPRECATION_POINTER =
            "Password-grant and client_credentials are not enabled on any seeded FAF Hydra "
                    + "client with `lobby` scope (WBS-2.2.10). Migrate to refresh-token auth: "
                    + "see documentation/research/lobby-protocol-spec.md §2 for the bootstrap "
                    + "procedure.";

    /** Environment variables supplied by the caller. */
    private final Map<String, String> env;

    /** Values loaded from the optional JSON config file. */
    private final Map<String, String> fileValues;

    /**
     * Which layer answered for each option this provider supplied, keyed by long option name.
     * Populated as picocli asks, so it is complete only once parsing has finished.
     */
    private final Map<String, Layer> resolvedFrom = new LinkedHashMap<>();

    /**
     * A configuration layer, ordered by the precedence {@code harness-runbook.md} documents:
     * built-in defaults, then the JSON config file, then {@code FAF_MOCK_CLIENT_*}, then CLI flags.
     * Only the three that can carry a credential are modelled.
     */
    enum Layer {
        /** Supplied by the JSON config file. */
        FILE,
        /** Supplied by a {@code FAF_MOCK_CLIENT_*} environment variable. */
        ENV,
        /** Matched on the command line, so this provider was never consulted for it. */
        CLI
    }

    LayeredDefaultProvider(final Map<String, String> environment, final Path configFile) {
        this.env = environment == null ? Map.of() : environment;
        this.fileValues = configFile == null ? Map.of() : readJsonFile(configFile);
        rejectStaleEnv(this.env);
        rejectStaleFileKeys(this.fileValues);
    }

    @Override
    public String defaultValue(final ArgSpec argSpec) {
        if (!(argSpec instanceof OptionSpec opt)) {
            return null;
        }
        String cliFlag = opt.longestName();
        if (!cliFlag.startsWith("--")) {
            return null;
        }
        String stem = cliFlag.substring(2);
        String envName = ENV_PREFIX + stem.replace('-', '_').toUpperCase(Locale.ROOT);
        String fromEnv = env.get(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            resolvedFrom.put(cliFlag, Layer.ENV);
            return fromEnv;
        }
        String jsonKey =
                (opt.userObject() instanceof Field field)
                        ? field.getName()
                        : camelCaseFromKebab(stem);
        String fromFile = fileValues.get(jsonKey);
        if (fromFile != null && !fromFile.isBlank()) {
            resolvedFrom.put(cliFlag, Layer.FILE);
            return fromFile;
        }
        return null;
    }

    /**
     * Which layer supplied an option's value, for the callers that have to compare two options
     * against the documented precedence rather than merely read them.
     *
     * <p>Picocli consults this provider only for options it did <em>not</em> match on the command
     * line, so "no record here, yet the field is populated" is a precise signal that the value came
     * from a CLI flag. That is what {@link Layer#CLI} means at the call site; this map only ever
     * holds {@link Layer#ENV} and {@link Layer#FILE}.
     *
     * @param cliFlag the option's longest name, including the leading {@code --}
     * @return the layer that answered for it, or empty if this provider did not supply it
     */
    Optional<Layer> layerFor(final String cliFlag) {
        return Optional.ofNullable(resolvedFrom.get(cliFlag));
    }

    private static Map<String, String> readJsonFile(final Path path) {
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("config file is not readable: " + path);
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(path));
            if (!root.isObject()) {
                throw new IllegalArgumentException(
                        "config file root must be a JSON object: " + path);
            }
            Map<String, String> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode v = field.getValue();
                if (!v.isNull()) {
                    out.put(field.getKey(), v.isTextual() ? v.asText() : v.toString());
                }
            }
            return out;
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to parse config file: " + e.getMessage(), e);
        }
    }

    /**
     * Reject stale password-grant env vars with a deprecation error. Blank values are tolerated
     * (some CI systems set "empty" env vars as part of secret hygiene) — only a non-blank stale
     * variable signals a misconfigured caller.
     *
     * @param envMap the caller-supplied environment map to scan
     */
    private static void rejectStaleEnv(final Map<String, String> envMap) {
        for (String key : STALE_ENV_KEYS) {
            String value = envMap.get(key);
            if (value != null && !value.isBlank()) {
                throw new IllegalArgumentException(
                        "deprecated env var "
                                + key
                                + " is no longer accepted. "
                                + DEPRECATION_POINTER);
            }
        }
    }

    /**
     * Reject stale password-grant JSON keys with a deprecation error.
     *
     * @param fileMap key/value pairs loaded from the JSON config file
     */
    private static void rejectStaleFileKeys(final Map<String, String> fileMap) {
        for (String key : STALE_JSON_KEYS) {
            if (fileMap.containsKey(key)) {
                throw new IllegalArgumentException(
                        "deprecated config key \""
                                + key
                                + "\" is no longer accepted. "
                                + DEPRECATION_POINTER);
            }
        }
    }

    private static String camelCaseFromKebab(final String kebab) {
        StringBuilder out = new StringBuilder(kebab.length());
        boolean upperNext = false;
        for (int i = 0; i < kebab.length(); i++) {
            char c = kebab.charAt(i);
            if (c == '-') {
                upperNext = true;
                continue;
            }
            out.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return out.toString();
    }
}
