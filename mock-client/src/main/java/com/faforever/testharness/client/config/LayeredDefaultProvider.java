package com.faforever.testharness.client.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.IDefaultValueProvider;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
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
 *   <li>JSON config file (keyed by the option's field name)
 *   <li>{@code @Option(defaultValue = ...)} (handled by picocli after this provider returns null)
 * </ol>
 *
 * <p>Stale password-grant fields ({@code oauthUsername}, {@code oauthPassword}, {@code
 * oauthClientSecret}) are rejected at construction time with a deprecation error pointing at the
 * lobby spec — the de-risking in WBS-2.2.10 confirmed neither ROPC nor client_credentials are
 * viable against FAF Hydra (see {@code documentation/research/lobby-protocol-spec.md} §2). Every
 * other key in the file must be one some command reads, or construction fails naming it.
 */
final class LayeredDefaultProvider implements IDefaultValueProvider {

    /** Prefix applied to environment variables owned by the Mock Client. */
    private static final String ENV_PREFIX = "FAF_MOCK_CLIENT_";

    /**
     * Shared Jackson mapper used to read JSON config files.
     *
     * <p>Two non-default features are on, both of them about what the loader is willing to accept
     * rather than about how it reports (WBS-3.1.5.1-fix, #290):
     *
     * <ul>
     *   <li>{@code FAIL_ON_TRAILING_TOKENS} — {@code readTree} otherwise stops at the first
     *       complete value and silently discards the rest, so {@code {"a":1} garbage} and {@code
     *       {"a":1}{"b":2}} both loaded as {@code {"a":1}}. A file truncated and re-appended, or
     *       concatenated by a bad generator, then produced a "missing required option" naming a key
     *       the operator can see in the file.
     *   <li>{@code STRICT_DUPLICATE_DETECTION} — a repeated key silently took the last value.
     *       Last-wins is a defensible convention, but not an unstated one: a config with the same
     *       key twice is a mistake far more often than it is a layering trick.
     * </ul>
     */
    private static final ObjectMapper JSON =
            JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .build();

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

    /**
     * Values loaded from the optional JSON config file, in file order. A JSON {@code null} is kept
     * as a {@code null} value, which reads as unset.
     */
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

    /**
     * Loads the config file, if there is one, and rejects what no command can use: the stale
     * password-grant variables and keys first, then any other key no command reads.
     *
     * @param environment the environment the {@code FAF_MOCK_CLIENT_*} variables are read from
     * @param configFile the JSON config file, or {@code null} for none
     * @param root the root command with its subcommands registered, whose options decide which keys
     *     the file may hold
     */
    LayeredDefaultProvider(
            final Map<String, String> environment, final Path configFile, final CommandSpec root) {
        this.env = environment == null ? Map.of() : environment;
        this.fileValues = configFile == null ? Map.of() : readJsonFile(configFile);
        rejectStaleEnv(this.env);
        rejectStaleFileKeys(this.fileValues);
        rejectUnknownFileKeys(this.fileValues, root, configFile);
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
        String jsonKey = fileKey(opt);
        // Map.of(), the map when there is no config file, throws on a null lookup.
        String fromFile = jsonKey == null ? null : fileValues.get(jsonKey);
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
            throw new IllegalArgumentException("config file is not readable: " + oneLine(path));
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(path));
            if (!root.isObject()) {
                throw new IllegalArgumentException(
                        "config file root must be a JSON object: " + oneLine(path));
            }
            Map<String, String> out = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode v = field.getValue();
                if (v.isNull()) {
                    // Kept rather than dropped, so the key checks still see the key (#490).
                    // defaultValue reads a null value as unset, as it did a missing key.
                    out.put(field.getKey(), null);
                } else {
                    out.put(field.getKey(), v.isTextual() ? v.asText() : v.toString());
                }
            }
            return out;
        } catch (JsonProcessingException e) {
            // getMessage() appends a multi-line " at [Source: ...]" block, and the entry point
            // renders this as a single-line usage error. Take the reason on its own, then put back
            // the line/column that block carried — that is what tells the user where to look — and
            // the file name, which Jackson redacts (INCLUDE_SOURCE_IN_LOCATION is off by default).
            //
            // getOriginalMessage() is the unguarded half of the pair (#285): getMessage() falls
            // back to "N/A" when the cause carried no message of its own, this one returns the
            // null straight through, and the diagnostic would read "…: null". No input tried
            // reaches it — every malformed document produced a real reason, and nothing in
            // readTree(String) uses the JsonProcessingException(Throwable) constructor that leaves
            // the message null — so the fallback is insurance against a future Jackson, not a
            // reproduced defect. toString() rather than "N/A": if this ever does fire, the
            // exception type is the only thing left that says anything about the failure.
            throw new IllegalArgumentException(
                    "failed to parse config file "
                            + oneLine(path)
                            + describeLocation(e.getLocation())
                            + ": "
                            + oneLine(
                                    Objects.requireNonNullElse(
                                            e.getOriginalMessage(), e.getClass().getName())),
                    e);
        } catch (IOException e) {
            // Same guard as the parse branch above, for the same reason: oneLine is
            // String.valueOf, so a null message renders the literal "null" — the exact symptom
            // #285 exists to prevent, two lines below the guard that prevents it. No current input
            // reaches it (FileSystemException and MalformedInputException both override
            // getMessage() non-null), which is the standing #285 itself had.
            throw new IllegalArgumentException(
                    "failed to parse config file "
                            + oneLine(path)
                            + ": "
                            + oneLine(
                                    Objects.requireNonNullElse(
                                            e.getMessage(), e.getClass().getName())),
                    e);
        }
    }

    /**
     * Renders a value for a single-line diagnostic, escaping any line break it contains.
     *
     * <p>A path may legally contain a newline on Linux and macOS. Interpolated raw, it splits the
     * diagnostic the entry point promises to keep on one line, and a path containing {@code
     * "\nUsage:"} would forge the boundary between the error and picocli's usage block for anything
     * reading stderr.
     *
     * <p>Every caller-controlled value interpolated into one of this class's messages goes through
     * here, not just the path: an underlying library's message is no more trustworthy than the
     * filename that produced it, and one unescaped operand reopens the hole for all of them. Values
     * drawn from fixed sets, such as the stale-key names, are exempt.
     *
     * <p>This covers only the diagnostics raised while the {@code CommandLine} is being built,
     * which is all this class produces. The single-line property holds for every error the CLI
     * reports because the two handlers {@code ConfigLoader.newCommandLine} installs apply the same
     * escaping to everything reported from inside {@code execute}: {@code
     * ParameterExceptionHandler} to parse errors (#307) and {@code ExecutionExceptionHandler} to
     * exceptions escaping a subcommand. {@code LoggingSetup} escapes its {@code --log-file} notice
     * the same way. Picocli's own {@code [picocli WARN]} trace lines, which it prints straight to
     * {@code System.err}, are outside it.
     *
     * @param value the value to interpolate — a {@link Path}, or a message from a caught exception
     * @return the value's text with every line terminator replaced by a literal {@code \n}
     */
    static String oneLine(final Object value) {
        return String.valueOf(value).replaceAll("\\R", "\\\\n");
    }

    /**
     * Renders a Jackson parse location as a single parenthesised clause.
     *
     * @param location the location reported by the parse failure, possibly {@code null} or unset
     * @return {@code " (line N, column M)"}, or an empty string if no location is available
     */
    private static String describeLocation(final JsonLocation location) {
        if (location == null || location.getLineNr() < 1) {
            return "";
        }
        return " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
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

    /**
     * Rejects every config-file key that no command in the tree reads, naming each one, in file
     * order, and the file (WBS-3.1.5.1-fix, #490).
     *
     * <p>Such a key used to be dropped without a word. A misspelt fault option then ran the session
     * without its fault, and the session passed; a key spelt as the flag in camel case where the
     * field is named otherwise, such as {@code lobbyWebsocketUrl}, was dropped the same way. The
     * valid keys come from the whole tree rather than the command being run, because one file
     * serves every subcommand: a key only {@code session} reads is still valid under {@code run}.
     * Every key is checked whatever its value, {@code null} included, so a misspelt placeholder is
     * caught before anyone fills it in.
     *
     * <p>Runs after {@link #rejectStaleFileKeys}, so a stale key keeps its deprecation message.
     *
     * @param fileMap key/value pairs loaded from the JSON config file, in file order
     * @param root the root command, with its subcommands registered
     * @param configFile the file the pairs came from
     */
    private static void rejectUnknownFileKeys(
            final Map<String, String> fileMap, final CommandSpec root, final Path configFile) {
        Set<String> known = new HashSet<>();
        collectFileKeys(root, known);
        List<String> unknown = new ArrayList<>();
        for (String key : fileMap.keySet()) {
            if (!known.contains(key)) {
                unknown.add("\"" + oneLine(key) + "\"");
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    (unknown.size() == 1 ? "unknown config key " : "unknown config keys ")
                            + String.join(", ", unknown)
                            + " in "
                            + oneLine(configFile));
        }
    }

    /**
     * Adds the config-file key of every option of {@code spec} and of its subcommands, at any
     * depth. A command's {@code options()} hold its own options, its mixins' and its inherited
     * copies of the root's.
     *
     * @param spec the command to start from
     * @param keys the set the keys are added to
     */
    private static void collectFileKeys(final CommandSpec spec, final Set<String> keys) {
        for (OptionSpec opt : spec.options()) {
            String key = fileKey(opt);
            if (key != null) {
                keys.add(key);
            }
        }
        for (CommandLine subcommand : spec.subcommands().values()) {
            collectFileKeys(subcommand.getCommandSpec(), keys);
        }
    }

    /**
     * The config-file key an option is read from: its field's name, whatever the flag says. For
     * most options that is the flag in camel case, but not for all: {@code --lobby-websocket-url}
     * reads {@code lobbyWebSocketUrl}. The lookup in {@link #defaultValue(ArgSpec)} and the key
     * check both use this, so they cannot disagree about which keys exist.
     *
     * @param opt the option
     * @return the key, the flag in camel case for an option with no field behind it, or {@code
     *     null} for an option nothing in a file can set: one with no long name, the help and
     *     version flags, and {@code --config}, which names the file itself
     */
    private static String fileKey(final OptionSpec opt) {
        String cliFlag = opt.longestName();
        if (!cliFlag.startsWith("--")
                || opt.usageHelp()
                || opt.versionHelp()
                || cliFlag.equals(ConfigLoader.CONFIG_FLAG)) {
            return null;
        }
        return opt.userObject() instanceof Field field
                ? field.getName()
                : camelCaseFromKebab(cliFlag.substring(2));
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
