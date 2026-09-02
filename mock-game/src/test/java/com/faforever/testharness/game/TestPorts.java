package com.faforever.testharness.game;

import java.io.IOException;
import java.net.DatagramSocket;

/**
 * Free-port allocation for tests that bind real sockets.
 *
 * <p>Since WBS-4.3.2 a mock game binds its {@code --lobby-port} for real on {@code CreateLobby}, so
 * a test that drives one past IDLE takes a UDP port for the duration. A fixed port would collide
 * with the next test in the same JVM — and with whatever the developer happens to be running, since
 * the old fixtures used 6112 and 50001.
 *
 * <p>The probe socket is closed before the caller binds, which is a benign TOCTOU window: losing it
 * surfaces as the traffic session's "failed to bind" line rather than as a wrong answer.
 */
public final class TestPorts {

    private TestPorts() {}

    /**
     * Allocates a UDP port that is free at the moment of the call.
     *
     * @return the port number, already released
     */
    public static int freeUdpPort() {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("could not allocate a free UDP port", e);
        }
    }
}
