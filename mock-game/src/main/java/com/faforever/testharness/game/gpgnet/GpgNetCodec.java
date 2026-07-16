package com.faforever.testharness.game.gpgnet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte-level GPGNet frame codec (gpgnet-format-spec §2-§6). Encodes a {@link GpgNetFrame} to bytes
 * and decodes bytes back to a frame, with no message semantics — it is a pure {@code frame <->
 * bytes} layer shared by the transport.
 *
 * <p>Wire format: {@code [command string][chunk-count int32 LE][N chunks]}, each chunk a 1-byte
 * type tag ({@code 0x00} int, {@code 0x01} string) followed by a tag-specific payload; a string is
 * a 4-byte little-endian length prefix (UTF-8 <em>byte</em> length) followed by the UTF-8 bytes.
 * Every multi-byte integer is little-endian signed {@code int32}. There is no outer length envelope
 * (§2.1).
 *
 * <p>Faithful-to-upstream decisions:
 *
 * <ul>
 *   <li>The string length prefix is the UTF-8 byte length, mirroring the Go writer, not Java
 *       upstream's {@code string.length()} defect (§4.4).
 *   <li>The reader decodes tag {@code 0x00} as an int and treats <em>any other tag</em> as a
 *       length-prefixed string (§4.2).
 *   <li>{@code chunkCount > 10} is rejected on read, mirroring the upstream {@code MAX_CHUNK_SIZE}
 *       guard (§4.3); the largest in-spec frame ({@code CreateLobby}) has 5 args.
 *   <li>The {@code /t}->tab / {@code /n}->newline reader substitution (§3.3) is <em>not</em>
 *       applied: it is a no-op in the mock-game direction and applying it would break the
 *       encode/decode round-trip. Strings are decoded verbatim.
 * </ul>
 */
public final class GpgNetCodec {

    /** Chunk type tag for a signed {@code int32} argument (gpgnet-format-spec §4.1). */
    private static final int TAG_INT = 0x00;

    /** Chunk type tag for a length-prefixed UTF-8 string argument (§4.1). */
    private static final int TAG_STRING = 0x01;

    /** Hard cap on chunk count, mirroring upstream {@code MAX_CHUNK_SIZE} (§4.3). */
    private static final int MAX_CHUNK_COUNT = 10;

    /**
     * Defensive cap on a decoded string's byte length, guarding against a desynced stream (§4.3).
     */
    private static final int MAX_STRING_LENGTH = 1024 * 1024;

    private GpgNetCodec() {}

    /**
     * Encode a frame to its exact wire bytes.
     *
     * @param frame the frame to encode
     * @return the encoded bytes
     * @throws IllegalArgumentException if the frame has more than {@value #MAX_CHUNK_COUNT} args
     *     (the mock game must never emit an over-long frame, §4.3)
     */
    public static byte[] encode(final GpgNetFrame frame) {
        if (frame.argCount() > MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException(
                    "GPGNet frame '"
                            + frame.command()
                            + "' has "
                            + frame.argCount()
                            + " args, exceeding the max of "
                            + MAX_CHUNK_COUNT);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeString(out, frame.command());
        writeInt32Le(out, frame.argCount());
        for (Object arg : frame.args()) {
            if (arg instanceof Integer value) {
                out.write(TAG_INT);
                writeInt32Le(out, value);
            } else {
                // Guaranteed a String by the GpgNetFrame invariant.
                out.write(TAG_STRING);
                writeString(out, (String) arg);
            }
        }
        return out.toByteArray();
    }

    /**
     * Decode exactly one frame from a byte array. Convenience wrapper over {@link
     * #readFrame(InputStream)}; trailing bytes after the first complete frame are ignored.
     *
     * @param bytes the encoded frame
     * @return the decoded frame
     * @throws IOException if the bytes are malformed or truncated
     */
    public static GpgNetFrame decode(final byte[] bytes) throws IOException {
        return readFrame(new ByteArrayInputStream(bytes));
    }

    /**
     * Read exactly one frame from a blocking stream. Consumes {@code command + chunk-count + that
     * many chunks} and returns when the last chunk's payload is read; the next byte on the stream
     * is the first byte of the next frame (§2.1). Blocks until each field's bytes arrive.
     *
     * @param in the stream to read from
     * @return the decoded frame
     * @throws EOFException if the stream closes mid-frame (a truncated frame is unrecoverable,
     *     §5.3)
     * @throws IOException if the frame is malformed (bad chunk count or string length, §5.3)
     */
    public static GpgNetFrame readFrame(final InputStream in) throws IOException {
        String command = readString(in);
        int chunkCount = readInt32Le(in);
        if (chunkCount < 0 || chunkCount > MAX_CHUNK_COUNT) {
            throw new IOException("GPGNet chunk count out of range: " + chunkCount);
        }
        List<Object> args = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
            int tag = readByte(in);
            if (tag == TAG_INT) {
                args.add(readInt32Le(in));
            } else {
                // Any non-0x00 tag is a length-prefixed string (spec §4.2 reader fallback).
                args.add(readString(in));
            }
        }
        return new GpgNetFrame(command, args);
    }

    private static void writeString(final ByteArrayOutputStream out, final String value) {
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        writeInt32Le(out, body.length);
        out.writeBytes(body);
    }

    private static void writeInt32Le(final ByteArrayOutputStream out, final int value) {
        out.writeBytes(
                ByteBuffer.allocate(Integer.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(value)
                        .array());
    }

    private static String readString(final InputStream in) throws IOException {
        int length = readInt32Le(in);
        if (length < 0 || length > MAX_STRING_LENGTH) {
            throw new IOException("GPGNet string length out of range: " + length);
        }
        return new String(readFully(in, length), StandardCharsets.UTF_8);
    }

    private static int readInt32Le(final InputStream in) throws IOException {
        return ByteBuffer.wrap(readFully(in, Integer.BYTES))
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt();
    }

    private static int readByte(final InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("GPGNet stream closed mid-frame (expected a chunk tag)");
        }
        return b;
    }

    private static byte[] readFully(final InputStream in, final int n) throws IOException {
        byte[] buf = in.readNBytes(n);
        if (buf.length < n) {
            throw new EOFException(
                    "GPGNet stream closed mid-frame (wanted "
                            + n
                            + " bytes, got "
                            + buf.length
                            + ")");
        }
        return buf;
    }
}
