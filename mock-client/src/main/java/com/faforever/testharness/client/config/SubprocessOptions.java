package com.faforever.testharness.client.config;

import java.nio.file.Path;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * The options of the two subprocesses the client launches, {@code faf-ice-adapter} and {@code
 * mock-game}: their binaries, the adapter's three ports, the game id, and the game's launch delay.
 * Mixed into {@link MockClientCli}'s root command; they build both the {@link IceAdapterConfig} and
 * the {@link MockGameConfig} group of {@link MockClientConfig}.
 *
 * <p>One mixin holds both groups because of the order its fields are declared in, which is the
 * order picocli registers the options in (see {@link MockClientCli}): the two binary paths come
 * before the adapter's ports and the launch delay after them, which two separate mixins could not
 * keep.
 *
 * <p>Each field is named for its option in camel case, because the config-file key is the field
 * name.
 */
final class SubprocessOptions {

    /** Path to the faf-ice-adapter binary; defaults to {@code faf-ice-adapter.jar} in the CWD. */
    @Option(
            names = "--ice-adapter-binary-path",
            scope = ScopeType.INHERIT,
            defaultValue = "faf-ice-adapter.jar",
            description =
                    "Path to the faf-ice-adapter binary (default: ${DEFAULT-VALUE}, resolved "
                            + "against the working directory). A .jar is launched via "
                            + "'java -jar'; any other file is executed directly.")
    private Path iceAdapterBinaryPath;

    /**
     * Path to the {@code mock-game} binary; defaults to the {@code application} plugin's install
     * layout ({@code mock-game/build/install/mock-game/bin/mock-game}) so the harness "just works"
     * when run from the repo root after {@code ./gradlew :mock-game:installDist}.
     */
    @Option(
            names = "--mock-game-binary-path",
            scope = ScopeType.INHERIT,
            defaultValue = "mock-game/build/install/mock-game/bin/mock-game",
            description =
                    "Path to the mock-game binary (default: ${DEFAULT-VALUE}, the Gradle "
                            + "'application' plugin install layout, resolved against the working "
                            + "directory). A .jar is launched via 'java -jar'; any other file is "
                            + "executed directly.")
    private Path mockGameBinaryPath;

    /** Local JSON-RPC port exposed by faf-ice-adapter. */
    @Option(
            names = "--ice-adapter-rpc-port",
            scope = ScopeType.INHERIT,
            defaultValue = "7236",
            description = "Local JSON-RPC port exposed by faf-ice-adapter.")
    private int iceAdapterRpcPort;

    /** Local GPGNet port exposed by faf-ice-adapter. */
    @Option(
            names = "--ice-adapter-gpg-net-port",
            scope = ScopeType.INHERIT,
            defaultValue = "7237",
            description = "Local GPGNet port exposed by faf-ice-adapter.")
    private int iceAdapterGpgNetPort;

    /** Local UDP lobby port passed to faf-ice-adapter as {@code --lobby-port}. */
    @Option(
            names = "--ice-adapter-lobby-port",
            scope = ScopeType.INHERIT,
            defaultValue = "7238",
            description =
                    "Local UDP lobby port the game lobby uses for game traffic; passed to "
                            + "faf-ice-adapter as --lobby-port.")
    private int iceAdapterLobbyPort;

    /** Game ID passed to faf-ice-adapter as {@code --game-id} (required by the adapter). */
    @Option(
            names = "--ice-adapter-game-id",
            scope = ScopeType.INHERIT,
            defaultValue = "0",
            description =
                    "Game ID passed to faf-ice-adapter as --game-id (required by the adapter). "
                            + "Used by the launch-ice / ice-smoke diagnostics; a full 'run' "
                            + "session sets it from the lobby game_launch.uid.")
    private int iceAdapterGameId;

    /**
     * Seconds mock-game waits in the lobby before starting the match on its own; negative disables
     * auto-launch (WBS-4.3.1). This is the single default for the knob: mock-game's own default
     * only ever applies to a hand-run binary, since {@code MockGameLauncher} always emits the flag.
     */
    @Option(
            names = "--mock-game-launch-delay-seconds",
            scope = ScopeType.INHERIT,
            defaultValue = "5",
            description =
                    "Seconds mock-game sits in the lobby before launching the match on its own "
                            + "(default: ${DEFAULT-VALUE}). Use a negative value for a multi-peer "
                            + "session: the lobby server refuses a game_join once the host has "
                            + "launched, so an auto-launching host is unjoinable.")
    private int mockGameLaunchDelaySeconds;

    /**
     * The ICE adapter group from these options.
     *
     * @return the group
     */
    IceAdapterConfig toIceAdapterConfig() {
        return new IceAdapterConfig(
                iceAdapterBinaryPath,
                iceAdapterRpcPort,
                iceAdapterGpgNetPort,
                iceAdapterLobbyPort,
                iceAdapterGameId);
    }

    /**
     * The mock-game group from these options.
     *
     * @return the group
     */
    MockGameConfig toMockGameConfig() {
        return new MockGameConfig(mockGameBinaryPath, mockGameLaunchDelaySeconds);
    }
}
