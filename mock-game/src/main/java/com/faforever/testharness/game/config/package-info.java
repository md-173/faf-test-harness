/**
 * Mock game launch configuration (WBS-3.2.1.1): {@link
 * com.faforever.testharness.game.config.MockGameCli} strictly parses the argv the Mock Client's
 * launcher passes (subprocess-orchestration-spec.md §2.8) into the immutable {@link
 * com.faforever.testharness.game.config.MockGameConfig} the rest of the game reads. Nothing else
 * reads raw args, environment variables, or config files.
 *
 * <p>Bad input is reported rather than thrown at the caller (WBS-3.2.1.2): {@link
 * com.faforever.testharness.game.config.MockGameCli#parseOrReport(java.lang.String[],
 * java.io.PrintStream)} writes the error and usage text to stderr and returns one of the stable
 * process exit codes in {@link com.faforever.testharness.game.config.ExitCodes}.
 */
package com.faforever.testharness.game.config;
