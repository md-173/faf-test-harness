package com.faforever.testharness.game.gpgnet;

import java.io.IOException;

/**
 * A destination for outbound GPGNet frames — the one operation the sender (3.2.2.3) needs from the
 * transport. {@link GpgNetConnection} implements it (its {@link GpgNetConnection#send(GpgNetFrame)}
 * encodes and writes the frame); tests can supply a capturing implementation to assert exact frames
 * without a socket. Keeping the sender bound to this narrow interface, rather than the whole
 * connection, keeps frame construction decoupled from transport lifecycle.
 */
@FunctionalInterface
public interface GpgNetFrameSink {

    /**
     * Encode and send a frame.
     *
     * @param frame the frame to send
     * @throws IOException if the frame cannot be sent
     */
    void send(GpgNetFrame frame) throws IOException;
}
