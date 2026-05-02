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
 *   <li>Environment variable {@code FAF_MOCK_<UPPER_SNAKE>}
 *   <li>JSON config file (camelCase key)
 *   <li>{@code @Option(defaultValue = ...)} (handled by picocli after this provider returns null)
 * </ol>
 */
final class LayeredDefaultProvider implements IDefaultValueProvider {

    private static final String ENV_PREFIX = "FAF_MOCK_CLIENT_";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> env;
    private final Map<String, String> fileValues;

    LayeredDefaultProvider(final Map<String, String> env, final Path configFile) {
        this.env = env == null ? Map.of() : env;
        this.fileValues = configFile == null ? Map.of() : readJsonFile(configFile);
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
            return fromEnv;
        }
        String jsonKey =
                (opt.userObject() instanceof Field field)
                        ? field.getName()
                        : camelCaseFromKebab(stem);
        String fromFile = fileValues.get(jsonKey);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        return null;
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
