package com.faforever.testharness.game;

import com.faforever.testharness.game.config.ExitCodes;
import com.faforever.testharness.game.config.MockGameCli;
import com.faforever.testharness.game.config.MockGameConfig;
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
     * Entry point for the mock game. Parses and validates the launch arguments first: on a bad
     * argument it writes the error and usage to stderr and exits with {@link ExitCodes#USAGE},
     * without attempting any boot or GPGNet connection. On a valid set it proceeds. The full boot
     * sequence (GPGNet connect, lifecycle FSM) is WBS-3.2.5.1.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        MockGameCli.ParseOutcome outcome = MockGameCli.parseOrReport(args, System.err);
        if (outcome.exitCode() != ExitCodes.OK) {
            System.exit(outcome.exitCode());
        }
        MockGameConfig config = outcome.config();
        LOG.info(
                "mock game started: playerId={} login={} gpgNetPort={} lobbyPort={}",
                config.playerId(),
                config.playerLogin(),
                config.gpgNetPort(),
                config.lobbyPort());
    }
}
