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

    /** Lowest faction value faf-server's {@code Faction} enum defines ({@code uef}). */
    private static final int MIN_FACTION = 1;

    /** Highest faction value faf-server's {@code Faction} enum defines ({@code nomad}). */
    private static final int MAX_FACTION = 5;

    /**
     * Validates that {@code queueName} is present — this record only exists once the operator has
     * opted into queueing, at which point {@code queue_name} is required by the {@code
     * game_matchmaking} request — and that {@code faction}, when given, is a value the server can
     * actually decode.
     *
     * <p>The faction bound is the server's, not the CLI's advertised range (#304 review). {@code
     * lobbyconnection.command_game_matchmaking} passes the value to {@code Faction.from_value},
     * which raises on anything outside {@code uef=1 … nomad=5}, and an unhandled raise there costs
     * the operator an opaque server-side failure mid-session instead of a usage error at parse
     * time. Bounding to the four factions the CLI documents would instead reject {@code nomad},
     * which the server accepts, so the check tracks the enum.
     *
     * @throws IllegalArgumentException if {@code queueName} is {@code null} or blank, or if {@code
     *     faction} is present and outside 1..5
     */
    public GameQueueConfig {
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException(
                    "--queue-name must not be blank when queueing for a matchmaker game");
        }
        Objects.requireNonNull(faction, "faction");
        if (faction.isPresent() && (faction.get() < MIN_FACTION || faction.get() > MAX_FACTION)) {
            throw new IllegalArgumentException(
                    "--queue-faction must be between "
                            + MIN_FACTION
                            + " and "
                            + MAX_FACTION
                            + " (1=UEF, 2=Aeon, 3=Cybran, 4=Seraphim, 5=Nomad), got "
                            + faction.get());
        }
    }
}
