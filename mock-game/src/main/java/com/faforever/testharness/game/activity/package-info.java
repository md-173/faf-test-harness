/**
 * Simulated game activity. {@link com.faforever.testharness.game.activity.GameTicker} is the
 * deterministic tick source consumers attach behaviour to; the UDP traffic sender (R48,
 * WBS-3.2.2.5) emits packets per tick. Nothing in this package sends protocol frames — the FAF
 * sources impose no liveness requirement on the game (no GPGNet heartbeat, no adapter-side
 * timeout), so this is cadence for generated traffic, not keep-alive.
 */
package com.faforever.testharness.game.activity;
