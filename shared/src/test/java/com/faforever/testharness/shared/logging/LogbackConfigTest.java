package com.faforever.testharness.shared.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.Status;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shipped {@code logback.xml} loads without a warning or an error (WBS-2.3.6-fix, #524).
 *
 * <p>Its {@code NopStatusListener} keeps Logback's startup status off the console, errors included,
 * so a broken or deprecated element would ship unnoticed, as {@code converterClass} did until #521.
 * Logback still records every status, so loading the file into a throwaway context and reading them
 * back catches that at build time instead.
 */
final class LogbackConfigTest {

    @TempDir private Path tempDir;

    @Test
    void theShippedConfigLoadsWithoutWarningsOrErrors() throws JoranException {
        LoggerContext context = new LoggerContext();
        // Context properties win over system properties and the environment in ${...}, so the
        // file appender opens a file here, at a fixed level, whatever the developer exported.
        context.putProperty(LoggingSetup.LOG_FILE_ENV, tempDir.resolve("config.jsonl").toString());
        context.putProperty(LoggingSetup.LOG_LEVEL_ENV, "INFO");
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(LoggingSetup.class.getResource("/logback.xml"));

            List<String> problems =
                    context.getStatusManager().getCopyOfStatusList().stream()
                            .filter(status -> status.getEffectiveLevel() > Status.INFO)
                            .map(Status::toString)
                            .toList();
            assertEquals(List.of(), problems, "logback.xml loads with warnings or errors");
        } finally {
            context.stop();
        }
    }
}
