package com.faforever.testharness.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.GameHostConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The parts of {@link MultiPeerSession} that decide what each peer runs with, checked without a
 * lobby: the copied configs and the refusals before anything starts.
 */
final class MultiPeerSessionTest {

    /**
     * The config components {@link MultiPeerSession} replaces for each peer. Every other component
     * must survive the copy, which is what {@link #copiesEveryFieldExceptTheOnesTheSessionOwns}
     * checks: the compiler catches a missing argument in that positional copy, but not two ints or
     * two paths given in the wrong order, and #357 and #340 each add another component to it.
     */
    private static final Set<String> SESSION_OWNED =
            Set.of(
                    "iceAdapterRpcPort",
                    "iceAdapterGpgNetPort",
                    "iceAdapterLobbyPort",
                    "mockGameLaunchDelaySeconds",
                    "hostConfig",
                    "joinConfig",
                    "queueConfig");

    private static final MultiPeerSession.AdapterPorts PORTS =
            new MultiPeerSession.AdapterPorts(40001, 40002, 40003);

    @TempDir private Path dir;

    @Test
    void hostConfigHostsAFriendsGameWithAutoLaunchOff() throws IOException {
        MockClientConfig base = base(token("a"), "--queue-name=ladder1v1");

        MockClientConfig host = MultiPeerSession.hostConfig(base, PORTS, "title-1");

        GameHostConfig hosted = host.hostConfig().orElseThrow();
        assertEquals("title-1", hosted.title());
        assertEquals("scmp_007", hosted.map());
        assertEquals("faf", hosted.mod());
        assertEquals("friends", hosted.visibility());
        assertTrue(host.joinConfig().isEmpty());
        assertSessionOwnedFields(base, host);
    }

    @Test
    void joinConfigTargetsTheHostsUidWithAutoLaunchOff() throws IOException {
        MockClientConfig base = base(token("b"), "--queue-name=ladder1v1");

        MockClientConfig joiner = MultiPeerSession.joinConfig(base, PORTS, 12345);

        assertEquals(12345, joiner.joinConfig().orElseThrow().targetGameId());
        assertTrue(joiner.joinConfig().orElseThrow().password().isEmpty());
        assertTrue(joiner.hostConfig().isEmpty());
        assertSessionOwnedFields(base, joiner);
    }

