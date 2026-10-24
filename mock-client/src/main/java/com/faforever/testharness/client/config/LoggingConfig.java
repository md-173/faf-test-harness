package com.faforever.testharness.client.config;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The logging part of {@link MockClientConfig}.
 *
 * @param level minimum log level
 * @param file optional JSONL log file path
 */
public record LoggingConfig(String level, Optional<Path> file) {}
