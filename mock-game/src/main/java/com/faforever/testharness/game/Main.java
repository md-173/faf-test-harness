package com.faforever.testharness.game;

import com.faforever.testharness.shared.logging.LoggingSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("checkstyle:hideutilityclassconstructor")
public class Main {

    static {
        LoggingSetup.configure("MockGame");
    }

    /** Logger for mock-game startup messages. */
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    /**
     * Entry Point for mock game.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        LOG.info("Mock game started");
    }
}
