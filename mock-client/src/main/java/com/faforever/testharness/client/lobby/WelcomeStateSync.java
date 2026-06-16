package com.faforever.testharness.client.lobby;

import com.faforever.testharness.client.lobby.message.WelcomeMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Welcome → {@link SessionState} state sync (WBS 3.1.1.3). After a successful handshake the lobby
 * sends a {@code welcome} frame carrying the authenticated player's identity; this component
 * decodes it via {@link WelcomeMessage}, hydrates an immutable {@link SessionState}, logs one
 * "session ready" line, and exposes the result through {@link #sessionState()}. No FSM — parse,
 * store, log.
 *
 * <p>It does <em>not</em> register a {@code welcome} handler. {@link LobbyHandshake#perform}
 * already owns that handler (registering a second one on the single-handler {@link LobbyConnection}
 * would replace the handshake's and hang it), and hands the welcome payload back as its completion
 * future. {@link #hydrate} chains off that future instead:
 *
 * <pre>{@code
 * WelcomeStateSync sync = new WelcomeStateSync();
 * sync.hydrate(handshake.perform(tokenSource));
 * // ... later, once the welcome has arrived:
 * sync.sessionState().ifPresent(state -> ...);
 * }</pre>
 *
 * <p>The post-welcome world-state messages ({@code player_info}, {@code game_info}, {@code social},
 * {@code matchmaker_info}) are out of scope here — those land in a follow-on and <em>would</em>
 * each register their own {@link LobbyConnection} handler.
 */
public final class WelcomeStateSync {

    /** SLF4J logger — the "session ready" line carries id/login only, never any credential. */
    private static final Logger LOG = LoggerFactory.getLogger(WelcomeStateSync.class);

    /**
     * Jackson mapper used to convert the welcome {@link JsonNode} into a {@link WelcomeMessage}.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * The hydrated identity, or {@code null} until the welcome future completes. Volatile because
     * the future may complete on a different thread from a reader calling {@link #sessionState()}.
     */
    private volatile SessionState sessionState;

    /**
     * Hydrate the session state by consuming the handshake's {@code welcome} completion future:
     * decode via {@link WelcomeMessage}, distil to {@link SessionState}, store it, and log "session
     * ready". The returned future lets callers chain follow-on work (e.g. the FSM transition that
     * R30 will add) once the identity is known.
     *
     * <p>If {@code welcome} completes exceptionally (handshake failure) or the payload fails {@link
     * WelcomeMessage} validation, the returned future completes exceptionally and no state is
     * stored.
     *
     * @param welcome the welcome payload future returned by {@link LobbyHandshake#perform}
     * @return a future completing with the stored {@link SessionState}
     */
    public CompletableFuture<SessionState> hydrate(final CompletableFuture<JsonNode> welcome) {
        return welcome.thenApply(node -> mapper.convertValue(node, WelcomeMessage.class))
                .thenApply(SessionState::from)
                .thenApply(this::store);
    }

    /**
     * Store the freshly hydrated state and emit the single "session ready" line.
     *
     * @param state the distilled session identity
     * @return {@code state}, so this can sit in a {@code thenApply} chain
     */
    private SessionState store(final SessionState state) {
        this.sessionState = state;
        LOG.info("session ready: id={} login={}", state.id(), state.login());
        return state;
    }

    /**
     * The current session identity, if the welcome has been received. Placeholder accessor for
     * downstream consumers (the FSM in R30; the ICE adapter launcher, which reads {@code id}/{@code
     * login}).
     *
     * @return the hydrated {@link SessionState}, or empty if no welcome has completed yet
     */
    public Optional<SessionState> sessionState() {
        return Optional.ofNullable(sessionState);
    }
}
