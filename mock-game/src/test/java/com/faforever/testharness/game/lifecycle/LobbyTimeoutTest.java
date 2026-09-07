package com.faforever.testharness.game.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.faforever.testharness.game.config.MockGameConfig;
import com.faforever.testharness.game.gpgnet.GpgNetConnection;
import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.game.gpgnet.ScriptedGpgNetServer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The optional lobby give-up timer (WBS-3.2.1.3, #323).
 *
 * <p>Without it the game waits in LOBBY indefinitely, which is faithful and is what a consumer
 * asserting on the GPGNet handshake wants — but the consumer then has to end the run, and a
 * signal-terminated run reports 143 or 130 rather than a harness code, so a run that did exactly
 * what was asked looks like one killed for hanging. With it, the game gives up on its own and exits
 * through the normal path.
 *
 * <p>Both halves matter and both are here: the timer fires when nothing arrives, and it never fires
 * once something drives the game into a role.
 */
@Timeout(30)
final class LobbyTimeoutTest {

    /** Short enough to keep the suite quick, long enough that arrival always wins the race. */
    private static final int LOBBY_TIMEOUT_SECONDS = 1;

    /** Comfortably longer than the timer, so a failure to fire fails rather than flakes. */
    private static final int AWAIT_SECONDS = 5;

    /** Long enough that it cannot fire during the "must not trip" case. */
    private static final int GENEROUS_TIMEOUT_SECONDS = 20;

    private ScriptedGpgNetServer gpgnet;
    private MockGameLifecycle lifecycle;

    @BeforeEach
    void setUp() throws IOException {
        gpgnet = new ScriptedGpgNetServer();
        gpgnet.start();
    }

    @AfterEach
    void tearDown() {
        if (lifecycle != null) {
            lifecycle.shutdown().run();
        }
        gpgnet.stop();
    }

    /** Nothing drives the game into a role, so it gives up and reports why. */
    @Test
    void theTimerEndsAGameNothingEverDrove() throws Exception {
        lifecycle = lifecycleWithLobbyTimeout(LOBBY_TIMEOUT_SECONDS);
        reachLobby();

        lifecycle.stateReached(GameState.ENDED).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(
                MockGameLifecycle.ExitStatus.LOBBY_TIMEOUT,
                lifecycle.getExitStatus(),
                "giving up in the lobby is its own outcome, not a failure and not a played match");
    }

    /**
     * A game driven into a role never trips it. The cancellation is the state machine's — {@code
     * commitTransition} disarms every pending timeout on any state change — so this is what pins
     * that the timer is armed in a way that participates in it.
     */
    @Test
    void aHostedGameNeverTripsTheTimer() throws Exception {
        lifecycle = lifecycleWithLobbyTimeout(LOBBY_TIMEOUT_SECONDS);
        reachLobby();

        gpgnet.sendFrame(new GpgNetFrame("HostGame", List.of("scm_007")));
        lifecycle.stateReached(GameState.HOSTING).get(AWAIT_SECONDS, TimeUnit.SECONDS);

        // Well past the timer: had it survived the transition, it would have fired by now.
        Thread.sleep(Duration.ofSeconds(LOBBY_TIMEOUT_SECONDS).toMillis() * 2);
        assertEquals(
                GameState.HOSTING,
                lifecycle.getState(),
                "a game that reached a role must not be ended by the lobby timer");
    }

    /** Unset is the default, and an unset timer arms nothing: today's behaviour, unchanged. */
    @Test
    void withoutTheFlagTheGameWaitsInTheLobbyIndefinitely() throws Exception {
        lifecycle = lifecycleWithLobbyTimeout(-1);
        reachLobby();

        assertThrows(
                TimeoutException.class,
                () -> lifecycle.stateReached(GameState.ENDED).get(3, TimeUnit.SECONDS),
                "with no timeout configured the game must sit in the lobby as it always has");
        assertEquals(GameState.LOBBY, lifecycle.getState());
    }

    /** A generous timer is not a substitute for the unset case: it must still not fire early. */
    @Test
    void aGenerousTimerDoesNotFireEarly() throws Exception {
        lifecycle = lifecycleWithLobbyTimeout(GENEROUS_TIMEOUT_SECONDS);
        reachLobby();

        assertThrows(
                TimeoutException.class,
                () -> lifecycle.stateReached(GameState.ENDED).get(2, TimeUnit.SECONDS),
                "the game gave up well before the timeout it was given");
    }

    /** Builds a lifecycle against the fixture with {@code seconds} as its lobby timeout. */
    private MockGameLifecycle lifecycleWithLobbyTimeout(final int seconds) {
        MockGameConfig config =
                new MockGameConfig(gpgnet.port(), 50001, 1, "Rhiza", 9001, Map.of(), -1, seconds);
        return new MockGameLifecycle(
                config, new GpgNetConnection(gpgnet.port()), Duration.ofSeconds(5), null, null);
    }

    /** Drives the fixture as far as LOBBY, which is where the timer applies. */
    private void reachLobby() throws Exception {
        gpgnet.awaitClient();
        lifecycle.stateReached(GameState.IDLE).get(AWAIT_SECONDS, TimeUnit.SECONDS);
        gpgnet.sendFrame(new GpgNetFrame("CreateLobby", List.of(0, 5000, "Rhiza", 1, 1)));
        lifecycle.stateReached(GameState.LOBBY).get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }
}
