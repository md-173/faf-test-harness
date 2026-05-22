package com.faforever.testharness.client.lobby.message;

/**
 * Marker for every typed record that models a client→server lobby frame the Mock Client currently
 * emits. The sealed {@code permits} clause is the authoritative catalog: when a downstream track
 * needs a new outbound command, the new record must be added here.
 *
 * <p>Every implementor must be annotated with {@link LobbyCommand} so the sender knows the wire
 * {@code command} string to splice onto the serialized record. Records are also expected to either
 * use snake_case field names directly or carry Jackson naming/property annotations so the wire
 * format matches the spec.
 *
 * <p>Wire schemas live in {@code documentation/research/lobby-protocol-spec.md} §10.
 */
public sealed interface OutboundMessage permits AskSessionMessage, AuthMessage {}
