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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        "--game-uid", "9001",
    };

    private final ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);

    private String stderr() {
        return errBuffer.toString(StandardCharsets.UTF_8);
    }

    /**
     * The error message alone. The usage text printed after it lists every option, so asserting
     * against the whole of stderr would match any option name no matter what the error says.
     *
     * @return the first line of stderr, or an empty string if nothing was written
     */
    private String errorLine() {
        return stderr().lines().findFirst().orElse("");
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
        assertTrue(
                errorLine().contains("--player-login"), "message must name the missing argument");
        assertTrue(stderr().contains("Usage:"), "usage text must be printed");
    }

    @Test
    void unknownArgumentReturnsUsageAndNamesIt() {
        String[] args = new String[VALID_ARGS.length + 2];
        System.arraycopy(VALID_ARGS, 0, args, 0, VALID_ARGS.length);
        args[VALID_ARGS.length] = "--faction";
        args[VALID_ARGS.length + 1] = "3";

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(errorLine().contains("--faction"), "message must name the unknown argument");
    }

    /**
     * One bad value at a time — out of range, non-positive, blank, malformed — each rejected with
     * the usage code and an error line naming the argument at fault.
     *
     * @param index the position of the value in {@link #VALID_ARGS}
     * @param value the bad value to substitute
     * @param name the option name the error line must contain
     */
    @ParameterizedTest
    @CsvSource({
        "1, 70000,      --gpgnet-port",
        "3, 70000,      --lobby-port",
        "5, 0,          --player-id",
        "7, '   ',      --player-login",
        "9, -1,         --game-uid",
        "1, not-a-port, --gpgnet-port",
    })
    void rejectionNamesTheArgumentOnTheErrorLine(
            final int index, final String value, final String name) {
        String[] args = VALID_ARGS.clone();
        args[index] = value;

        ParseOutcome outcome = MockGameCli.parseOrReport(args, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(errorLine().contains(name), "error line must name " + name);
    }
}
