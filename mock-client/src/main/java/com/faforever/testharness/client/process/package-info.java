/**
 * Subprocess launchers for the Mock Client's child processes, and the coordinated session teardown.
 *
 * <p>Each launcher builds a {@link java.lang.ProcessBuilder} for one child binary and starts it via
 * the shared {@code SubprocessManager}, which owns output capture and SIGTERM/SIGKILL teardown. See
 * {@code documentation/research/subprocess-orchestration-spec.md} §5.3 for the launcher pattern.
 *
 * <p>{@link com.faforever.testharness.client.process.SessionTeardown} (WBS-3.1.3.2) is the other
 * end of the lifecycle: one idempotent, bounded sequence that terminates both children and closes
 * the lobby + adapter connections, shared by the signal hook and the FSM's TERMINATED action.
 */
package com.faforever.testharness.client.process;
