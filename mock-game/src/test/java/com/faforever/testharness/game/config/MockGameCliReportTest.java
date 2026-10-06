package com.faforever.testharness.game.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.game.config.MockGameCli.ParseOutcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
        "--game-option", "Victory=demoralization",
    };

    private final ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
    private final PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
    private final ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);

    private String stderr() {
        return errBuffer.toString(StandardCharsets.UTF_8);
    }

    private String stdout() {
        return outBuffer.toString(StandardCharsets.UTF_8);
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
        ParseOutcome outcome = MockGameCli.parseOrReport(VALID_ARGS, out, err);

        assertEquals(ExitCodes.OK, outcome.exitCode());
        assertNotNull(outcome.config());
        assertEquals(42, outcome.config().playerId());
        assertEquals("Rhiza", outcome.config().playerLogin());
        assertEquals("demoralization", outcome.config().gameOptions().get("Victory"));
        assertTrue(stderr().isEmpty(), "a valid set must pass through silently; got: " + stderr());
    }

    /**
     * {@code --version} identifies the jar, so it must work with none of the session facts and
     * without touching stderr, which the Mock Client captures as failure output. From class
     * directories there is no manifest, so the fallback is what prints; the jar route, the one that
     * matters to a consumer, is {@code VersionFlagJarTest}.
     */
    @Test
    void versionPrintsTheProviderTextAndExitsOk() {
        ParseOutcome outcome = MockGameCli.parseOrReport(new String[] {"--version"}, out, err);

        assertEquals(ExitCodes.OK, outcome.exitCode());
        assertNull(outcome.config(), "there is no game to run after printing version text");
        assertEquals(VersionProvider.DEVELOPMENT_BUILD, stdout().strip());
        assertTrue(stderr().isEmpty(), "version text must not reach stderr; got: " + stderr());
    }

    @Test
    void helpPrintsUsageOnStdoutAndExitsOk() {
        ParseOutcome outcome = MockGameCli.parseOrReport(new String[] {"--help"}, out, err);

        assertEquals(ExitCodes.OK, outcome.exitCode());
        assertNull(outcome.config(), "there is no game to run after printing help");
        assertTrue(stdout().contains("Usage: mock-game"), "got: " + stdout());
        assertTrue(stderr().isEmpty(), "help text must not reach stderr; got: " + stderr());
    }

    @Test
    void missingRequiredArgumentReturnsUsageAndNamesTheArgument() {
        String[] args = {"--gpgnet-port", "7237", "--lobby-port", "6112", "--player-id", "42"};

        ParseOutcome outcome = MockGameCli.parseOrReport(args, out, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config(), "no partial config on a usage error");
        assertTrue(
                errorLine().contains("--player-login"), "message must name the missing argument");
        assertTrue(stderr().contains("Usage:"), "usage text must be printed");
    }

    /**
     * An argument holding a newline cannot forge the {@code Usage:} line (#514): picocli quotes it
     * back in the error, so the newline is escaped onto that line. Checked for both routes a
     * caller-controlled value takes into the message, an unknown option and a value that fails
     * conversion. One error line alone would not prove it, since an unescaped forgery is itself
     * where a reader splitting on {@code Usage:} stops, so the escaped text must be on the line and
     * no line may begin with the forgery.
     *
     * @param argument the offending argument, appended to an otherwise valid argv
     * @param errorPrefix how the error line must begin
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "--bogus\\nUsage: FORGED | Unknown option: ",
                "--gpgnet-port=zz\\nUsage: FORGED | Invalid value for option '--gpgnet-port': "
            })
    void aNewlineInAnArgumentCannotForgeTheUsageLine(
            final String argument, final String errorPrefix) {
        String[] args = Arrays.copyOf(VALID_ARGS, VALID_ARGS.length + 1);
        args[VALID_ARGS.length] = argument.replace("\\n", "\n");

        ParseOutcome outcome = MockGameCli.parseOrReport(args, out, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        List<String> lines =
                stderr().lines().takeWhile(line -> !line.startsWith("Usage:")).toList();
        assertEquals(1, lines.size(), "the argument's newline split the error: " + lines);
        assertTrue(lines.get(0).startsWith(errorPrefix), "unexpected error line: " + lines.get(0));
        assertTrue(
                lines.get(0).contains("\\nUsage: FORGED"),
                "the newline was not escaped onto the error line: " + lines.get(0));
        assertTrue(
                stderr().lines().noneMatch(line -> line.startsWith("Usage: FORGED")),
                "a forged Usage: line reached stderr:\n" + stderr());
        assertTrue(stderr().contains("Usage: mock-game "), "no real usage block");
    }

    @Test
    void unknownArgumentReturnsUsageAndNamesIt() {
        String[] args = new String[VALID_ARGS.length + 2];
        System.arraycopy(VALID_ARGS, 0, args, 0, VALID_ARGS.length);
        args[VALID_ARGS.length] = "--faction";
        args[VALID_ARGS.length + 1] = "3";

        ParseOutcome outcome = MockGameCli.parseOrReport(args, out, err);

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

        ParseOutcome outcome = MockGameCli.parseOrReport(args, out, err);

        assertEquals(ExitCodes.USAGE, outcome.exitCode());
        assertNull(outcome.config());
        assertTrue(errorLine().contains(name), "error line must name " + name);
    }
}
