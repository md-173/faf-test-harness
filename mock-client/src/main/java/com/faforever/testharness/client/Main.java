package com.faforever.testharness.client;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.shared.logging.LoggingSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

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
        } catch (CommandLine.ParameterException e) {
            System.err.println(e.getMessage());
            e.getCommandLine().usage(System.err);
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

    private static void applyLoggingProperties(final MockClientConfig config) {
        System.setProperty(LoggingSetup.LOG_LEVEL_ENV, config.logLevel());
        config.logFile()
                .ifPresent(path -> System.setProperty(LoggingSetup.LOG_FILE_ENV, path.toString()));
    }
}