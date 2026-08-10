/**
 * The mock game's UDP data path to the ICE adapter. {@link
 * com.faforever.testharness.game.net.GameUdpSender} emits simulated peer traffic as {@link
 * com.faforever.testharness.game.net.GameDatagram} payloads over one shared socket bound on the
 * lobby port; {@link com.faforever.testharness.game.net.GameUdpReceiver} (WBS-3.2.2.6) reads peer
 * datagrams back from the same socket and keeps per-sender counts.
 */
package com.faforever.testharness.game.net;
