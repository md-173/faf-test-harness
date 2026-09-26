package com.faforever.testharness.client.session;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.GameHostConfig;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.lobby.SessionState;
import com.faforever.testharness.client.state.ClientState;
import com.faforever.testharness.game.config.ExitCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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
        assertTrue(
                e.getMessage().startsWith("peer B: could not read OAuth refresh-token file"),
                e.getMessage());
    }

    @Test
    void copiesEveryFieldExceptTheOnesTheSessionOwns() throws Exception {
        MockClientConfig base = distinctBase("--oauth-refresh-token-file=" + token("distinct"));

        assertCopiesEveryOtherField(base);
    }

    @Test
    void copiesTheAccessTokenFileOnTheAccessTokenChannel() throws Exception {
        // A config holds one credential channel or the other, never both, so the access-token
        // component gets its distinct value from a base on that channel.
        Path accessToken = Files.writeString(dir.resolve("access_token.txt"), "access-token");
        MockClientConfig base = distinctBase("--oauth-access-token-file=" + accessToken);
        assertEquals(Optional.of(accessToken), base.oauthAccessTokenFile());
        assertEquals(null, base.oauthRefreshTokenFile());

        assertCopiesEveryOtherField(base);
    }

    /**
     * Copies {@code base} as the host and as a joiner and checks every component the session does
     * not own survives unchanged, and every one it owns holds the session's value. The compiler
     * catches a missing argument in the positional copy, but not two ints or two paths given in the
     * wrong order, which only a base with a distinct value in each component can.
     *
     * @param base a config with a distinct value in every component
     * @throws Exception if a record accessor cannot be invoked
     */
    private static void assertCopiesEveryOtherField(final MockClientConfig base) throws Exception {
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
    void withFaultsReplacesOnlyTheThreeFaultValues() throws Exception {
        MockClientConfig base = distinctBase("--oauth-refresh-token-file=" + token("distinct"));
        Set<String> faults =
                Set.of("iceRelayDelayMs", "mockGameUdpDropPercent", "mockGameCrashAfterSeconds");

        MockClientConfig faulted = MultiPeerSession.withFaults(base, 501, 51, 41);

        for (RecordComponent component : MockClientConfig.class.getRecordComponents()) {
            if (!faults.contains(component.getName())) {
                assertEquals(
                        component.getAccessor().invoke(base),
                        component.getAccessor().invoke(faulted),
                        component.getName());
            }
        }
        assertEquals(501, faulted.iceRelayDelayMs());
        assertEquals(51, faulted.mockGameUdpDropPercent());
        assertEquals(41, faulted.mockGameCrashAfterSeconds());
    }

    @Test
    void aDeliberateCrashIsSetOnTheNamedJoinerOnly() throws IOException {
        List<MockClientConfig> bases =
                List.of(base(token("a")), base(token("b")), base(token("c")));

        List<MockClientConfig> crashing = MultiPeerSession.crashBases(bases, 1);

        assertEquals(
                List.of(-1, MultiPeerSession.crashAfterSeconds(3), -1),
                crashing.stream().map(MockClientConfig::mockGameCrashAfterSeconds).toList());
    }

    @Test
    void refusesADeliberateCrashOnTheHostOrOutsideTheSession() throws IOException {
        List<MockClientConfig> bases =
                List.of(base(token("a")), base(token("b")), base(token("c")));

        IllegalArgumentException host =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> MultiPeerSession.crashBases(bases, 0));
        IllegalArgumentException outside =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> MultiPeerSession.crashBases(bases, 3));

        assertTrue(host.getMessage().contains("on a joiner, B to C; got A"), host.getMessage());
        assertTrue(outside.getMessage().contains("got D"), outside.getMessage());
    }

    @Test
    void refusesADeliberateCrashBesideAnotherCrash() throws IOException {
        List<MockClientConfig> bases =
                List.of(base(token("a")), base(token("b"), "--mock-game-crash-after-seconds=5"));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> MultiPeerSession.crashBases(bases, 1));

        assertTrue(e.getMessage().startsWith("peer B already sets a crash"), e.getMessage());
    }

    @Test
    void aDeliberateCrashLandsAfterLaunchAndLeavesTheSurvivorsTimeBeforeTheMatchEnds() {
        for (int peers = MultiPeerSession.MIN_PEERS; peers <= MultiPeerSession.MAX_PEERS; peers++) {
            long launch = MultiPeerSession.minHostLaunchDelaySeconds(peers);
            long crash = MultiPeerSession.crashAfterSeconds(peers);
            // The crash timer starts at the joiner's join, no earlier than the host started
            // hosting, which is when the host's launch timer started.
            assertTrue(crash >= launch + MultiPeerSession.CRASH_AFTER_LAUNCH.toSeconds());
            // Every joiner is in before launch, so the crash lands at most a launch delay later
            // than that, and the survivor wait must end before the host's match does: mock-game
            // runs it for twice the launch delay after launch.
            long latestCrash = launch + crash;
            long matchEnds = launch + 2 * launch;
            assertTrue(
                    latestCrash + MultiPeerSession.SURVIVOR_TIMEOUT.toSeconds() < matchEnds,
                    "at " + peers + " peers");
        }
    }

    @Test
    void theInjectedCrashExitCodeIsMockGames() {
        assertEquals(ExitCodes.INJECTED_CRASH, MultiPeerSession.INJECTED_CRASH_EXIT);
    }

    @Test
    void aDeliberateCrashSessionIsCheckedLikeAnyOther() throws IOException {
        List<MockClientConfig> bases = List.of(base(token("a")), base(token("b")));

        // Everything passes up to the default adapter path, which does not exist.
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> MultiPeerSession.withDeliberateCrash(bases, "t", 1));

        assertTrue(e.getMessage().startsWith("faf-ice-adapter binary not found"), e.getMessage());
    }

    @Test
    void aLossCountsOnlyWhenReportedAfterTheMarkAndStillStanding() throws IOException {
        SessionPeer host = peer("A", "host", 7982, token("a"));
        SessionPeer crashed = peer("B", "joiner", 330072, token("b"));
        // Bring-up: the adapter reports B down while ICE negotiates, then up.
        host.recordVerdict(verdict(7982, 330072, false));
        host.recordVerdict(verdict(7982, 330072, true));
        host.drainVerdicts("crash");
        int mark = host.observed().size();

        assertFalse(host.reportedLostSince(mark, crashed), "a bring-up false must not count");

        host.recordVerdict(verdict(7982, 330072, false));
        host.drainVerdicts("loss");
        assertTrue(host.reportedLostSince(mark, crashed));
        assertFalse(host.reportsConnected(crashed));

        host.recordVerdict(verdict(7982, 330072, true));
        host.drainVerdicts("loss");
        assertTrue(host.reportsConnected(crashed), "a flap back up is what the latest check sees");
    }

    @Test
    void onlySurvivorsThatOfferedToTheCrashedJoinerMustReportIt() throws IOException {
        SessionPeer host = peer("A", "host", 1, token("a"));
        SessionPeer crashed = peer("B", "joiner", 2, token("b"));
        SessionPeer later = peer("C", "joiner", 3, token("c"));
        SessionPeer earlier = peer("D", "joiner", 4, token("d"));
        host.recordOffer(offer(2, true));
        later.recordOffer(offer(2, true));
        earlier.recordOffer(offer(2, false));

        assertEquals(
                List.of(host, later),
                MultiPeerSession.requiredReporters(List.of(host, later, earlier), crashed));
    }

    @Test
    void aHostThatRecordedNoOfferFailsRatherThanAskingNobody() throws IOException {
        SessionPeer host = peer("A", "host", 1, token("a"));
        SessionPeer crashed = peer("B", "joiner", 2, token("b"));
        SessionPeer later = peer("C", "joiner", 3, token("c"));
        later.recordOffer(offer(2, true));

        CheckpointFailure e =
                assertThrows(
                        CheckpointFailure.class,
                        () -> MultiPeerSession.requiredReporters(List.of(host, later), crashed));

        assertTrue(e.getMessage().startsWith("A(host): loss: recorded no"), e.getMessage());
    }

    @Test
    void theMatchIsLiveOnlyWhenTheHostPlaysAndTheServerSaysSo() {
        assertTrue(MultiPeerSession.matchLive(ClientState.PLAYING, Optional.of("playing")));
        assertFalse(MultiPeerSession.matchLive(ClientState.PLAYING, Optional.of("open")));
        assertFalse(MultiPeerSession.matchLive(ClientState.PLAYING, Optional.empty()));
        assertFalse(MultiPeerSession.matchLive(ClientState.HOSTING, Optional.of("playing")));
    }

    @Test
    void onlyExit134ClassifiedAsACrashIsTheInjectedCrash() {
        assertTrue(MultiPeerSession.injectedCrash(134, true));
        assertFalse(MultiPeerSession.injectedCrash(134, false), "the harness's own doing");
        assertFalse(MultiPeerSession.injectedCrash(70, true), "a crash, but not the injected one");
        assertFalse(MultiPeerSession.injectedCrash(null, true), "not exited");
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
    void acceptsOneAccessTokenFilePerPeer() throws IOException {
        List<MockClientConfig> bases = List.of(accessBase("a"), accessBase("b"));

        // Credentials pass; the next check refuses the default adapter path, which does not exist.
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(e.getMessage().startsWith("faf-ice-adapter binary not found"), e.getMessage());
    }

    @Test
    void refusesTwoPeersOnOneAccessTokenFile() throws IOException {
        MockClientConfig shared = accessBase("a");
        List<MockClientConfig> bases = List.of(shared, shared);

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(e.getMessage().startsWith("peer B: access-token file"), e.getMessage());
        assertTrue(e.getMessage().contains("also peer A's"), e.getMessage());
    }

    @Test
    void refusesAnEmptyAccessTokenFileNamingTheReason() throws IOException {
        Path empty = Files.writeString(dir.resolve("empty.jwt"), "  \n");
        List<MockClientConfig> bases = List.of(accessBase("a"), accessBase(empty));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(
                e.getMessage().startsWith("peer B: OAuth access-token file is empty"),
                e.getMessage());
    }

    @Test
    void refusesTwoAccessTokensForOneAccount() throws IOException {
        Path first = Files.writeString(dir.resolve("first.jwt"), jwt("\"7982\""));
        Path second = Files.writeString(dir.resolve("second.jwt"), jwt("\"7982\""));
        List<MockClientConfig> bases = List.of(accessBase(first), accessBase(second));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(
                e.getMessage()
                        .startsWith(
                                "peer B: access token is for account 7982, the same account as"
                                        + " peer A"),
                e.getMessage());
    }

    @Test
    void acceptsAccessTokensForDifferentAccounts() throws IOException {
        Path first = Files.writeString(dir.resolve("first.jwt"), jwt("\"7982\""));
        Path second = Files.writeString(dir.resolve("second.jwt"), jwt("330072"));
        List<MockClientConfig> bases = List.of(accessBase(first), accessBase(second));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(e.getMessage().startsWith("faf-ice-adapter binary not found"), e.getMessage());
    }

    @Test
    void readsTheAccountOnlyFromANumericSubClaim() {
        assertEquals(Optional.of("7982"), MultiPeerSession.accountOf(jwt("\"7982\"")));
        assertEquals(Optional.of("7982"), MultiPeerSession.accountOf(jwt("7982")));
        assertEquals(Optional.empty(), MultiPeerSession.accountOf(jwt("\"test\"")));
        assertEquals(Optional.empty(), MultiPeerSession.accountOf(jwt("null")));
        assertEquals(Optional.empty(), MultiPeerSession.accountOf("not-a-jwt"));
        assertEquals(Optional.empty(), MultiPeerSession.accountOf("a.!!!.c"));
    }

    @Test
    void refusesABlankRefreshTokenFileBeforeAnyLogin() throws IOException {
        Path blank = Files.writeString(dir.resolve("blank_refresh.txt"), " \n");
        List<MockClientConfig> bases = List.of(base(token("a")), base(blank));

        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class, () -> new MultiPeerSession(bases, "t"));
        assertTrue(
                e.getMessage().startsWith("peer B: OAuth refresh-token file is empty"),
                e.getMessage());
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
     * @param credentialFlag the one credential channel flag, refresh-token or access-token file
     * @return the config
     */
    private MockClientConfig distinctBase(final String credentialFlag) {
        List<String> args =
                List.of(
                        "--lobby-websocket-url=wss://lobby.example.test",
                        "--oauth-token-url=https://token.example.test/oauth2/token",
                        "--oauth-auth-endpoint=https://auth.example.test/oauth2/auth",
                        "--oauth-redirect-uri=http://127.0.0.2",
                        "--oauth-scopes=openid offline lobby extra",
                        "--oauth-client-id=client-id-1",
                        credentialFlag,
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
                        "--mock-game-udp-drop-percent=17",
                        "--mock-game-crash-after-seconds=30009");
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    private Path token(final String name) throws IOException {
        return Files.writeString(dir.resolve("refresh_token_" + name + ".txt"), "token-" + name);
    }

    private MockClientConfig accessBase(final String name) throws IOException {
        return accessBase(token(name));
    }

    /**
     * An unsigned JWT with the given {@code sub} claim: enough for {@link
     * MultiPeerSession#accountOf(String)}, which never checks a signature.
     *
     * @param sub the claim's JSON value, e.g. {@code "7982"} or {@code 7982}
     * @return the token
     */
    private static String jwt(final String sub) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(UTF_8));
        String payload = encoder.encodeToString(("{\"sub\":" + sub + "}").getBytes(UTF_8));
        return header + "." + payload + ".signature";
    }

    /**
     * A base on the access-token channel with nothing else credential-related, which also shows
     * that channel needs no token URL and no client id.
     *
     * @param accessTokenFile the access-token file
     * @return the config
     */
    private MockClientConfig accessBase(final Path accessTokenFile) {
        String[] args = {
            "--lobby-websocket-url=wss://ws.faforever.xyz",
            "--oauth-access-token-file=" + accessTokenFile,
            "--unique-id=00000000-0000-0000-0000-000000000000",
            // A path that cannot exist, so the binary refusal after the credentials is certain.
            "--ice-adapter-binary-path=" + dir.resolve("no-such-adapter.jar")
        };
        return ConfigLoader.load(args, Map.of()).orElseThrow();
    }

    /**
     * A peer with a lobby identity and no connection, enough to feed its recorders directly.
     *
     * @param label the instance label
     * @param role {@code host} or {@code joiner}
     * @param id its player id
     * @param tokenFile its refresh-token file
     * @return the peer
     */
    private static SessionPeer peer(
            final String label, final String role, final int id, final Path tokenFile) {
        SessionPeer peer =
                new SessionPeer(
                        label,
                        role,
                        MultiPeerSession.joinConfig(
                                base(tokenFile),
                                new MultiPeerSession.AdapterPorts(
                                        40000 + id, 41000 + id, 42000 + id),
                                1));
        peer.identity(new SessionState(id, label, "", "", Map.of(), "2026-09-26T00:00:00Z"));
        return peer;
    }

    /**
     * An {@code onConnected} notification as the adapter sends it.
     *
     * @param local the reporting adapter's player id
     * @param remote the peer the verdict is about
     * @param connected the verdict
     * @return the notification
     */
    private static JsonNode verdict(final long local, final long remote, final boolean connected) {
        ObjectNode notification = JSON.createObjectNode().put("method", "onConnected");
        notification.putArray("params").add(local).add(remote).add(connected);
        return notification;
    }

    /**
     * A {@code ConnectToPeer} frame as faf-server sends it: login, id, offer.
     *
     * @param remote the peer to connect to
     * @param offer whether this side makes the ICE offer
     * @return the frame
     */
    private static JsonNode offer(final long remote, final boolean offer) {
        ObjectNode frame = JSON.createObjectNode().put("command", "ConnectToPeer");
        frame.putArray("args").add("login-" + remote).add(remote).add(offer);
        return frame;
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
