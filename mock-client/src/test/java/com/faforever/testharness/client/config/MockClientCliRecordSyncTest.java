package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Drift guard: every leaf component of {@link MockClientConfig}, its nested groups flattened under
 * the names {@link ConfigLeaves} gives them, must have a matching {@link Option}-annotated field on
 * {@link MockClientCli} or one of its {@link Mixin}s, and vice versa. Names must agree under the
 * kebab-case ↔ camelCase mapping. Without this test, adding a new field to one side and forgetting
 * the other compiles fine but produces a {@code null} value at runtime, and the user sees a
 * confusing NPE instead of a config error.
 *
 * <p>The 1:1 rule has three deliberate exceptions: {@code hostConfig} groups seven CLI options
 * ({@code hostTitle}, {@code hostMap}, {@code hostMod}, {@code hostVisibility}, {@code
 * hostRatingMin}, {@code hostRatingMax}, {@code hostEnforceRatingRange}) into a single {@link
 * GameHostConfig} record component, {@code joinConfig} groups two ({@code targetGameId}, {@code
 * gameJoinPassword}) into a single {@link GameJoinConfig} component, and {@code queueConfig} groups
 * two ({@code queueName}, {@code queueFaction}) into a single {@link GameQueueConfig} component
 * (see {@link MockClientCli#toConfig()}), so each can be absent as a whole when the mock client
 * isn't configured to host, join, or queue. {@link #GROUPED_RECORD_COMPONENT_NAMES} and {@link
 * #GROUPED_CLI_FIELD_NAMES} carve those pairings out of the strict 1:1 checks below.
 */
final class MockClientCliRecordSyncTest {

    /** Names of CLI Field Names that don't correspond to record component names. */
    private static final Set<String> CLI_ONLY_FIELD_NAMES = Set.of("configFile");

    /** Record components populated from more than one CLI option; see class javadoc. */
    private static final Set<String> GROUPED_RECORD_COMPONENT_NAMES =
            Set.of("hostConfig", "joinConfig", "queueConfig");

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
                    "gameJoinPassword",
                    "queueName",
                    "queueFaction");

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
                            + "MockClientCli or its mixins: "
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
                    "MockClientCli or a mixin has @Option-annotated fields with no matching "
                            + "record component on MockClientConfig: "
                            + extraOnCli);
        }
    }

    @Test
    void cliOptionFieldNamesMatchTheirCliFlagsExactly() {
        for (Field field : cliOptionFields()) {
            Option option = field.getAnnotation(Option.class);
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
        // .size() (8 host-* options collapse into 1 hostConfig record component, 2 join options
        // collapse into 1 joinConfig component, and 2 queue-* options collapse into 1
        // queueConfig component).
        // Both went up by one for --mock-game-crash-after-seconds (WBS-5.2), which is an ordinary
        // ungrouped option: one flag, one record component, so the gap between the two is unmoved.
        // Leaves are counted, so nesting the groups (#143) left both numbers as they were.
        long expectedRecordComponents = 29;
        long expectedCliOptionsExcludingHelpers = 38;

        List<String> leaves = ConfigLeaves.names();
        assertEquals(leaves.size(), Set.copyOf(leaves).size(), "leaves share a name: " + leaves);
        long actualRecordComponents = leaves.size();
        long actualCliOptions = cliOptionFields().size() - CLI_ONLY_FIELD_NAMES.size();

        assertEquals(
                expectedRecordComponents,
                actualRecordComponents,
                "MockClientConfig leaf-component count drifted; update this test if "
                        + "intentional.");
        assertEquals(
                expectedCliOptionsExcludingHelpers,
                actualCliOptions,
                "MockClientCli @Option-annotated field count drifted; update this test if "
                        + "intentional.");
    }

    private static Set<String> collectRecordComponentNames() {
        return new TreeSet<>(ConfigLeaves.names());
    }

    private static Set<String> collectCliOptionFieldNames() {
        Set<String> out = new HashSet<>();
        for (Field field : cliOptionFields()) {
            out.add(field.getName());
        }
        return out;
    }

    /**
     * The {@link Option} fields declared on {@link MockClientCli} and on the type of each of its
     * {@link Mixin} fields, which is where picocli finds them too.
     */
    private static List<Field> cliOptionFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : MockClientCli.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Option.class)) {
                fields.add(field);
            } else if (field.isAnnotationPresent(Mixin.class)) {
                for (Field mixed : field.getType().getDeclaredFields()) {
                    if (mixed.isAnnotationPresent(Option.class)) {
                        fields.add(mixed);
                    }
                }
            }
        }
        return fields;
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
