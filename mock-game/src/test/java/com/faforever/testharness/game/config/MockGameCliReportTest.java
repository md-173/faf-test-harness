package com.faforever.testharness.game.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.config.MockGameCli.ParseOutcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link MockGameCli#parseOrReport}: the exit-code contract and the stderr diagnostics.
 * Each rejection returns the usage code with a message naming the offending argument; a valid set
 * returns OK with the config and no output.
 */
final class MockGameCliReportTest {

    /** The exact argv {@code MockGameLauncher} emits (spec §2.8 order). */
    private static final String[] VALID_ARGS = {
        "--gpgnet-port", "7237",
        "--lobby-port", "6112",
        "--player-id", "42",
        "--player-login", "Rhiza",
    };

    private final ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);

    private String stderr() {
        return errBuffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    void validArgsReturnOkWithConfigAndNoOutput() {
        ParseOutcome outcome = MockGameCli.parseOrReport(VALID_ARGS, err);

        assertEquals(ExitCodes.OK, outcome.exitCode());
        assertNotNull(outcome.config());
        assertEquals(42, outcome.config().playerId());
        assertEquals("Rhiza", outcome.config().playerLogin());
        assertTrue(stderr().isEmpty(), "a valid set must pass through silently; got: " + stderr());
    }

    @Test
    void missingRequiredArgumentReturnsUsageAndNamesTheArgument() {
        String[] args = {"--gpgnet-port", "7237", "--lobby-port", "6112", "--player-id", "42"};

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config(), "no partial config on a usage error");
        assertTrue(stderr().contains("--player-login"), "message must name the missing argument");
        assertTrue(stderr().contains("Usage:"), "usage text must be printed");
    }

    @Test
    void unknownArgumentReturnsUsageAndNamesIt() {
        String[] args = new String[VALID_ARGS.length + 2];
        System.arraycopy(VALID_ARGS, 0, args, 0, VALID_ARGS.length);
        args[VALID_ARGS.length] = "--game-uid";
        args[VALID_ARGS.length + 1] = "9001";

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(stderr().contains("--game-uid"), "message must name the unknown argument");
    }

    @Test
    void outOfRangePortReturnsUsageNamingTheArgument() {
        String[] args = VALID_ARGS.clone();
        args[1] = "70000";

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(stderr().contains("--gpgnet-port"), "message must name the out-of-range port");
    }

    @Test
    void nonPositivePlayerIdReturnsUsageNamingTheArgument() {
        String[] args = VALID_ARGS.clone();
        args[5] = "0";

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(stderr().contains("--player-id"), "message must name the bad player id");
    }

    @Test
    void blankLoginReturnsUsageNamingTheArgument() {
        String[] args = VALID_ARGS.clone();
        args[7] = "   ";

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(stderr().contains("--player-login"), "message must name the blank login");
    }

    @Test
    void malformedPortReturnsUsage() {
        String[] args = VALID_ARGS.clone();
        args[1] = "not-a-port";

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(stderr().contains("--gpgnet-port"), "message must name the malformed port");
    }
}
