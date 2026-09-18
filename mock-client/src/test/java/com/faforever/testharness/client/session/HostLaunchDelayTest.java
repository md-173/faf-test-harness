package com.faforever.testharness.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The opt-in host launch delay and the {@code game_info} recording added for WBS-4.3.4, unit-tested
 * without standing a live session up.
 *
 * <p>Both exist only so a departure test can reach a post-launch session and know when faf-server
 * agrees it is post-launch, so what is pinned here is the part that would fail silently: that the
 * floor actually rejects a delay too short to keep the game joinable, that a joiner never inherits
 * the host's delay, and that the frame parsing ignores everything that is not this peer's own game.
 */
final class HostLaunchDelayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Ports the config copies under test are given; nothing binds them here. */
    private static final MultiPeerSession.AdapterPorts PORTS =
            new MultiPeerSession.AdapterPorts(1, 2, 3);

    @Test
    void theDefaultConstructorStillDisablesAutoLaunch(@TempDir Path dir) throws IOException {
        MockClientConfig base = base(token(dir, "a"));

        MockClientConfig host = MultiPeerSession.hostConfig(base, PORTS, "title");

        assertEquals(
                MultiPeerSession.LAUNCH_DISABLED,
                host.mockGameLaunchDelaySeconds(),
                "the two-argument path must keep WBS-4.3.1's auto-launch-off default");
    }

    @Test
    void aHostCanBeGivenADelayAndAJoinerNeverInheritsIt(@TempDir Path dir) throws IOException {
        MockClientConfig base = base(token(dir, "a"));

        MockClientConfig host = MultiPeerSession.hostConfig(base, PORTS, "title", 120);
        MockClientConfig joiner = MultiPeerSession.joinConfig(base, PORTS, 4242);

        assertEquals(120, host.mockGameLaunchDelaySeconds());
        assertEquals(
                MultiPeerSession.LAUNCH_DISABLED,
                joiner.mockGameLaunchDelaySeconds(),
                "a joiner that auto-launched would end its own session on a timer the caller"
                        + " never chose");
    }

    @Test
    void aDelayBelowTheFloorIsRejected(@TempDir Path dir) throws IOException {
        List<MockClientConfig> bases = List.of(base(token(dir, "a")), base(token(dir, "b")));
        long floor = MultiPeerSession.minHostLaunchDelaySeconds(bases.size());

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new MultiPeerSession(bases, "title", (int) floor - 1));

        assertTrue(
                thrown.getMessage().contains("joinable"),
                "the message must say why the floor exists: " + thrown.getMessage());
    }

    @Test
    void disablingAutoLaunchIsAlwaysAccepted(@TempDir Path dir) throws IOException {
        List<MockClientConfig> bases = List.of(base(token(dir, "a")), base(token(dir, "b")));

        // Not an assertion on the object, just that the floor does not reject the sentinel: -1 is
        // below every floor and must stay the way callers ask for the default.
        try (MultiPeerSession session =
                new MultiPeerSession(bases, "title", MultiPeerSession.LAUNCH_DISABLED)) {
            assertEquals(List.of(), session.peers(), "nothing runs until run() is called");
        }
    }

    @Test
    void theFloorGrowsWithThePeerCount() {
        long two = MultiPeerSession.minHostLaunchDelaySeconds(2);
        long four = MultiPeerSession.minHostLaunchDelaySeconds(4);

        assertEquals(MultiPeerSession.MIN_HOST_LAUNCH_DELAY.toSeconds(), two);
        assertTrue(
                four > two,
                "joiners start serially, so the host must stay joinable longer at four peers");
    }

    @Test
    void gameInfoIsRecordedOnlyForThisPeersOwnGame() {
        assertEquals(Optional.of("playing"), SessionPeer.stateOfOwnGame(game(7, "playing"), 7));
        assertEquals(
                Optional.empty(),
                SessionPeer.stateOfOwnGame(game(8, "open"), 7),
                "another player's game must not be recorded");
        assertEquals(
                Optional.empty(),
                SessionPeer.stateOfOwnGame(game(7, "playing"), -1),
                "nothing is ours until game_launch names a uid");
    }

    @Test
    void malformedGameDictsAreIgnored() {
        ObjectNode noUid = MAPPER.createObjectNode().put("state", "playing");
        ObjectNode textualUid = MAPPER.createObjectNode().put("uid", "7").put("state", "playing");
        ObjectNode noState = MAPPER.createObjectNode().put("uid", 7);

        assertEquals(Optional.empty(), SessionPeer.stateOfOwnGame(noUid, 7));
        assertEquals(Optional.empty(), SessionPeer.stateOfOwnGame(textualUid, 7));
        assertEquals(Optional.empty(), SessionPeer.stateOfOwnGame(noState, 7));
    }

    /** One game dict in the shape {@code Game.to_dict} produces. */
    private static ObjectNode game(final int uid, final String state) {
        return MAPPER.createObjectNode().put("uid", uid).put("state", state);
    }

    /**
     * A readable refresh-token file, which the session constructor requires of every peer.
     *
     * @param dir the per-test temporary directory
     * @param name distinguishes each peer's file, since the session refuses two peers sharing one
     * @return the file's path
     * @throws IOException if it cannot be written
     */
    private static Path token(final Path dir, final String name) throws IOException {
        Path file = dir.resolve("refresh_" + name + ".txt");
        Files.writeString(file, "refresh-token-" + name);
        return file;
    }

    /**
     * A validated config carrying that token and the binaries the session checks for.
     *
     * @param refreshTokenFile the peer's account
     * @return the config
     */
    private static MockClientConfig base(final Path refreshTokenFile) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "--lobby-websocket-url=wss://ws.faforever.xyz",
                                "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                                "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                                "--oauth-redirect-uri=http://127.0.0.1",
                                "--oauth-scopes=openid offline lobby",
                                "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                                "--unique-id=00000000-0000-0000-0000-000000000000",
                                "--oauth-refresh-token-file=" + refreshTokenFile.toAbsolutePath(),
                                "--ice-adapter-binary-path=" + existingFile(),
                                "--mock-game-binary-path=" + existingFile(),
                                "--uid-binary-path=" + existingFile()));
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    /**
     * Any file that certainly exists, standing in for the three binaries the session only checks
     * the presence of.
     *
     * @return an absolute path to this JVM's own executable
     */
    private static String existingFile() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().toString();
    }
}
