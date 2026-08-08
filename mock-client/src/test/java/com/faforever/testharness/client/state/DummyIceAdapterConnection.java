package com.faforever.testharness.client.state;

import com.faforever.testharness.client.ice.IceAdapterConnection;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

class DummyIceAdapterConnection extends IceAdapterConnection {
    private final Map<String, Object[]> received = new HashMap<>();

    private final Map<String, List<Consumer<JsonNode>>> notificationHandlers = new HashMap<>();

    private final Set<String> failCalls = new HashSet<>();

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
        failCalls.add(method);
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
        if (failCalls.remove(method)) {
            return CompletableFuture.failedFuture(new IOException("Bad call"));
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
