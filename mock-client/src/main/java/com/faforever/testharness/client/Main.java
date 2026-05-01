package com.faforever.testharness.client;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.ConfigValidationException;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.shared.logging.LoggingSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class Main {

    private static final String COMPONENT_NAME = "MockClient";
    private static final int EXIT_CONFIG_ERROR = 2;

    /**
     * Entry point.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        MockClientConfig config;
        try {
            config = ConfigLoader.load(args);
        } catch (ConfigValidationException e) {
            System.err.println(e.getMessage());
            System.exit(EXIT_CONFIG_ERROR);
            return;
        }

        applyLoggingProperties(config);
        LoggingSetup.configure(COMPONENT_NAME);

        Logger log = LoggerFactory.getLogger(Main.class);
        log.info("Mock client started");
        log.debug(
                "Lobby WS={} ICE RPC={} ICE GPGNet={}",
                config.lobbyWebSocketUrl(),
                config.iceAdapterRpcPort(),
                config.iceAdapterGpgNetPort());
    }

    /**
     * Bridge {@link MockClientConfig} into the system properties that {@code logback.xml}
     * reads via {@code ${LOG_LEVEL}} / {@code ${LOG_FILE}} substitution. This is the only
     * place outside {@link ConfigLoader} that touches system properties, and it only
     * <em>writes</em> them — it never reads configuration from them.
     */
    private static void applyLoggingProperties(final MockClientConfig config) {
        System.setProperty(LoggingSetup.LOG_LEVEL_ENV, config.logLevel());
        config.logFile()
                .ifPresent(path -> System.setProperty(LoggingSetup.LOG_FILE_ENV, path.toString()));
    }
}