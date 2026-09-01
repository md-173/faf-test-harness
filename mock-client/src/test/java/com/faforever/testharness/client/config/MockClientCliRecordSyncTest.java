package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Option;

/**
 * Drift guard: every {@link MockClientConfig} record component must have a matching {@link
 * Option}-annotated field on {@link MockClientCli}, and vice versa. Names must agree under the
 * kebab-case ↔ camelCase mapping. Without this test, adding a new field to one side and forgetting
 * the other compiles fine but produces a {@code null} value at runtime — the user sees a confusing
 * NPE instead of a config error.
 *
 * <p>The 1:1 rule has two deliberate exceptions: {@code hostConfig} groups seven CLI options
 * ({@code hostTitle}, {@code hostMap}, {@code hostMod}, {@code hostVisibility}, {@code
 * hostRatingMin}, {@code hostRatingMax}, {@code hostEnforceRatingRange}) into a single {@link
 * GameHostConfig} record component, and {@code joinConfig} groups two ({@code targetGameId}, {@code
 * gameJoinPassword}) into a single {@link GameJoinConfig} component (see {@link
 * MockClientCli#toConfig()}), so each can be absent as a whole when the mock client isn't
 * configured to host or join. {@link #GROUPED_RECORD_COMPONENT_NAMES} and {@link
 * #GROUPED_CLI_FIELD_NAMES} carve those pairings out of the strict 1:1 checks below.
 */
final class MockClientCliRecordSyncTest {

    /** Names of CLI Field Names that don't correspond to record component names. */
    private static final Set<String> CLI_ONLY_FIELD_NAMES = Set.of("configFile");

    /** Record components populated from more than one CLI option; see class javadoc. */
    private static final Set<String> GROUPED_RECORD_COMPONENT_NAMES =
            Set.of("hostConfig", "joinConfig");

    /**
     * CLI options that feed a {@linkplain #GROUPED_RECORD_COMPONENT_NAMES grouped} record
     * component.
     */
    private static final Set<String> GROUPED_CLI_FIELD_NAMES =
            Set.of(
                    "hostTitle",
                    "hostMap",
                    "hostMod",
                    "hostVisibility",
                    "hostRatingMin",
                    "hostRatingMax",
                    "hostGameOption",
                    "hostEnforceRatingRange",
                    "targetGameId",
                    "gameJoinPassword");

    @Test
    void everyRecordComponentHasAMatchingCliOption() {
        Set<String> cliOptionNames = collectCliOptionFieldNames();
        Set<String> recordNames = collectRecordComponentNames();

        Set<String> missingFromCli = new TreeSet<>(recordNames);
        missingFromCli.removeAll(cliOptionNames);
        missingFromCli.removeAll(GROUPED_RECORD_COMPONENT_NAMES);

        if (!missingFromCli.isEmpty()) {
            fail(
                    "MockClientConfig has record components with no matching @Option on "
                            + "MockClientCli: "
                            + missingFromCli);
        }
    }

    @Test
    void everyCliOptionHasAMatchingRecordComponent() {
        Set<String> cliOptionNames = collectCliOptionFieldNames();
        Set<String> recordNames = collectRecordComponentNames();

        Set<String> extraOnCli = new TreeSet<>(cliOptionNames);
        extraOnCli.removeAll(recordNames);
        extraOnCli.removeAll(CLI_ONLY_FIELD_NAMES);
        extraOnCli.removeAll(GROUPED_CLI_FIELD_NAMES);

        if (!extraOnCli.isEmpty()) {
            fail(
                    "MockClientCli has @Option-annotated fields with no matching record "
                            + "component on MockClientConfig: "
                            + extraOnCli);
        }
    }

    @Test
    void cliOptionFieldNamesMatchTheirCliFlagsExactly() {
        for (Field field : MockClientCli.class.getDeclaredFields()) {
            Option option = field.getAnnotation(Option.class);
            if (option == null) {
                continue;
            }
            String fieldName = field.getName();
            String firstFlag = option.names()[0];
            assertTrue(
                    firstFlag.startsWith("--"),
                    "Expected --kebab-case for option on field " + fieldName);

            String derivedCamel = kebabToCamelCase(firstFlag.substring(2));
            if (CLI_ONLY_FIELD_NAMES.contains(fieldName)) {
                continue;
            }

            assertEquals(
                    fieldName.toLowerCase(Locale.ROOT),
                    derivedCamel.toLowerCase(Locale.ROOT),
                    "@Option flag "
                            + firstFlag
                            + " on field "
                            + fieldName
                            + " disagrees with the derived camelCase form (case-insensitive).");
        }
    }

    @Test
    void counts() {
        // sanity check: catches the case where both sets drift in lockstep. The gap between the
        // two numbers is exactly GROUPED_CLI_FIELD_NAMES.size() - GROUPED_RECORD_COMPONENT_NAMES
        // .size() (8 host-* options collapse into 1 hostConfig record component, and 2 join
        // options collapse into 1 joinConfig component).
        long expectedRecordComponents = 25;
        long expectedCliOptionsExcludingHelpers = 33;

        long actualRecordComponents = MockClientConfig.class.getRecordComponents().length;
        long actualCliOptions =
                Arrays.stream(MockClientCli.class.getDeclaredFields())
                                .filter(f -> f.isAnnotationPresent(Option.class))
                                .count()
                        - CLI_ONLY_FIELD_NAMES.size();

        assertEquals(
                expectedRecordComponents,
                actualRecordComponents,
                "MockClientConfig record-component count drifted; update this test if "
                        + "intentional.");
        assertEquals(
                expectedCliOptionsExcludingHelpers,
                actualCliOptions,
                "MockClientCli @Option-annotated field count drifted; update this test if "
                        + "intentional.");
    }

    private static Set<String> collectRecordComponentNames() {
        return Stream.of(MockClientConfig.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> collectCliOptionFieldNames() {
        Set<String> out = new HashSet<>();
        for (Field field : MockClientCli.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Option.class)) {
                out.add(field.getName());
            }
        }
        return out;
    }

    private static String kebabToCamelCase(final String kebab) {
        StringBuilder out = new StringBuilder(kebab.length());
        boolean upperNext = false;
        for (int i = 0; i < kebab.length(); i++) {
            char c = kebab.charAt(i);
            if (c == '-') {
                upperNext = true;
            } else {
                out.append(upperNext ? Character.toUpperCase(c) : Character.toLowerCase(c));
                upperNext = false;
            }
        }
        return out.toString();
    }

    @SuppressWarnings("unused")
    private static String camelCaseToScreamingSnake(final String camel) {
        // Kept here as a reference for tests that want to also assert env-var derivation.
        StringBuilder out = new StringBuilder(camel.length() * 2);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toUpperCase(c));
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }
}
