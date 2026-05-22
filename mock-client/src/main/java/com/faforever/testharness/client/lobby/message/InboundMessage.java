package com.faforever.testharness.client.lobby.message;

/**
 * Marker for every typed record that models a server→client lobby frame the Mock Client currently
 * understands. The sealed {@code permits} clause is the authoritative catalog: when a downstream
 * track (auth, welcome state sync, R24 game launch, matchmaker, …) needs a new inbound command, the
 * new record must be added both here and to {@link
 * com.faforever.testharness.client.lobby.LobbyMessageDispatcher}'s registry.
 *
 * <p>Every implementor must be annotated with {@link LobbyCommand} so the dispatcher can map the
 * wire {@code command} field to the correct record class without reflective constant lookups.
 *
 * <p>Wire schemas live in {@code documentation/research/lobby-protocol-spec.md} §10.
 */
public sealed interface InboundMessage
        permits SessionMessage, WelcomeMessage, AuthenticationFailedMessage, GameLaunchMessage {}
