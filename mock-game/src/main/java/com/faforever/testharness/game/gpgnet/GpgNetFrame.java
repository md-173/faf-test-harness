package com.faforever.testharness.game.gpgnet;

import java.util.List;

/**
 * A single GPGNet message, modelled generically as a command name plus an ordered list of
 * arguments. Each argument is either an {@link Integer} (wire tag {@code 0x00}) or a {@link String}
 * (wire tag {@code 0x01}); no other types cross the wire (gpgnet-format-spec §4.1). This is the one
 * frame type the codec, transport, dispatcher, and sender all share — there are deliberately <em>no
 * per-command classes</em>, matching the untyped {@code command + args} shape of the reference
 * codecs.
 *
 * <p>Arguments are read positionally via {@link #intArg(int)} / {@link #stringArg(int)}; the caller
 * knows each command's signature from the catalog (gpgnet-format-spec §7) rather than from types.
 *
 * @param command the command name (e.g. {@code "GameState"}, {@code "CreateLobby"}); never null
 * @param args the ordered arguments, each an {@link Integer} or {@link String}; never null, may be
 *     empty. The list is an unmodifiable copy.
 */
public record GpgNetFrame(String command, List<Object> args) {

    /**
     * Canonical constructor — validates and defensively copies. Rejects a null command, a null args
     * list, and any argument that is not an {@link Integer} or {@link String}.
     *
     * @throws NullPointerException if {@code command} or {@code args} (or any element) is null
     * @throws IllegalArgumentException if any argument is not an {@link Integer} or {@link String}
     */
    public GpgNetFrame {
        if (command == null) {
            throw new NullPointerException("command");
        }
        args = List.copyOf(args); // immutable + rejects null elements
        for (Object arg : args) {
            if (!(arg instanceof Integer) && !(arg instanceof String)) {
                throw new IllegalArgumentException(
                        "GPGNet arg must be Integer or String, got " + arg.getClass().getName());
            }
        }
    }

    /**
     * Build a frame from a command and its ordered arguments. Each argument must be an {@link
     * Integer} or {@link String}.
     *
     * @param command the command name
     * @param args the ordered arguments
     * @return the frame
     */
    public static GpgNetFrame of(final String command, final Object... args) {
        return new GpgNetFrame(command, List.of(args));
    }

    /**
     * @return the number of arguments.
     */
    public int argCount() {
        return args.size();
    }

    /**
     * Read the argument at {@code index} as an int.
     *
     * @param index zero-based argument position
     * @return the argument value
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     * @throws IllegalArgumentException if the argument at {@code index} is not an {@link Integer}
     */
    public int intArg(final int index) {
        Object arg = args.get(index);
        if (arg instanceof Integer value) {
            return value;
        }
        throw new IllegalArgumentException(
                "arg[" + index + "] of '" + command + "' is not an int: " + arg);
    }

    /**
     * Read the argument at {@code index} as a string.
     *
     * @param index zero-based argument position
     * @return the argument value
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     * @throws IllegalArgumentException if the argument at {@code index} is not a {@link String}
     */
    public String stringArg(final int index) {
        Object arg = args.get(index);
        if (arg instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException(
                "arg[" + index + "] of '" + command + "' is not a string: " + arg);
    }
}
