package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the four-step lobby authentication handshake over a connected {@link LobbyConnection}:
 * {@code ask_session → session → auth → welcome | authentication_failed} (see {@code
 * documentation/research/lobby-protocol-spec.md} §3).
 *
 * <p>One handshake per {@link LobbyHandshake} instance. The {@link #perform(TokenSource)} future
 * completes with the {@code welcome} payload on success, or completes exceptionally with {@link
 * AuthenticationException} on either an {@code authentication_failed} frame or any failure
 * obtaining the access token. The caller is responsible for chaining a timeout / disconnect
 * listener if it needs to bound the handshake.
 *
 * <p>No log line emitted by this class contains the JWT access token. The success log records the
 * server-supplied login; the failure log records the server-supplied {@code text} field, neither of
 * which is a credential.
 */
public final class LobbyHandshake {

    /** SLF4J logger — never carries credentials; see class-level note. */
    private static final Logger LOG = LoggerFactory.getLogger(LobbyHandshake.class);

    /** Underlying transport — must already be {@code connect()}ed before {@link #perform}. */
    private final LobbyConnection connection;

    /** Hardware identifier hash sent in the {@code auth} message. Never logged. */
    private final String uniqueId;

    /** Client version string sent in {@code ask_session}; a required argument of that command. */
    private final String clientVersion;

    /** User-agent string sent in {@code ask_session}; a required argument of that command. */
    private final String userAgent;

    /** Jackson mapper for building outgoing frames. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Result of the handshake; completed by the session / welcome / authentication_failed paths.
     */
    private final CompletableFuture<JsonNode> result = new CompletableFuture<>();

    /** Latch ensuring {@link #perform} is invoked at most once. */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Construct a handshake bound to {@code connection}. The connection's existing handlers for
     * {@code session}, {@code welcome}, and {@code authentication_failed} will be replaced when
     * {@link #perform} is called.
     *
     * @param connection a connected {@link LobbyConnection}
     * @param uniqueId hardware identifier hash sent in the {@code auth} payload
     * @param clientVersion {@code version} field sent in {@code ask_session}
     * @param userAgent {@code user_agent} field sent in {@code ask_session}
     */
    public LobbyHandshake(
            final LobbyConnection connection,
            final String uniqueId,
            final String clientVersion,
            final String userAgent) {
        this.connection = connection;
        this.uniqueId = uniqueId;
        this.clientVersion = clientVersion;
        this.userAgent = userAgent;
    }

    /**
     * Run the handshake. Pulls a token from {@code tokens}, then exchanges {@code ask_session →
     * session → auth → welcome}.
     *
     * @param tokens source of the JWT access token
     * @return future that completes with the {@code welcome} message on success, or exceptionally
     *     with {@link AuthenticationException}
     * @throws IllegalStateException if {@link #perform} has already been called on this instance
     */
    public CompletableFuture<JsonNode> perform(final TokenSource tokens) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("LobbyHandshake.perform() may only be called once");
        }
        connection.registerHandler("welcome", this::onWelcome);
        connection.registerHandler("authentication_failed", this::onAuthenticationFailed);
        tokens.obtain()
                .whenComplete(
                        (token, err) -> {
                            if (err != null) {
                                Throwable cause =
                                        err instanceof java.util.concurrent.CompletionException
                                                ? err.getCause()
                                                : err;
                                LOG.warn(
                                        "lobby auth aborted: token acquisition failed: {}",
                                        cause.getMessage());
                                result.completeExceptionally(
                                        cause instanceof AuthenticationException
                                                ? cause
                                                : new AuthenticationException(
                                                        "token acquisition failed", cause));
                                return;
                            }
                            sendAskSessionThenAuth(token);
                        });
        return result;
    }

    private void sendAskSessionThenAuth(final AccessToken token) {
        connection.registerHandler(
                "session",
                msg -> {
                    if (result.isDone()) {
                        return;
                    }
                    final long session;
                    try {
                        session = JsonRequire.longField(msg, "session");
                    } catch (IllegalArgumentException e) {
                        result.completeExceptionally(
                                new AuthenticationException(
                                        "malformed session message from lobby: " + e.getMessage(),
                                        e));
                        return;
                    }
                    sendAuth(token, session);
                });
        ObjectNode ask = mapper.createObjectNode();
        ask.put("command", "ask_session");
        ask.put("version", clientVersion);
        ask.put("user_agent", userAgent);
        connection
                .send(ask)
                .exceptionally(
                        err -> {
                            result.completeExceptionally(
                                    new AuthenticationException("failed to send ask_session", err));
                            return null;
                        });
    }

    private void sendAuth(final AccessToken token, final long session) {
        ObjectNode auth = mapper.createObjectNode();
        auth.put("command", "auth");
        auth.put("token", token.token());
        auth.put("unique_id", uniqueId);
        auth.put("session", session);
        connection
                .send(auth)
                .exceptionally(
                        err -> {
                            result.completeExceptionally(
                                    new AuthenticationException("failed to send auth", err));
                            return null;
                        });
    }

    private void onWelcome(final JsonNode msg) {
        if (result.isDone()) {
            return;
        }
        String login = msg.has("login") ? msg.get("login").asText() : "<unknown>";
        LOG.info("lobby authenticated as login={}", login);
        result.complete(msg);
    }

    private void onAuthenticationFailed(final JsonNode msg) {
        if (result.isDone()) {
            return;
        }
        String text = msg.has("text") ? msg.get("text").asText() : "<no text>";
        LOG.error("lobby authentication_failed: {}", text);
        result.completeExceptionally(new AuthenticationException(text));
    }
}
