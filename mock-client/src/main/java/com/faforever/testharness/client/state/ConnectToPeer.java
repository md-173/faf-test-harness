package com.faforever.testharness.client.state;

import com.faforever.testharness.shared.statemachine.Event;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lobby instructs mock client to open a peer connection to another player already in the game.
 *
 * <p>Source-verified against faf-server's {@code gameconnection.py}: a joiner reaching {@code
 * GameState Lobby} makes the server send {@code JoinGame(host_login, host_id)} to that joiner and
 * {@code ConnectToPeer(joiner_login, joiner_id, offer=True)} to the host ({@code connect_to_host}).
 * For a third or later peer, {@code connect_to_peer} sends the arriving player {@code
 * ConnectToPeer(peer, offer=True)} and each existing peer {@code ConnectToPeer(arrival,
 * offer=False)} — which is why the {@code offer} flag is carried through from the frame rather than
 * assumed, and why this event is accepted while hosting and while joining alike.
 *
 * @param command the ConnectToPeer command received.
 */
/*package-private*/ record ConnectToPeer(JsonNode command) implements Event {}
