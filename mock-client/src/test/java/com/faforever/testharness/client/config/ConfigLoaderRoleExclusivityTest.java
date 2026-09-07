package com.faforever.testharness.client.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * The mock client plays one role per run (WBS-3.1.1.9-fix, #310).
 *
 * <p>Host and join are both IDLE entry hooks, so nothing sequences them: configuring both means
 * both fire on the same entry to IDLE and the client sends a {@code game_host} and a {@code
 * game_join} to the lobby in whatever order the hooks were registered. Rejected at config load,
 * where the operator can act on it, rather than on the wire where the lobby's answer to the second
 * request depends on what its answer to the first did to the session.
 */
final class ConfigLoaderRoleExclusivityTest {

    private static final String[] HOST_FLAGS = {
        "--host-title=Test", "--host-map=SCMP_007", "--host-mod=faf", "--host-visibility=public"
    };

    private static final String JOIN_FLAG = "--target-game-id=4242";

    /** The defect: both roles configured, both hooks armed, conflicting requests on one entry. */
    @Test
    void configuringHostAndJoinTogetherFailsTheLoad() {
        String[] args = concat(concat(TestFixtures.minimalRequiredCli(), HOST_FLAGS), JOIN_FLAG);

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(args, Map.of()));

        String message = ex.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(
                message.contains("host") && message.contains("join"),
                "the error must name both roles in conflict. Got: " + ex.getMessage());
        assertTrue(
                message.contains("--host-title") && message.contains("--target-game-id"),
                "the error must name flags the operator typed, not record components. Got: "
                        + ex.getMessage());
    }

    /**
     * A config file reaches the record without passing through the CLI, which is the entry point a
     * picocli {@code @ArgGroup(exclusive = true)} would not have covered — the reason the check
     * lives in the compact constructor.
     */
    @Test
    void theSameConflictFromAConfigFileAlsoFails() {
        Map<String, String> env = TestFixtures.minimalRequiredEnv();
        env.put("FAF_MOCK_CLIENT_HOST_TITLE", "Test");
        env.put("FAF_MOCK_CLIENT_HOST_MAP", "SCMP_007");
        env.put("FAF_MOCK_CLIENT_HOST_MOD", "faf");
        env.put("FAF_MOCK_CLIENT_HOST_VISIBILITY", "public");
        env.put("FAF_MOCK_CLIENT_TARGET_GAME_ID", "4242");

        CommandLine.ParameterException ex =
                assertThrows(
                        CommandLine.ParameterException.class,
                        () -> ConfigLoader.load(new String[] {}, env));

        assertTrue(
                ex.getMessage().toLowerCase(Locale.ROOT).contains("only one role"),
                "Got: " + ex.getMessage());
    }

    /** Single-role runs are exactly as they were. */
    @Test
    void hostingAloneIsUnaffected() {
        String[] args = concat(TestFixtures.minimalRequiredCli(), HOST_FLAGS);

        assertDoesNotThrow(() -> ConfigLoader.load(args, Map.of()));
    }

    /** The other half of the same guarantee. */
    @Test
    void joiningAloneIsUnaffected() {
        String[] args = concat(TestFixtures.minimalRequiredCli(), JOIN_FLAG);

        assertDoesNotThrow(() -> ConfigLoader.load(args, Map.of()));
    }

    /** No role at all stays legal: that is the observer run, and it always was. */
    @Test
    void configuringNoRoleIsStillLegal() {
        assertDoesNotThrow(() -> ConfigLoader.load(TestFixtures.minimalRequiredCli(), Map.of()));
    }

    private static String[] concat(final String[] base, final String... extra) {
        return Stream.concat(Arrays.stream(base), Arrays.stream(extra)).toArray(String[]::new);
    }
}
