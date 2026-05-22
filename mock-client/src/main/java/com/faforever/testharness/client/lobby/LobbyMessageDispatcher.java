package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.message.InboundMessage;
import com.faforever.testharness.client.lobby.message.LobbyCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed-message routing layer that sits on top of the raw-frame {@link LobbyConnection} from
 * 3.1.1.1. Consumers register a {@link Consumer} against an {@link InboundMessage} record class;
 * when a JSON frame arrives whose {@code command} field is bound to that class (via the {@link
 * LobbyCommand} annotation), the dispatcher decodes the frame into the record and fans out to every
 * registered consumer.
 *
 * <h2>Composability</h2>
 *
 * Multiple consumers can register independently for the same record class — for example, the auth
 * FSM and a debug observer can both subscribe to {@link
 * com.faforever.testharness.client.lobby.message.SessionMessage}. Consumers are invoked in
 * registration order on the WebSocket listener thread, so they must not block.
 *
 * <h2>Malformed / unknown frames</h2>
 *
 * Per the issue's acceptance criteria, malformed JSON, missing {@code command}, decode failures,
 * and unhandled commands are logged at WARN and dropped. No exception escapes back to the
 * transport.
 *
 * <h2>Validation scope</h2>
 *
 * The dispatcher's only validation is structural. Each inbound record's canonical constructor
 * presence-checks its required fields (required primitives are boxed so an omitted field decodes to
 * {@code null} rather than silently to {@code 0}); Jackson wraps the resulting {@link
 * IllegalArgumentException} in a {@link
 * com.fasterxml.jackson.databind.exc.ValueInstantiationException}, which {@link #dispatch} catches
 * and drops at WARN alongside plain malformed-JSON failures. Deeper value-level validation
 * (allow-listed mapnames, identifier patterns) is the downstream consumer's job per the issue's "no
 * business logic" rule — see spec §5 step 3 and {@link
 * com.faforever.testharness.client.lobby.message.GameLaunchMessage}'s javadoc.
 */
public final class LobbyMessageDispatcher {

    /** SLF4J logger for the dispatcher. */
    private static final Logger LOG = LoggerFactory.getLogger(LobbyMessageDispatcher.class);

    static {
        // Fail loudly at class-load if any inbound record forgot its @LobbyCommand annotation,
        // rather than discovering it on the first matching frame. commandOf throws for a missing
        // annotation; iterating the sealed permits clause keeps this in lockstep with the catalog
        // without a hand-maintained parallel table.
        for (Class<?> permitted : InboundMessage.class.getPermittedSubclasses()) {
            commandOf(permitted);
        }
    }

    /** Raw-frame transport this dispatcher sits on top of. */
    private final LobbyConnection connection;

    /** Jackson mapper for {@code JsonNode → record} decode. */
    private final ObjectMapper mapper;

    /**
     * Consumers registered per record class. {@link CopyOnWriteArrayList} so iteration on the
     * dispatcher thread is lock-free and registration during dispatch (rare but legal) is safe.
     */
    private final Map<Class<? extends InboundMessage>, List<Consumer<? extends InboundMessage>>>
            consumers = new ConcurrentHashMap<>();

    /**
     * Construct a dispatcher bound to an already-constructed (but not necessarily connected) {@link
     * LobbyConnection}. The dispatcher installs one underlying {@link LobbyMessageHandler} per
     * command lazily, when the first consumer for that command registers.
     *
     * @param connection raw-frame transport from 3.1.1.1
     * @param mapper Jackson mapper used to decode JSON frames into records
     */
    public LobbyMessageDispatcher(final LobbyConnection connection, final ObjectMapper mapper) {
        this.connection = connection;
        this.mapper = mapper;
    }

    /**
     * Convenience constructor that builds a default {@link ObjectMapper}.
     *
     * @param connection raw-frame transport from 3.1.1.1
     */
    public LobbyMessageDispatcher(final LobbyConnection connection) {
        this(connection, new ObjectMapper());
    }

    /**
     * Register a typed consumer for inbound messages of the given record class. Multiple
     * registrations against the same class fan out in registration order.
     *
     * <p>Synchronised so the "first registration installs the connection-level handler" guard is
     * atomic against a concurrent {@code register} for the same class — registration is a
     * startup-time activity, so the lock is uncontended in practice.
     *
     * @param <T> record type
     * @param messageType record class — must be annotated with {@link LobbyCommand} (guaranteed for
     *     every {@link InboundMessage} by the class-load check above)
     * @param consumer callback invoked once per matching inbound frame
     */
    public synchronized <T extends InboundMessage> void register(
            final Class<T> messageType, final Consumer<T> consumer) {
        String command = commandOf(messageType);
        List<Consumer<? extends InboundMessage>> existing =
                consumers.computeIfAbsent(messageType, ignored -> new CopyOnWriteArrayList<>());
        boolean firstForThisType = existing.isEmpty();
        existing.add(consumer);
        if (firstForThisType) {
            // First consumer for this command — install the single connection-level handler that
            // decodes and fans out. The connection keys handlers by command string, so installing
            // it more than once would just replace an identical lambda; the guard avoids that.
            connection.registerHandler(command, node -> dispatch(messageType, node));
        }
    }

    /**
     * Decode a JSON node into a typed record and fan out to every consumer. Errors are swallowed
     * with a WARN log — the protocol layer never lets a bad frame tear down the listener.
     *
     * @param <T> record type
     * @param messageType record class to decode into
     * @param node raw frame as received from {@link LobbyConnection}
     */
    private <T extends InboundMessage> void dispatch(
            final Class<T> messageType, final JsonNode node) {
        String command = commandOf(messageType);
        T decoded;
        try {
            decoded = mapper.treeToValue(node, messageType);
        } catch (com.fasterxml.jackson.databind.exc.ValueInstantiationException e) {
            // Thrown specifically when a record's canonical constructor rejects the decoded
            // values — i.e. a missing required field. Distinct from a plain type mismatch below.
            Throwable cause = e.getCause();
            LOG.warn(
                    "dropping '{}' frame failing shape validation: {}",
                    command,
                    cause != null ? cause.getMessage() : e.getOriginalMessage());
            return;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Wrong JSON shape for the target type (e.g. a string where a number is expected).
            LOG.warn("dropping malformed '{}' frame: {}", command, e.getOriginalMessage());
            return;
        }
        List<Consumer<? extends InboundMessage>> handlers = consumers.get(messageType);
        if (handlers == null) {
            return;
        }
        for (Consumer<? extends InboundMessage> raw : handlers) {
            @SuppressWarnings("unchecked")
            Consumer<T> typed = (Consumer<T>) raw;
            try {
                typed.accept(decoded);
            } catch (RuntimeException e) {
                LOG.warn(
                        "consumer for {} threw {}: {}",
                        messageType.getSimpleName(),
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
    }

    /**
     * Read the wire command string from a record class's {@link LobbyCommand} annotation.
     *
     * @param messageType record class
     * @return value of the {@code @LobbyCommand} annotation
     * @throws IllegalStateException if the class is not annotated (defensive — every inbound record
     *     is required to carry it)
     */
    static String commandOf(final Class<?> messageType) {
        LobbyCommand annotation = messageType.getAnnotation(LobbyCommand.class);
        if (annotation == null) {
            throw new IllegalStateException(
                    messageType.getSimpleName()
                            + " is missing the @LobbyCommand annotation — add one with the wire "
                            + "command string");
        }
        return annotation.value();
    }
}
