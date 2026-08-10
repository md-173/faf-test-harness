package com.faforever.testharness.game.net;

import java.nio.ByteBuffer;

/**
 * The mock game's simulated per-tick UDP payload (WBS-3.2.2.5). This is a format <em>we</em>
 * define, not the real game's: both ends of a test are mock games and the ICE adapter proxies
 * datagrams opaquely, so the only requirement is that it is small and self-describing enough for
 * the receiving side (WBS-3.2.2.6 / R58b) to assert delivery and ordering.
 *
 * <p>Wire layout is a fixed {@value #BYTES} bytes, big-endian: {@code senderId} (int32), {@code
 * sequence} (int64), {@code timestampMillis} (int64). Well under 1&nbsp;KB, so it fits comfortably
 * in the adapter's ICE data channel.
 *
 * @param senderId the emitting mock game's FAF player id
 * @param sequence a per-peer monotonic sequence number, so the receiver can detect loss/reordering
 * @param timestampMillis the sender's wall-clock time at emission ({@link
 *     System#currentTimeMillis()})
 */
public record GameDatagram(int senderId, long sequence, long timestampMillis) {

    /** Fixed encoded size: int32 senderId + int64 sequence + int64 timestamp. */
    public static final int BYTES = Integer.BYTES + Long.BYTES + Long.BYTES;

    /**
     * Encode to the fixed {@value #BYTES}-byte wire form.
     *
     * @return the encoded bytes
     */
    public byte[] toBytes() {
        return ByteBuffer.allocate(BYTES)
                .putInt(senderId)
                .putLong(sequence)
                .putLong(timestampMillis)
                .array();
    }

    /**
     * Decode from a received datagram's backing array.
     *
     * @param data the datagram's data buffer (e.g. {@code packet.getData()})
     * @param length the number of valid bytes (e.g. {@code packet.getLength()})
     * @return the decoded datagram
     * @throws IllegalArgumentException if {@code length} is not exactly {@value #BYTES}
     */
    public static GameDatagram parse(final byte[] data, final int length) {
        if (length != BYTES) {
            throw new IllegalArgumentException(
                    "GameDatagram is exactly " + BYTES + " bytes, got " + length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        return new GameDatagram(buffer.getInt(), buffer.getLong(), buffer.getLong());
    }
}
