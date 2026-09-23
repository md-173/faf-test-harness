package com.faforever.testharness.client.state;

import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

class DummyIceAdapterConnection extends IceAdapterConnection {
    /**
     * Concurrent because calls are not always made on the test thread: once the relays are wired
     * into a launch (#218), a lobby frame reaches the adapter from the connection's reader thread,
     * and a test polling a plain HashMap for that call would be reading it unsynchronised.
     */
    private final Map<String, Object[]> received = new ConcurrentHashMap<>();

    /** Concurrent for the same reason as {@link #received}: registration and firing can differ. */
    private final Map<String, List<Consumer<JsonNode>>> notificationHandlers =
            new ConcurrentHashMap<>();

    /**
     * What each rigged call fails with. Concurrent for the same reason as {@link #received}: a test
     * rigs it on its own thread, and the call can arrive on the lobby connection's.
     */
    private final Map<String, Throwable> failCalls = new ConcurrentHashMap<>();

    /** What each rigged call throws from {@code call} itself, instead of failing its future. */
    private final Map<String, RuntimeException> throwCalls = new ConcurrentHashMap<>();

    private final boolean failOnConnection;

    DummyIceAdapterConnection(int port) {
        this(port, false);
    }

    DummyIceAdapterConnection(int port, boolean failOnConnection) {
        super(port);
        this.failOnConnection = failOnConnection;
    }

    @Override
    public CompletableFuture<Void> connect() {
        if (failOnConnection) {
            return CompletableFuture.failedFuture(new IOException("Could not connect"));
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * A {@link #call(final String method, final Object... params)} with {@code method} will result
     * in an exceptional future.
     */
    public void setupCallFail(String method) {
        setupCallFail(method, new IOException("Bad call"));
    }

    /**
     * A {@link #call(final String method, final Object... params)} with {@code method} will fail
     * with {@code failure}, the way the real connection fails it: an {@code IceRpcException} for an
     * error answer, a {@code TimeoutException} for none in time, an {@code IOException} for a
     * closed connection. The lifecycle judges a failed call by which one it was (#445).
     */
    public void setupCallFail(String method, Throwable failure) {
        failCalls.put(method, failure);
    }

    /**
     * A {@link #call(final String method, final Object... params)} with {@code method} will throw
     * {@code defect} rather than return a future at all: the unchecked throw out of a transition
     * action that #439 is about, standing in for a bug anywhere in it.
     */
    public void setupCallThrow(String method, RuntimeException defect) {
        throwCalls.put(method, defect);
    }

    /**
     * If {@link #call(final String method, final Object... params)} was called with the given
     * {@code method}, return the {@code params} given.
     */
    public Object[] receivedMessage(String method) {
        return received.get(method);
    }

    @Override
    public CompletableFuture<JsonNode> call(final String method, final Object... params) {
        received.put(method, params);
        RuntimeException defect = throwCalls.remove(method);
        if (defect != null) {
            throw defect;
        }
        Throwable failure = failCalls.remove(method);
        if (failure != null) {
            return CompletableFuture.failedFuture(failure);
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void registerNotification(final String name, final Consumer<JsonNode> handler) {
        notificationHandlers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(handler);
    }

    /**
     * Delivers {@code value} to every handler registered under {@code name}, standing in for the
     * real connection's reader thread. Handlers are kept in a list because the lifecycle registers
     * more than one under a single notification name.
     */
    public void fireNotification(String name, JsonNode value) {
        for (Consumer<JsonNode> handler : notificationHandlers.getOrDefault(name, List.of())) {
            handler.accept(value);
        }
    }

    @Override
    public void onDisconnect(final Consumer<DisconnectEvent> listener) {}

    @Override
    public void close() {}
}
