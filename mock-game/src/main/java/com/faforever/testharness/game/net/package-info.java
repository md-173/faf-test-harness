/**
 * The mock game's UDP data path to the ICE adapter. {@link
 * com.faforever.testharness.game.net.GameUdpSender} emits simulated peer traffic as {@link
 * com.faforever.testharness.game.net.GameDatagram} payloads over one shared socket bound on the
 * lobby port; the receiver (WBS-3.2.2.6) reads from the same socket next sprint.
 */
package com.faforever.testharness.game.net;
