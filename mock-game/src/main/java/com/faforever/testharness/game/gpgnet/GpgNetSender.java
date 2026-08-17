package com.faforever.testharness.game.gpgnet;

import java.io.IOException;
import java.util.Set;

/**
 * Outbound GPGNet frame builders — the game→adapter side of the interface. A thin layer over the
 * transport (3.2.2.1): each method constructs the {@code (command, args)} tuple for one command and
 * hands it to a {@link GpgNetFrameSink} (the transport's {@code send(frame)}, which does the
 * encoding and writing). It holds <em>no</em> logic about <em>when</em> to send — that is the
 * lifecycle controller's job (#81); this only provides <em>how</em>.
 *
 * <p>Scope is the minimal set the mock-game lifecycle and result reporting need (gpgnet-format-spec
 * §7.1), not the full emit catalogue: the lifecycle frames {@code GameState} and {@code GameEnded};
 * the host/lobby-config frames {@code GameOption}, {@code PlayerOption}, {@code GameMods}; and the
 * result frames {@code GameResult} and {@code JsonStats}. The long tail ({@code AIOption}, {@code
 * ClearSlot}, {@code Chat}, …) and {@code BEAT} (Lockstep Tick, #80) are added on demand, not here.
 * There are deliberately no per-message-type classes — just frame-builders.
 */
public final class GpgNetSender {

    /** The four valid {@code GameState} values (§8); no {@code "Hosted"} or {@code "Live"}. */
    private static final Set<String> VALID_GAME_STATES =
            Set.of("Idle", "Lobby", "Launching", "Ended");

    /** Where built frames are sent — the transport's {@code send(frame)}. */
    private final GpgNetFrameSink sink;

    /**
     * @param sink the frame destination — normally a {@link GpgNetConnection}
     */
    public GpgNetSender(final GpgNetFrameSink sink) {
        this.sink = sink;
    }

    /**
     * Emit {@code GameState(state)}. The lifecycle state signal (§7.1).
     *
     * @param state one of {@code "Idle"}, {@code "Lobby"}, {@code "Launching"}, {@code "Ended"}
     * @throws IllegalArgumentException if {@code state} is not one of the four valid values
     * @throws IOException if the frame cannot be sent
     */
    public void gameState(final String state) throws IOException {
        if (!VALID_GAME_STATES.contains(state)) {
            throw new IllegalArgumentException(
                    "invalid GameState '" + state + "'; must be one of " + VALID_GAME_STATES);
        }
        sink.send(GpgNetFrame.of("GameState", state));
    }

    /**
     * Emit {@code GameOption(key, value)} — a lobby configuration option (§7.1).
     *
     * @param key the option key
     * @param value the option value
     * @throws IOException if the frame cannot be sent
     */
    public void gameOption(final String key, final String value) throws IOException {
        sink.send(GpgNetFrame.of("GameOption", key, value));
    }

    /**
     * Emit {@code PlayerOption(playerId, key, value)} — a per-player lobby option (§7.1).
     *
     * @param playerId the player id
     * @param key the option key
     * @param value the option value
     * @throws IOException if the frame cannot be sent
     */
    public void playerOption(final int playerId, final String key, final String value)
            throws IOException {
        sink.send(GpgNetFrame.of("PlayerOption", playerId, key, value));
    }

    /**
     * Emit {@code PlayerOption(playerId, key, value)} — a per-player lobby option (§7.1).
     *
     * @param playerId the player id
     * @param key the option key
     * @param value the option value
     * @throws IOException if the frame cannot be sent
     */
    public void playerOption(final int playerId, final String key, final int value)
            throws IOException {
        sink.send(GpgNetFrame.of("PlayerOption", playerId, key, value));
    }

    /**
     * Emit {@code GameMods(mode, args…)} — the variadic mods frame (§7.1). The two in-spec shapes
     * are {@code GameMods("activated", count)} and {@code GameMods("uids", spaceSeparatedUids)};
     * pass whichever shape the mode calls for as {@code args}. Args are passed through verbatim;
     * the codec enforces the ≤10-chunk bound.
     *
     * @param mode the mods mode (e.g. {@code "activated"} or {@code "uids"})
     * @param args the mode-specific trailing args (an int count, or a space-separated uid string)
     * @throws IOException if the frame cannot be sent
     * @throws IllegalArgumentException if the resulting frame exceeds the codec's chunk cap
     */
    public void gameMods(final String mode, final Object... args) throws IOException {
        Object[] frameArgs = new Object[args.length + 1];
        frameArgs[0] = mode;
        System.arraycopy(args, 0, frameArgs, 1, args.length);
        sink.send(GpgNetFrame.of("GameMods", frameArgs));
    }

    /**
     * Emit {@code GameResult(army, resultString)} — one per army (§7.1), e.g. {@code (1, "victory
     * 10")}.
     *
     * @param army the army number
     * @param resultString the result string (e.g. {@code "victory 10"})
     * @throws IOException if the frame cannot be sent
     */
    public void gameResult(final int army, final String resultString) throws IOException {
        sink.send(GpgNetFrame.of("GameResult", army, resultString));
    }

    /**
     * Emit {@code JsonStats(statsJson)} — the end-of-game stats blob (§7.1).
     *
     * @param statsJson the stats JSON payload (sent verbatim as a string)
     * @throws IOException if the frame cannot be sent
     */
    public void jsonStats(final String statsJson) throws IOException {
        sink.send(GpgNetFrame.of("JsonStats", statsJson));
    }

    /**
     * Emit {@code GameEnded()} — the no-arg lifecycle end signal (§7.1).
     *
     * @throws IOException if the frame cannot be sent
     */
    public void gameEnded() throws IOException {
        sink.send(GpgNetFrame.of("GameEnded"));
    }
}
