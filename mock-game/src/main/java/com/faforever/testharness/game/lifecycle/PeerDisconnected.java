package com.faforever.testharness.game.lifecycle;

import com.faforever.testharness.game.gpgnet.GpgNetFrame;
import com.faforever.testharness.shared.statemachine.Event;

/**
 * A peer has disconnected from the game (WBS-4.3.4).
 *
 * <p>Posted from the {@code DisconnectFromPeer} GPGNet handler. The adapter emits that frame from
 * {@code IceAdapter.onDisconnectFromPeer}, which is reached only when this side's mock client
 * issues the {@code disconnectFromPeer} RPC, which in turn happens only when faf-server tells it a
 * peer left. Source-verified in java-ice-adapter 3.3.14: the adapter sends the frame with no
 * game-state guard of its own, so it can arrive in any state this lifecycle can be in.
 *
 * <p>Carries the frame rather than being no-arg so the departing player id is available to the
 * transition action. Today that id is only logged: the transitions registered for this event go to
 * ENDED from every non-ENDED state, so the <em>first</em> departure ends the game regardless of how
 * many peers remain. That is correct at two peers and wrong above them; WBS-4.3.3 owns deciding
 * whether the game should instead play on until the last peer goes, and the id is carried here so
 * that decision needs no change to this type.
 *
 * @param frame the GpgNet frame that produced this event.
 */
/*package-private*/ record PeerDisconnected(GpgNetFrame frame) implements Event {}
