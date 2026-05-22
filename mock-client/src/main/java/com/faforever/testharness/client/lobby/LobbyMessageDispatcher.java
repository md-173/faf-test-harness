package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.message.AuthenticationFailedMessage;
import com.faforever.testharness.client.lobby.message.GameLaunchMessage;
import com.faforever.testharness.client.lobby.message.InboundMessage;
import com.faforever.testharness.client.lobby.message.LobbyCommand;
import com.faforever.testharness.client.lobby.message.SessionMessage;
import com.faforever.testharness.client.lobby.message.WelcomeMessage;
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
 * FSM and a debug observer can both subscribe to {@link SessionMessage}. Consumers are invoked in
 * registration order on the WebSocket listener thread, so they must not block.
 *
 * <h2>Malformed / unknown frames</h2>
 *
 * Per the issue's acceptance criteria and spec §1.3-equivalent (the input-validation guidance
 * actually lives in §5 step 3), malformed JSON, missing {@code command}, decode failures, and
 * unhandled commands are logged at WARN and dropped. No exception escapes back to the transport.
 *
 * <h2>Validation scope</h2>
 *
 * Records' canonical constructors perform shape validation (required fields non-null/non-blank); a
 * {@link RuntimeException} from a record constructor is caught here and treated as "malformed
 * payload — drop". Deeper value-level validation (allow-listed mapnames, identifier patterns) is
 * deferred to the downstream consumer per the issue's "no business logic" rule.
 */
public final class LobbyMessageDispatcher {

    /** SLF4J logger for the dispatcher. */
    private static final Logger LOG = LoggerFactory.getLogger(LobbyMessageDispatcher.class);

    /**
     * Authoritative registry of {@code command} string → record class. Update this when a new
     * {@link InboundMessage} record is added. Listed explicitly (rather than reflectively
     * enumerating {@code InboundMessage.getPermittedSubclasses()}) so missing-annotation bugs fail
     * loudly at startup, not at first message.
     */
    private static final Map<String, Class<? extends InboundMessage>> KNOWN_COMMANDS =
            Map.of(
                    commandOf(SessionMessage.class), SessionMessage.class,
                    commandOf(WelcomeMessage.class), WelcomeMessage.class,
                    commandOf(AuthenticationFailedMessage.class), AuthenticationFailedMessage.class,
                    commandOf(GameLaunchMessage.class), GameLaunchMessage.class);

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
     * @param <T> record type
     * @param messageType record class — must be annotated with {@link LobbyCommand} and listed in
     *     {@link #KNOWN_COMMANDS}
     * @param consumer callback invoked once per matching inbound frame
     * @throws IllegalArgumentException if the record class is not registered as a known command
     */
    public <T extends InboundMessage> void register(
            final Class<T> messageType, final Consumer<T> consumer) {
        String command = commandOf(messageType);
        if (!KNOWN_COMMANDS.containsKey(command)) {
            throw new IllegalArgumentException(
                    "record class "
                            + messageType.getSimpleName()
                            + " is not in the dispatcher's known-commands registry; add it to "
                            + "LobbyMessageDispatcher.KNOWN_COMMANDS");
        }
        // Compute-if-absent the per-class consumer list, then install the connection-level handler
        // exactly once. The handler is keyed on the command string at the connection layer, so
        // any second registerHandler call would silently replace the first — guard with a sentinel
        // on the consumer list to skip re-installation.
        List<Consumer<? extends InboundMessage>> existing =
                consumers.computeIfAbsent(messageType, ignored -> new CopyOnWriteArrayList<>());
        boolean firstForThisType = existing.isEmpty();
        existing.add(consumer);
        if (firstForThisType) {
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
        T decoded;
        try {
            decoded = mapper.treeToValue(node, messageType);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            LOG.warn(
                    "dropping malformed '{}' frame: {}",
                    KNOWN_COMMANDS.entrySet().stream()
                            .filter(entry -> entry.getValue().equals(messageType))
                            .findFirst()
                            .map(Map.Entry::getKey)
                            .orElse("?"),
                    e.getOriginalMessage());
            return;
        } catch (IllegalArgumentException e) {
            // Thrown by a record's canonical constructor on null/blank required fields.
            LOG.warn(
                    "dropping '{}' frame failing shape validation: {}",
                    commandOf(messageType),
                    e.getMessage());
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
