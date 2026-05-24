/**
 * Subprocess launchers for the Mock Client's child processes.
 *
 * <p>Each launcher builds a {@link java.lang.ProcessBuilder} for one child binary and starts it via
 * the shared {@code SubprocessManager}, which owns output capture and SIGTERM/SIGKILL teardown. See
 * {@code documentation/research/subprocess-orchestration-spec.md} §5.3 for the launcher pattern.
 */
package com.faforever.testharness.client.process;
