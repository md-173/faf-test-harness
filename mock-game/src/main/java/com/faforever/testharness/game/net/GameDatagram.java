package com.faforever.testharness.game.net;

import com.faforever.testharness.game.config.Seat;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * The mock game's simulated per-tick UDP payload (WBS-3.2.2.5). This is a format <em>we</em>
 * define, not the real game's: both ends of a test are mock games and the ICE adapter proxies
 * datagrams opaquely, so the only requirement is that it is small and self-describing enough for
 * the receiving side (WBS-3.2.2.6 / R58b) to assert delivery and ordering.
 *
 * <p>It also carries its sender's {@link Seat}, the mock's stand-in for the {@code AddPlayer}
 * message a joiner in FA's matchmaker lobby sends its host once their link is up (WBS-3.2.4.3-fix,
 * #534). FA delivers lobby messages reliably and these datagrams can be lost, so every one carries
 * the seat and the host takes it from whichever arrives first.
 *
 * <p>Wire layout is a fixed {@value #BYTES} bytes, big-endian: {@code senderId} (int32), {@code
 * sequence} (int64), {@code timestampMillis} (int64), then the seat's {@code team}, {@code
 * startSpot} and {@code faction} (int32 each). Well under 1&nbsp;KB, so it fits comfortably in the
 * adapter's ICE data channel.
 *
 * @param senderId the emitting mock game's FAF player id
 * @param sequence a per-peer monotonic sequence number, so the receiver can detect loss/reordering
 * @param timestampMillis the sender's wall-clock time at emission ({@link
 *     System#currentTimeMillis()})
 * @param seat the sender's seat, {@link Seat#DEFAULT} when it was launched without one
 */
public record GameDatagram(int senderId, long sequence, long timestampMillis, Seat seat) {

    /** Fixed encoded size: int32 senderId + int64 sequence + int64 timestamp + 3 int32 seat. */
    public static final int BYTES =
            Integer.BYTES + Long.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES + Integer.BYTES;

    /** Rejects a missing seat, which {@link #toBytes()} could not encode. */
    public GameDatagram {
        Objects.requireNonNull(seat, "seat");
    }

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
                .putInt(seat.team())
                .putInt(seat.startSpot())
                .putInt(seat.faction())
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
        return new GameDatagram(
                buffer.getInt(),
                buffer.getLong(),
                buffer.getLong(),
                new Seat(buffer.getInt(), buffer.getInt(), buffer.getInt()));
    }
}
