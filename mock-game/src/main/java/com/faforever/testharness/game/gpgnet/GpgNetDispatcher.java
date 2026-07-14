package com.faforever.testharness.game.gpgnet;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inbound GPGNet dispatch: a thin {@code command -> handler} layer over the transport's read loop
 * (3.2.2.1). Wired in via {@link GpgNetConnection#onFrame(Consumer)}, it routes each decoded frame
 * to the handler registered for its command name, handing over the generic {@link GpgNetFrame}
 * (args read positionally). It holds <em>no</em> reaction logic — what to do on {@code HostGame} vs
 * {@code JoinGame} is the lifecycle controller's job (#81); this only delivers the frame.
 *
 * <p>Scope is the lifecycle inbound set the mock game reacts to (gpgnet-format-spec §7.2): {@code
 * CreateLobby}, {@code HostGame}, {@code JoinGame}, {@code ConnectToPeer}, {@code
 * DisconnectFromPeer}. Dispatch itself is generic, though — any command can be registered; there
 * are no per-message-type classes. {@code JoinGame} / {@code ConnectToPeer} carry 3 positional args
 * on the local wire (§7.3), matching the frames the codec decodes.
 *
 * <p>Unknown or unhandled commands (e.g. inbound {@code IceMsg}, which the mock game does not
 * process) are logged once and dropped. A handler that throws is caught and logged — neither an
 * unknown command nor a throwing handler crashes the reader thread this runs on, matching the "log
 * and drop" convention of the mock client's transports.
 *
 * <p>Threading: {@link #accept(GpgNetFrame)} runs on the connection's reader thread, so handlers
 * must not block. Registration is concurrent-safe.
 */
public final class GpgNetDispatcher implements Consumer<GpgNetFrame> {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GpgNetDispatcher.class);

    /** Command name -> its single handler. */
    private final Map<String, Consumer<GpgNetFrame>> handlers = new ConcurrentHashMap<>();

    /** Commands already warned about, to suppress unhandled-command log spam. */
    private final Set<String> warnedUnknown = ConcurrentHashMap.newKeySet();

    /**
     * Register the handler for an inbound command. One handler per command; the handler receives
     * the decoded frame and reads its args positionally (per the §7.2 catalog).
     *
     * @param command the inbound command name (e.g. {@code "HostGame"})
     * @param handler invoked with the decoded frame on the reader thread (must not block)
     * @throws NullPointerException if {@code command} or {@code handler} is null
     * @throws IllegalArgumentException if a handler is already registered for {@code command}
     */
    public void registerHandler(final String command, final Consumer<GpgNetFrame> handler) {
        if (command == null) {
            throw new NullPointerException("command");
        }
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        if (handlers.putIfAbsent(command, handler) != null) {
            throw new IllegalArgumentException(
                    "a handler is already registered for GPGNet command '" + command + "'");
        }
    }

    /**
     * Route one decoded frame to its registered handler. An unregistered command is logged once and
     * dropped; a handler that throws is caught and logged. Never propagates an exception, so the
     * reader thread survives.
     *
     * @param frame the decoded inbound frame
     */
    @Override
    public void accept(final GpgNetFrame frame) {
        Consumer<GpgNetFrame> handler = handlers.get(frame.command());
        if (handler == null) {
            if (warnedUnknown.add(frame.command())) {
                LOG.warn(
                        "unhandled inbound GPGNet command '{}' (repeats suppressed)",
                        frame.command());
            }
            return;
        }
        try {
            handler.accept(frame);
        } catch (RuntimeException e) {
            LOG.warn(
                    "GPGNet handler for '{}' threw {}: {}",
                    frame.command(),
                    e.getClass().getSimpleName(),
                    e.getMessage());
        }
    }
}
