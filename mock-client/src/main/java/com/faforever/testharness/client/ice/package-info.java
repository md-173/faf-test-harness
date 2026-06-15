/**
 * JSON-RPC transport to the local {@code faf-ice-adapter} subprocess. {@link
 * com.faforever.testharness.client.ice.IceAdapterConnection} owns the loopback TCP socket, frames
 * messages, correlates request/response by id, and dispatches inbound notifications; specific RPC
 * methods live in downstream callers, not here.
 */
package com.faforever.testharness.client.ice;
