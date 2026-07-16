/**
 * GPGNet TCP interface to the faf-ice-adapter (gpgnet-format-spec). {@link
 * com.faforever.testharness.game.gpgnet.GpgNetCodec} is the byte-level {@code frame <-> bytes}
 * codec and {@link com.faforever.testharness.game.gpgnet.GpgNetConnection} the loopback transport
 * (connect / blocking read loop / send / close), both operating on the generic {@link
 * com.faforever.testharness.game.gpgnet.GpgNetFrame} (command + int/string args). Message semantics
 * — inbound routing and outbound frame construction — live in downstream layers, not here.
 */
package com.faforever.testharness.game.gpgnet;