    @Test
    void refusesFewerThanTwoPeers() throws IOException {
        List<MockClientConfig> one = List.of(base(token("a")));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new MultiPeerSession(one, "t"));
        assertTrue(e.getMessage().contains("got 1"), e.getMessage());
    }

    @Test
    void refusesMorePeersThanLabels() throws IOException {
        List<MockClientConfig> many =
                new ArrayList<>(
                        Collections.nCopies(MultiPeerSession.MAX_PEERS + 1, base(token("a"))));

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new MultiPeerSession(many, "t"));
        assertTrue(e.getMessage().contains("got 27"), e.getMessage());
    }

    @Test
    void refusesTwoPeersOnOneTokenFile() throws IOException {
        Path shared = token("a");
        List<MockClientConfig> bases = List.of(base(shared), base(shared));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(e.getMessage().startsWith("peer B:"), e.getMessage());
        assertTrue(e.getMessage().contains("also peer A's"), e.getMessage());
    }

    @Test
    void refusesAMissingTokenFileNamingThePeer() throws IOException {
        List<MockClientConfig> bases = List.of(base(token("a")), base(dir.resolve("missing.txt")));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(e.getMessage().startsWith("peer B: cannot read"), e.getMessage());
    }

    @Test
    void copiesEveryFieldExceptTheOnesTheSessionOwns() throws Exception {
        MockClientConfig base = distinctBase();

        MockClientConfig host = MultiPeerSession.hostConfig(base, PORTS, "title-1");
        MockClientConfig joiner = MultiPeerSession.joinConfig(base, PORTS, 12345);

        for (RecordComponent component : MockClientConfig.class.getRecordComponents()) {
            if (SESSION_OWNED.contains(component.getName())) {
                continue;
            }
            Object expected = component.getAccessor().invoke(base);
            assertEquals(expected, component.getAccessor().invoke(host), component.getName());
            assertEquals(expected, component.getAccessor().invoke(joiner), component.getName());
        }
        // The loop above proves nothing about a field it skips, and a positional copy can swap two
        // ints or two paths without the compiler noticing, so the owned fields are pinned by value.
        for (MockClientConfig copy : List.of(host, joiner)) {
            assertEquals(40001, copy.iceAdapterRpcPort());
            assertEquals(40002, copy.iceAdapterGpgNetPort());
            assertEquals(40003, copy.iceAdapterLobbyPort());
            assertEquals(-1, copy.mockGameLaunchDelaySeconds());
            assertTrue(copy.queueConfig().isEmpty());
        }
        assertEquals("title-1", host.hostConfig().orElseThrow().title());
        assertTrue(host.joinConfig().isEmpty());
        assertEquals(12345, joiner.joinConfig().orElseThrow().targetGameId());
        assertTrue(joiner.hostConfig().isEmpty());
    }

    @Test
    void theSessionOwnedFieldNamesStillExist() {
        Set<String> components =
                Arrays.stream(MockClientConfig.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .collect(Collectors.toSet());

        assertTrue(components.containsAll(SESSION_OWNED), components.toString());
    }

    @Test
    void refusesALogLevelThatHidesGameTraffic() throws IOException {
        List<MockClientConfig> bases =
                List.of(base(token("a"), "--log-level=WARN"), base(token("b"), "--log-level=WARN"));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(e.getMessage().startsWith("--log-level must be INFO or finer"), e.getMessage());
    }

    @Test
    void refusesAMissingAdapterBinaryBeforeAnyLogin() throws IOException {
        List<MockClientConfig> bases = List.of(base(token("a")), base(token("b")));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(e.getMessage().startsWith("faf-ice-adapter binary not found"), e.getMessage());
    }

    @Test
    void attributesASurvivorToThePeerWhoseGpgNetPortItCarries() throws IOException {
        List<SessionPeer> peers =
                List.of(
                        new SessionPeer(
                                "A",
                                "host",
                                MultiPeerSession.hostConfig(base(token("a")), PORTS, "t")),
                        new SessionPeer(
                                "B",
                                "joiner",
                                MultiPeerSession.joinConfig(
                                        base(token("b")),
                                        new MultiPeerSession.AdapterPorts(40011, 40012, 40013),
                                        1)));

        assertEquals(
                Optional.of("B(joiner)"),
                MultiPeerSession.ownerOf(
                        "java -jar mock-game.jar --gpgnet-port 40012 --id 2", peers));
        assertEquals(
                Optional.of("A(host)"),
                MultiPeerSession.ownerOf(
                        "java -jar faf-ice-adapter.jar --gpgnet-port 40002", peers));
        assertEquals(
                Optional.empty(),
                MultiPeerSession.ownerOf("java -jar mock-game.jar --gpgnet-port 400021", peers));
        assertEquals(Optional.empty(), MultiPeerSession.ownerOf("java -jar other.jar", peers));
    }

    /**
     * The session replaces the ports, the launch delay and the queue, and keeps the account.
     *
     * @param base the base config
     * @param copy the session's copy of it
     */
    private static void assertSessionOwnedFields(
            final MockClientConfig base, final MockClientConfig copy) {
        assertEquals(40001, copy.iceAdapterRpcPort());
        assertEquals(40002, copy.iceAdapterGpgNetPort());
        assertEquals(40003, copy.iceAdapterLobbyPort());
        assertEquals(-1, copy.mockGameLaunchDelaySeconds());
        assertTrue(copy.queueConfig().isEmpty());
        assertEquals(base.oauthRefreshTokenFile(), copy.oauthRefreshTokenFile());
        assertEquals(base.lobbyWebSocketUrl(), copy.lobbyWebSocketUrl());
    }

    /**
     * A base config whose every copied component holds a value of its own, so a copy that moves a
     * value to the wrong component cannot still compare equal.
     *
     * @return the config
     * @throws IOException if the token file cannot be written
     */
    private MockClientConfig distinctBase() throws IOException {
        List<String> args =
                List.of(
                        "--lobby-websocket-url=wss://lobby.example.test",
                        "--oauth-token-url=https://token.example.test/oauth2/token",
                        "--oauth-auth-endpoint=https://auth.example.test/oauth2/auth",
                        "--oauth-redirect-uri=http://127.0.0.2",
                        "--oauth-scopes=openid offline lobby extra",
                        "--oauth-client-id=client-id-1",
                        "--oauth-refresh-token-file=" + token("distinct"),
                        "--unique-id=11111111-1111-1111-1111-111111111111",
                        "--client-version=1.2.3-distinct",
                        "--user-agent=agent-1",
                        "--uid-binary-path=" + dir.resolve("uid-binary"),
                        "--ice-adapter-binary-path=" + dir.resolve("adapter-binary"),
                        "--mock-game-binary-path=" + dir.resolve("game-binary"),
                        "--ice-adapter-rpc-port=30001",
                        "--ice-adapter-gpg-net-port=30002",
                        "--ice-adapter-lobby-port=30003",
                        "--ice-adapter-game-id=30004",
                        "--mock-game-launch-delay-seconds=30005",
                        "--log-level=DEBUG",
                        "--log-file=" + dir.resolve("log-file.jsonl"),
                        "--player-id-override=30006",
                        "--player-login=login-1",
                        "--queue-name=ladder1v1",
                        "--queue-faction=2",
                        "--ice-relay-delay-ms=30007",
                        "--mock-game-udp-drop-percent=17");
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    private Path token(final String name) throws IOException {
        return Files.writeString(dir.resolve("refresh_token_" + name + ".txt"), "token-" + name);
    }

    private static MockClientConfig base(final Path tokenFile, final String... extra) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "--lobby-websocket-url=wss://ws.faforever.xyz",
                                "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                                "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                                "--oauth-redirect-uri=http://127.0.0.1",
                                "--oauth-scopes=openid offline lobby",
                                "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                                "--oauth-refresh-token-file=" + tokenFile,
                                "--unique-id=00000000-0000-0000-0000-000000000000"));
        args.addAll(List.of(extra));
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }
}
