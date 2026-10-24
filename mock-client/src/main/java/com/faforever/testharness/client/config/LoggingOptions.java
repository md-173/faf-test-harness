package com.faforever.testharness.client.config;

import java.nio.file.Path;
import java.util.Optional;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * The logging options of {@link MockClientCli}, mixed into the root command. They build the {@link
 * LoggingConfig} group of {@link MockClientConfig}.
 *
 * <p>Each field is named for its option in camel case, because the config-file key is the field
 * name.
 */
final class LoggingOptions {

    /** Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR). */
    @Option(
            names = "--log-level",
            scope = ScopeType.INHERIT,
            defaultValue = "INFO",
            description = "Minimum log level (TRACE, DEBUG, INFO, WARN, ERROR).")
    private String logLevel;

    /** Optional JSONL log file path. */
    @Option(
            names = "--log-file",
            scope = ScopeType.INHERIT,
            description = "Optional JSONL log file path.")
    private Path logFile;

    /**
     * The logging group from these options.
     *
     * @return the group; its level is {@code null} only if no layer and no default supplied one
     */
    LoggingConfig toConfig() {
        return new LoggingConfig(logLevel, Optional.ofNullable(logFile));
    }
}
