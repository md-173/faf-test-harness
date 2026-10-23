package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.shared.statemachine.Event;

/**
 * The first datagram from a peer has arrived: the mock's nearest equivalent of FA's matchmaker
 * lobby establishing its link to that peer, which it reports with {@code EstablishedPeer}
 * (WBS-3.2.4.1-fix, #506).
 *
 * <p>Posted only in a matchmaker lobby, from the lifecycle's scheduler rather than the receive
 * thread that saw the datagram. {@code MockGameLifecycle.registerPeerEstablishedTransitions} says
 * why, and what each state does with it.
 *
 * @param playerId the peer's player id, as its datagrams carry it.
 */
/*package-private*/ record PeerEstablished(int playerId) implements Event {}
