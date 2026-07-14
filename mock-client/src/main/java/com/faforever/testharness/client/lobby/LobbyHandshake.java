package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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

    /** Bound on the {@code faf-uid} subprocess before it is killed and the static UID is used. */
    private static final int UID_BINARY_TIMEOUT_SECONDS = 15;

    /** Cap on the {@code faf-uid} stderr text quoted in the failure-path warning log. */
    private static final int STDERR_LOG_CAP = 500;

    /** Underlying transport — must already be {@code connect()}ed before {@link #perform}. */
    private final LobbyConnection connection;

    /** Hardware identifier hash sent in the {@code auth} message. Never logged. */
    private final String uniqueId;

    /** Client version string sent in {@code ask_session}; a required argument of that command. */
    private final String clientVersion;

    /** User-agent string sent in {@code ask_session}; a required argument of that command. */
    private final String userAgent;

    /**
     * Optional {@code faf-uid} binary. When present, {@link #resolveUniqueId(long)} runs it with
     * the session to produce the {@code unique_id}; when empty, the static {@link #uniqueId} is
     * sent.
     */
    private final Optional<Path> uidBinaryPath;

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
        this(connection, uniqueId, clientVersion, userAgent, Optional.empty());
    }

    /**
     * Construct a handshake that derives its {@code unique_id} from the {@code faf-uid} binary.
     *
     * @param connection a connected {@link LobbyConnection}
     * @param uniqueId fallback hardware identifier used when {@code uidBinaryPath} is empty or the
     *     binary fails
     * @param clientVersion {@code version} field sent in {@code ask_session}
     * @param userAgent {@code user_agent} field sent in {@code ask_session}
     * @param uidBinaryPath optional path to the {@code faf-uid} binary; when present it is run as
     *     {@code <path> <session>} and its stdout becomes the {@code unique_id}
     */
    public LobbyHandshake(
            final LobbyConnection connection,
            final String uniqueId,
            final String clientVersion,
            final String userAgent,
            final Optional<Path> uidBinaryPath) {
        this.connection = connection;
        this.uniqueId = uniqueId;
        this.clientVersion = clientVersion;
        this.userAgent = userAgent;
        this.uidBinaryPath = uidBinaryPath;
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
        auth.put("unique_id", resolveUniqueId(session));
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

    /**
     * Resolve the {@code unique_id} for the {@code auth} message. When {@link #uidBinaryPath} is
     * present, run {@code <binary> <session>} and use its (trimmed) stdout — the FAF {@code
     * faf-uid} tool's RSA-encrypted blob, which the lobby's policy server requires (a plain
     * placeholder is rejected; lobby-protocol-spec.md §3). On any failure (missing binary, non-zero
     * exit, timeout, empty output) it logs a warning and falls back to the static {@link
     * #uniqueId}. On failure the tool's stderr is logged — there is no UID to leak on that path;
     * the successful blob itself is never logged, only its length.
     *
     * @param session the lobby-issued session number passed to the UID binary
     * @return the resolved unique_id string
     */
    private String resolveUniqueId(final long session) {
        if (uidBinaryPath.isEmpty()) {
            return uniqueId;
        }
        String binary = uidBinaryPath.get().toString();
        try {
            Process process = new ProcessBuilder(binary, Long.toString(session)).start();
            // Bound the process before reading: readAllBytes blocks until stream EOF, so reading
            // first would let a hung-but-silent binary defeat the timeout. The pipe buffers the
            // small UID blob comfortably until the process exits.
            if (!process.waitFor(UID_BINARY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.warn("faf-uid timed out; falling back to configured unique_id");
                return uniqueId;
            }
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                            .strip();
            if (process.exitValue() != 0 || output.isEmpty()) {
                String stderr =
                        new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                                .strip();
                LOG.warn(
                        "faf-uid exited {}: {}; falling back to configured unique_id",
                        process.exitValue(),
                        stderr.isEmpty() ? "<no stderr output>" : truncateForLog(stderr));
                return uniqueId;
            }
            LOG.info("generated unique_id via faf-uid ({} chars)", output.length());
            return output;
        } catch (IOException e) {
            LOG.warn("faf-uid invocation failed ({}); falling back to configured unique_id", e);
            return uniqueId;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("faf-uid interrupted; falling back to configured unique_id");
            return uniqueId;
        }
    }

    /**
     * Cap diagnostic process output to one log-friendly line.
     *
     * @param text raw stderr text
     * @return the text flattened to a single line and capped at {@link #STDERR_LOG_CAP} chars
     */
    private static String truncateForLog(final String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= STDERR_LOG_CAP ? flat : flat.substring(0, STDERR_LOG_CAP) + "…";
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
