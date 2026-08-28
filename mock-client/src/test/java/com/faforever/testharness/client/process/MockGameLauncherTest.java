package com.faforever.testharness.client.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.shared.logging.LoggingSetup;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link MockGameLauncher}. The real {@code mock-game} binary is not built in CI, so
 * a stub shell script stands in for it (WBS-3.1.2.3 deliverables). Argument-list construction is
 * verified directly; spawn / capture / terminate are exercised against the stub.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class MockGameLauncherTest {

    private static final int AWAIT_SECONDS = 10;
    private static final long POLL_BUDGET_MS = 5_000;
    private static final long POLL_INTERVAL_MS = 50;

    @TempDir private Path tempDir;

    private ListAppender<ILoggingEvent> appender;
    private Logger root;

    @BeforeEach
    void attachAppender() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        // Subprocess reader threads can append while the test thread polls captured events.
        appender.list = new CopyOnWriteArrayList<>();
        appender.setContext(ctx);
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (appender != null) {
            appender.stop();
            root.detachAppender(appender);
        }
    }

    @Test
    void missingBinaryThrowsClearSingleLineException() {
        Path missing = tempDir.resolve("does-not-exist");
        MockGameLauncher launcher = new MockGameLauncher(configWithBinary(missing));

        MockGameLaunchException ex = assertThrows(MockGameLaunchException.class, launcher::start);

        assertTrue(
                ex.getMessage().contains("binary not found"),
                "message should name the failure mode; got: " + ex.getMessage());
        assertFalse(
                ex.getMessage().contains("\n"),
                "error must be a single line; got: " + ex.getMessage());
    }

    @Test
    void nativeBinaryArgvRunsBinaryDirectly() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 0\n");
        List<String> argv = new MockGameLauncher(configWithBinary(binary)).buildArgv(binary);

        assertEquals(binary.toString(), argv.get(0), "native binary should be argv[0]");
        assertEquals(
                "--gpgnet-port", argv.get(1), "--gpgnet-port must immediately follow the binary");
    }

    @Test
    void jarBinaryArgvIsLaunchedViaJavaJar() throws Exception {
        Path jar = createStub("mock-game.jar", "");
        List<String> argv = new MockGameLauncher(configWithBinary(jar)).buildArgv(jar);

        assertTrue(argv.get(0).contains("java"), "a .jar must run via the java binary: " + argv);
        assertEquals("-jar", argv.get(1), "java must be invoked with -jar");
        assertEquals(jar.toString(), argv.get(2), "the jar path must follow -jar");
        assertEquals("--gpgnet-port", argv.get(3), "--gpgnet-port must follow the jar path");
    }

    @Test
    void argvCarriesEveryRequiredGameFlag() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 0\n");
        List<String> argv = new MockGameLauncher(configWithBinary(binary)).buildArgv(binary);

        // Ports are sourced from the same MockClientConfig fields the adapter uses (spec §2.8
        // requires the values to match between adapter and game).
        assertEquals("7237", valueAfter(argv, "--gpgnet-port"));
        assertEquals("7238", valueAfter(argv, "--lobby-port"));
        assertEquals("mock-client", valueAfter(argv, "--player-login"));
        // iceAdapterGameId default, meaning no orchestrated session.
        assertEquals("0", valueAfter(argv, "--game-uid"));
    }

    @Test
    void argvCarriesEveryGameOption() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 0\n");
        Map<String, String> gameOptions = Map.of("Victory", "demoralization", "Slots", "6");
        List<String> argv =
                new MockGameLauncher(configWithBinaryAndGameOptions(binary, gameOptions))
                        .buildArgv(binary);

        for (var option : gameOptions.entrySet()) {
            int i = argv.indexOf(String.format("%s=%s", option.getKey(), option.getValue()));
            assertTrue(
                    i != -1 && i > 0 && argv.get(i - 1).equals("--game-option"),
                    "Game option was not carried to the mock game binary");
        }
    }

    @Test
    void orchestratedArgvCarriesSessionIdentityNotConfig() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 0\n");
        LaunchIdentity identity = new LaunchIdentity(9001, "welcome-login", 4242);

        List<String> argv =
                new MockGameLauncher(configWithBinary(binary)).buildArgv(binary, identity);

        // The config this launcher holds would give player id 1 and login "mock-client".
        assertEquals("9001", valueAfter(argv, "--player-id"));
        assertEquals("welcome-login", valueAfter(argv, "--player-login"));
        assertEquals("4242", valueAfter(argv, "--game-uid"));
    }

    @Test
    void orchestratedIdentityBeatsPlayerIdOverride() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 0\n");
        MockClientConfig config = configWithBinaryAndPlayerId(binary, 42);

        List<String> argv =
                new MockGameLauncher(config)
                        .buildArgv(binary, new LaunchIdentity(9001, "welcome-login", 4242));

        assertEquals(
                "9001",
                valueAfter(argv, "--player-id"),
                "a session launch is bound to the lobby id, so the override must not apply");
    }

    @Test
    void playerIdOverrideSuppliesGameId() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 0\n");
        MockClientConfig config = configWithBinaryAndPlayerId(binary, 42);
        List<String> argv = new MockGameLauncher(config).buildArgv(binary);

        assertEquals(
                "42",
                valueAfter(argv, "--player-id"),
                "--player-id should come from playerIdOverride");
    }

    @Test
    void absentPlayerIdOverrideFallsBackToDefault() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 0\n");
        List<String> argv = new MockGameLauncher(configWithBinary(binary)).buildArgv(binary);

        assertEquals(
                Integer.toString(MockGameLauncher.DEFAULT_PLAYER_ID),
                valueAfter(argv, "--player-id"));
    }

    @Test
    void startCapturesTaggedOutputThenTerminatesCleanly() throws Exception {
        // ProcessOutputLogger flushes a line only when the next line arrives (it coalesces
        // stack-trace continuations), so the stub emits a heartbeat to flush the marker.
        Path binary =
                createStub(
                        "mock-game",
                        "#!/bin/sh\n"
                                + "echo MOCK-GAME-STUB-MARKER\n"
                                + "while true; do echo heartbeat; sleep 1; done\n");

        SubprocessManager game = new MockGameLauncher(configWithBinary(binary)).start();
        try {
            assertTrue(game.isAlive(), "stub mock-game should be running after start()");
            assertTrue(game.pid() > 0, "started process should expose a pid");
            awaitLog(
                    e ->
                            "MOCK-GAME-STUB-MARKER".equals(e.getMessage())
                                    && MockGameLauncher.COMPONENT_TAG.equals(
                                            e.getMDCPropertyMap()
                                                    .get(LoggingSetup.COMPONENT_MDC_KEY)));
        } finally {
            game.terminate();
        }

        int code = game.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertFalse(game.isAlive(), "mock-game should be dead after terminate()");
        assertEquals(128 + 15, code, "a SIGTERM-ed process exits with 143");
    }

    @Test
    void startSurfacesExitCodeWhenGameExitsOnItsOwn() throws Exception {
        Path binary = createStub("mock-game", "#!/bin/sh\nexit 7\n");

        SubprocessManager game = new MockGameLauncher(configWithBinary(binary)).start();
        int code = game.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(7, code, "mock-game's own exit code must be observable");
    }

    /** Writes {@code body} to an executable file named {@code name} in the temp dir. */
    private Path createStub(final String name, final String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, body);
        assertTrue(script.toFile().setExecutable(true), "could not mark stub executable");
        return script;
    }

    private static String valueAfter(final List<String> argv, final String flag) {
        int i = argv.indexOf(flag);
        assertTrue(i >= 0 && i + 1 < argv.size(), flag + " missing or has no value in " + argv);
        return argv.get(i + 1);
    }

    private static MockClientConfig configWithBinaryAndGameOptions(
            final Path binary, final Map<String, String> gameOptions) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "--lobby-websocket-url=wss://lobby.faforever.xyz",
                                "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                                "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                                "--oauth-redirect-uri=http://127.0.0.1",
                                "--oauth-scopes=openid offline lobby",
                                "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                                "--oauth-refresh-token-file=/nonexistent/test-refresh-token",
                                "--unique-id=00000000-0000-0000-0000-000000000000",
                                "--mock-game-binary-path=" + binary,
                                "--host-title=Test",
                                "--host-map=scmp_007",
                                "--host-mod=faf",
                                "--host-visibility=public"));

        for (var option : gameOptions.entrySet()) {
            args.add(String.format("--host-game-option=%s=%s", option.getKey(), option.getValue()));
        }
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    private static MockClientConfig configWithBinary(final Path binary) {
        return configWithBinaryAndPlayerId(binary, null);
    }

    private static MockClientConfig configWithBinaryAndPlayerId(
            final Path binary, final Integer playerId) {
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "--lobby-websocket-url=wss://lobby.faforever.xyz",
                                "--oauth-token-url=https://hydra.faforever.xyz/oauth2/token",
                                "--oauth-auth-endpoint=https://hydra.faforever.xyz/oauth2/auth",
                                "--oauth-redirect-uri=http://127.0.0.1",
                                "--oauth-scopes=openid offline lobby",
                                "--oauth-client-id=95ecec08-29c1-4c48-ae0a-b000ff349cb8",
                                "--oauth-refresh-token-file=/nonexistent/test-refresh-token",
                                "--unique-id=00000000-0000-0000-0000-000000000000",
                                "--mock-game-binary-path=" + binary));
        if (playerId != null) {
            args.add("--player-id-override=" + playerId);
        }
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    private void awaitLog(final Predicate<ILoggingEvent> matcher) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            for (ILoggingEvent e : appender.list) {
                if (matcher.test(e)) {
                    return;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        fail("predicate never matched. captured: " + appender.list);
    }
}
