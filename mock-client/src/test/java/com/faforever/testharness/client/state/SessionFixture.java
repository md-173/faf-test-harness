package com.faforever.testharness.client.state;

import com.faforever.testharness.client.lobby.SessionState;
import java.util.Map;

/**
 * The lobby-assigned identity lifecycle tests run under. Values are deliberately unlike the config
 * defaults the test configs carry, so a test asserting on identity fails when the launchers fall
 * back to config instead of using the session (WBS-3.1.2.9).
 */
final class SessionFixture {

    /** Stand-in for the {@code welcome.me} block the lobby would send. */
    static final SessionState SESSION =
            new SessionState(9001, "welcome-login", "FAF", "AU", Map.of(), "2026-08-10T00:00:00Z");

    private SessionFixture() {}
}
