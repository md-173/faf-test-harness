/**
 * JSON-RPC transport to the local {@code faf-ice-adapter} subprocess, plus the components that
 * bridge its notifications onto the lobby WebSocket.
 *
 * <ul>
 *   <li>{@link com.faforever.testharness.client.ice.IceAdapterConnection} — the transport: owns the
 *       loopback TCP socket, frames messages, correlates request/response by id, and dispatches
 *       inbound notifications. It implements no specific RPC method.
 *   <li>{@link com.faforever.testharness.client.ice.IceSignalRelay} — relays ICE candidates both
 *       ways between the adapter ({@code onIceMsg} / {@code iceMsg}) and the lobby's {@code IceMsg}
 *       command (3.1.4.5).
 *   <li>{@link com.faforever.testharness.client.ice.GpgNetForwarder} — forwards the adapter's
 *       {@code onGpgNetMessageReceived} frames to the lobby in the {@code target:"game"} envelope,
 *       outbound only (3.1.4.6).
 * </ul>
 *
 * <p>The remaining adapter RPC methods ({@code setLobbyInitMode}, {@code hostGame}, {@code
 * joinGame}, {@code connectToPeer}, {@code quit}, …) are FSM-driven setup/role/teardown calls
 * issued directly via {@code IceAdapterConnection.call(...)} by the lifecycle wiring (WBS 3.1.3).
 */
package com.faforever.testharness.client.ice;
