package com.faforever.testharness.client;

import com.faforever.testharness.shared.logging.LoggingSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class Main {

    /** Logger for mock-client startup messages. */
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /**
     * Entry point.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        LoggingSetup.configure("MockClient");
        LOG.info("Mock client started");
    }
}
