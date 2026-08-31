package com.faforever.testharness.client.config;

import java.util.Objects;
import java.util.Optional;

/**
 * Matchmaking-queue settings (lobby-protocol-spec.md §4.3 / §10.2). Kept out of {@link
 * MockClientConfig} so queue settings stay grouped, and so the whole group can be absent when the
 * mock client is configured to host or join a custom game instead — see {@link
 * MockClientConfig#queueConfig()}.
 *
 * <p>Only one queue at a time: the server supports searching several queues simultaneously, but
 * nothing in the harness needs that yet (WBS-3.1.1.9).
 *
 * @param queueName the matchmaker queue to search, e.g. {@code "ladder1v1"}
 * @param faction faction to search with, sent only on {@code game_matchmaking} state {@code
 *     "start"}; empty means the field is omitted from the wire frame
 */
public record GameQueueConfig(String queueName, Optional<Integer> faction) {

    /**
     * Validates that {@code queueName} is present — this record only exists once the operator has
     * opted into queueing, at which point {@code queue_name} is required by the {@code
     * game_matchmaking} request.
     *
     * @throws IllegalArgumentException if {@code queueName} is {@code null} or blank
     */
    public GameQueueConfig {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException(
                    "--queue-name must not be blank when queueing for a matchmaker game");
        }
        Objects.requireNonNull(faction, "faction");
    }
}
