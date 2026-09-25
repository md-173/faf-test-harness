package com.faforever.testharness.shared.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Names a failure for a single log line, by its type and its root cause, however many layers of
 * wrapping a future or a library added on the way. Written for the mock client's session failures
 * (#445), and shared since its lobby lines need the same (WBS-3.1.1.4-fix, #455), so that every
 * line saying why something failed names it the same way.
 */
public final class Failures {

    /** How deep {@link #describe} follows a cause chain, so one that loops cannot hang a line. */
    private static final int MAX_CAUSE_DEPTH = 16;

    private Failures() {}

    /**
     * Strips the {@link ExecutionException} and {@link CompletionException} wrappers a future adds,
     * so a failure is judged by what actually caused it.
     *
     * @param failure the failure as a future reported it
     * @return the first cause that is not such a wrapper
     */
    public static Throwable unwrap(final Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * Names a failure for a log line: its type, which carries the meaning, then its message when it
     * has one, then the failure at the bottom of its cause chain, when there is one. The type is
     * what keeps a line readable when there is no message: {@code java.net.http} fails a refused
     * connect with a {@code ConnectException} that has none, and a {@code TimeoutException} a timer
     * completes a future with has none either. The root cause is what tells failures of one type
     * apart: {@code ClosedChannelException} for a refused port, {@code UnresolvedAddressException}
     * for an unknown host, the HTTP status for a refused WebSocket upgrade, and for a closed
     * JSON-RPC connection nothing for a clean end of the stream, a {@code SocketException} for a
     * reset, a {@code JsonProcessingException} for a stream that stopped parsing.
     *
     * @param cause the failure to name
     * @return its simple class name and message, and its root cause's when it has one
     */
    public static String describe(final Throwable cause) {
        Throwable root = cause;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH && root.getCause() != null; depth++) {
            root = root.getCause();
        }
        return root == cause ? name(cause) : name(cause) + ", caused by " + name(root);
    }

    private static String name(final Throwable failure) {
        String message =
                failure instanceof JsonProcessingException jackson
                        ? jackson.getOriginalMessage()
                        : failure.getMessage();
        return message == null
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + ": " + message;
    }
}
