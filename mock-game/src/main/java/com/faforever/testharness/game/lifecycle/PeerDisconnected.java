package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * A peer has disconnected from the game (WBS-4.3.4).
 *
 * <p>Posted from the {@code DisconnectFromPeer} GPGNet handler. The adapter emits that frame from
 * {@code IceAdapter.onDisconnectFromPeer}, which is reached only when this side's mock client
 * issues the {@code disconnectFromPeer} RPC, which in turn happens only when faf-server tells it a
 * peer left. Source-verified in java-ice-adapter 3.3.14: the adapter sends the frame whatever the
 * match is doing, but holds every frame it sends until this game has reported {@code GameState
 * Lobby} ({@code GPGNetServer.sendToGpgNet}), so it arrives in LOBBY or any later state.
 *
 * <p>Carries the frame rather than being no-arg so the transition action knows who left: in the
 * lobby the game drops that peer and plays on, as FA does (WBS-4.3.6). {@code
 * MockGameLifecycle.registerPeerDepartureTransitions} says what each state does with it.
 *
 * @param frame the GpgNet frame that produced this event.
 */
/*package-private*/ record PeerDisconnected(GpgNetFrame frame) implements Event {}
