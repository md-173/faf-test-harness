package com.faforever.testharness.game.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.ParameterException;

/**
 * Tests for {@link MockGameCli}: a complete arg set populates every field; anything missing,
 * unknown, malformed, or out of range fails the parse with no partial config.
 */
final class MockGameCliTest {

    /** The exact argv {@code MockGameLauncher} emits (spec §2.8 order). */
    private static final String[] VALID_ARGS = {
        "--gpgnet-port", "7237",
        "--lobby-port", "6112",
        "--player-id", "42",
        "--player-login", "Rhiza",
        "--game-uid", "9001",
        "--game-options", "Victory=demoralization",
    };

    @Test
    void completeArgSetPopulatesEveryField() {
        MockGameConfig config = MockGameCli.parse(VALID_ARGS);

        assertEquals(7237, config.gpgNetPort());
        assertEquals(6112, config.lobbyPort());
        assertEquals(42, config.playerId());
        assertEquals("Rhiza", config.playerLogin());
        assertEquals(9001, config.gameUid());
        assertEquals("demoralization", config.gameOptions().get("Victory"));
    }

    @Test
    void zeroGameUidIsAcceptedAsNoSession() {
        String[] args = VALID_ARGS.clone();
        args[9] = "0";

        // What the standalone launch-game diagnostic passes, having no lobby session.
        assertEquals(0, MockGameCli.parse(args).gameUid());
    }

    @Test
    void noGameOptionsAllowed() {
        // Truncate the last two args (the --game-options)
        String[] args = Arrays.copyOf(VALID_ARGS, VALID_ARGS.length - 2);

        assertEquals(0, MockGameCli.parse(args).gameOptions().size());
    }

    @Test
    void negativeGameUidFailsTheParse() {
        String[] args = VALID_ARGS.clone();
        args[9] = "-1";

        assertThrows(ParameterException.class, () -> MockGameCli.parse(args));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "--gpgnet-port",
                "--lobby-port",
                "--player-id",
                "--player-login",
                "--game-uid"
            })
    void missingAnyRequiredArgumentFailsTheParse(final String omitted) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < VALID_ARGS.length; i += 2) {
            if (!VALID_ARGS[i].equals(omitted)) {
                args.add(VALID_ARGS[i]);
                args.add(VALID_ARGS[i + 1]);
            }
        }

        assertThrows(
                ParameterException.class, () -> MockGameCli.parse(args.toArray(String[]::new)));
    }

    @Test
    void unknownArgumentFailsTheParse() {
        String[] args = withExtra(VALID_ARGS, "--faction", "3");

        assertThrows(ParameterException.class, () -> MockGameCli.parse(args));
    }

    @Test
    void malformedPortFailsTheParse() {
        String[] args = VALID_ARGS.clone();
        args[1] = "not-a-port";

        assertThrows(ParameterException.class, () -> MockGameCli.parse(args));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "65536"})
    void outOfRangeGpgNetPortFailsTheParse(final String port) {
        String[] args = VALID_ARGS.clone();
        args[1] = port;

        assertThrows(ParameterException.class, () -> MockGameCli.parse(args));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "65536"})
    void outOfRangeLobbyPortFailsTheParse(final String port) {
        String[] args = VALID_ARGS.clone();
        args[3] = port;

        assertThrows(ParameterException.class, () -> MockGameCli.parse(args));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-7"})
    void nonPositivePlayerIdFailsTheParse(final String id) {
        String[] args = VALID_ARGS.clone();
        args[5] = id;

        assertThrows(ParameterException.class, () -> MockGameCli.parse(args));
    }

    @Test
    void blankPlayerLoginFailsTheParse() {
        String[] args = VALID_ARGS.clone();
        args[7] = "   ";

        assertThrows(ParameterException.class, () -> MockGameCli.parse(args));
    }

    /**
     * Copies {@code base} with two extra tokens appended.
     *
     * @param base the valid argv
     * @param extra the flag and value to append
     * @return the extended argv
     */
    private static String[] withExtra(final String[] base, final String... extra) {
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        return args;
    }
}
