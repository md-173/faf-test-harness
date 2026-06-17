package com.faforever.testharness.client.ice;

/**
 * Raised when the ICE adapter returns a JSON-RPC {@code error} object for a {@link
 * IceAdapterConnection#call} request. Carries the JSON-RPC {@code error.code} so callers can
 * distinguish protocol errors (e.g. {@code -32601 Method Not Found}) from timeouts/disconnects,
 * which surface as other exception types on the same future.
 */
public final class IceRpcException extends RuntimeException {

    /** The JSON-RPC {@code error.code}. */
    private final int code;

    /**
     * @param code the JSON-RPC {@code error.code}
     * @param message the JSON-RPC {@code error.message}
     */
    public IceRpcException(final int code, final String message) {
        super("JSON-RPC error " + code + ": " + message);
        this.code = code;
    }

    /**
     * @return the JSON-RPC {@code error.code}
     */
    public int code() {
        return code;
    }
}
