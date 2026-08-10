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
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link IceAdapterLauncher}. The real {@code faf-ice-adapter} JAR is not available
 * in CI, so a stub shell script stands in for the binary (WBS-3.1.2.2 deliverables). Argument-list
 * construction is verified directly; spawn / capture / terminate are exercised against the stub.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
final class IceAdapterLauncherTest {

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
        IceAdapterLauncher launcher = new IceAdapterLauncher(configWithBinary(missing));

        IceAdapterLaunchException ex =
                assertThrows(IceAdapterLaunchException.class, launcher::start);

        assertTrue(
                ex.getMessage().contains("binary not found"),
                "message should name the failure mode; got: " + ex.getMessage());
        assertFalse(
                ex.getMessage().contains("\n"),
                "error must be a single line; got: " + ex.getMessage());
    }

    @Test
    void nativeBinaryArgvRunsBinaryDirectly() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 0\n");
        List<String> argv = new IceAdapterLauncher(configWithBinary(binary)).buildArgv(binary);

        assertEquals(binary.toString(), argv.get(0), "native binary should be argv[0]");
        assertEquals("--id", argv.get(1), "--id must immediately follow the binary");
        assertFalse(
                argv.stream().anyMatch(a -> a.startsWith("-Dlogback")),
                "a native binary takes no JVM flags: " + argv);
    }

    @Test
    void jarBinaryArgvIsLaunchedViaJavaJar() throws Exception {
        Path jar = createStub("adapter.jar", "");
        List<String> argv = new IceAdapterLauncher(configWithBinary(jar)).buildArgv(jar);

        assertTrue(argv.get(0).contains("java"), "a .jar must run via the java binary: " + argv);
        int jarFlag = argv.indexOf("-jar");
        assertTrue(jarFlag > 0, "java must be invoked with -jar: " + argv);
        assertEquals(jar.toString(), argv.get(jarFlag + 1), "the jar path must follow -jar");
        assertEquals("--id", argv.get(jarFlag + 2), "--id must follow the jar path");
        assertTrue(
                argv.subList(0, jarFlag).stream()
                        .anyMatch(a -> a.startsWith("-Dlogback.configurationFile=")),
                "the headless logback override must precede -jar so it reaches the JVM: " + argv);
    }

    @Test
    void argvCarriesEveryRequiredAdapterFlag() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 0\n");
        List<String> argv = new IceAdapterLauncher(configWithBinary(binary)).buildArgv(binary);

        assertEquals("mock-client", valueAfter(argv, "--login"));
        assertEquals("0", valueAfter(argv, "--game-id"));
        assertEquals("7236", valueAfter(argv, "--rpc-port"));
        assertEquals("7237", valueAfter(argv, "--gpgnet-port"));
        assertEquals("7238", valueAfter(argv, "--lobby-port"));
    }

    @Test
    void idAndLoginPrecedeEveryOtherFlag() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 0\n");
        List<String> argv = new IceAdapterLauncher(configWithBinary(binary)).buildArgv(binary);

        int rpc = argv.indexOf("--rpc-port");
        assertTrue(argv.indexOf("--id") >= 0 && argv.indexOf("--id") < rpc, "argv: " + argv);
        assertTrue(argv.indexOf("--login") >= 0 && argv.indexOf("--login") < rpc, "argv: " + argv);
    }

    @Test
    void orchestratedArgvCarriesSessionIdentityNotConfig() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 0\n");
        LaunchIdentity identity = new LaunchIdentity(9001, "welcome-login", 4242);

        List<String> argv =
                new IceAdapterLauncher(configWithBinary(binary)).buildArgv(binary, identity);

        // The config this launcher holds would give id 1, login "mock-client", game-id 0.
        assertEquals("9001", valueAfter(argv, "--id"));
        assertEquals("welcome-login", valueAfter(argv, "--login"));
        assertEquals("4242", valueAfter(argv, "--game-id"));
    }

    @Test
    void orchestratedIdentityBeatsPlayerIdOverride() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 0\n");
        MockClientConfig config = configWithBinaryAndPlayerId(binary, 42);

        List<String> argv =
                new IceAdapterLauncher(config)
                        .buildArgv(binary, new LaunchIdentity(9001, "welcome-login", 4242));

        assertEquals(
                "9001",
                valueAfter(argv, "--id"),
                "a session launch is bound to the lobby id, so the override must not apply");
    }

    @Test
    void playerIdOverrideSuppliesAdapterId() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 0\n");
        MockClientConfig config = configWithBinaryAndPlayerId(binary, 42);
        List<String> argv = new IceAdapterLauncher(config).buildArgv(binary);

        assertEquals("42", valueAfter(argv, "--id"), "--id should come from playerIdOverride");
    }

    @Test
    void absentPlayerIdOverrideFallsBackToDefault() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 0\n");
        List<String> argv = new IceAdapterLauncher(configWithBinary(binary)).buildArgv(binary);

        assertEquals(
                Integer.toString(IceAdapterLauncher.DEFAULT_PLAYER_ID), valueAfter(argv, "--id"));
    }

    @Test
    void startCapturesTaggedOutputThenTerminatesCleanly() throws Exception {
        // ProcessOutputLogger flushes a line only when the next line arrives (it coalesces
        // stack-trace continuations), so the stub emits a heartbeat to flush the marker.
        Path binary =
                createStub(
                        "adapter",
                        "#!/bin/sh\n"
                                + "echo ICE-ADAPTER-STUB-MARKER\n"
                                + "while true; do echo heartbeat; sleep 1; done\n");

        SubprocessManager adapter = new IceAdapterLauncher(configWithBinary(binary)).start();
        try {
            assertTrue(adapter.isAlive(), "stub adapter should be running after start()");
            assertTrue(adapter.pid() > 0, "started process should expose a pid");
            awaitLog(
                    e ->
                            "ICE-ADAPTER-STUB-MARKER".equals(e.getMessage())
                                    && IceAdapterLauncher.COMPONENT_TAG.equals(
                                            e.getMDCPropertyMap()
                                                    .get(LoggingSetup.COMPONENT_MDC_KEY)));
        } finally {
            adapter.terminate();
        }

        int code = adapter.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertFalse(adapter.isAlive(), "adapter should be dead after terminate()");
        assertEquals(128 + 15, code, "a SIGTERM-ed process exits with 143");
    }

    @Test
    void startSurfacesExitCodeWhenAdapterExitsOnItsOwn() throws Exception {
        Path binary = createStub("adapter", "#!/bin/sh\nexit 5\n");

        SubprocessManager adapter = new IceAdapterLauncher(configWithBinary(binary)).start();
        int code = adapter.onExit().get(AWAIT_SECONDS, TimeUnit.SECONDS);

        assertEquals(5, code, "the adapter's own exit code must be observable");
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
                                "--ice-adapter-binary-path=" + binary,
                                "--mock-game-binary-path=/bin/mock-game"));
        if (playerId != null) {
            args.add("--player-id-override=" + playerId);
        }
        return ConfigLoader.load(args.toArray(new String[0]), Map.of()).orElseThrow();
    }

    private void awaitLog(final Predicate<ILoggingEvent> matcher) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_BUDGET_MS;
        while (System.currentTimeMillis() < deadline) {
            ILoggingEvent[] snap;
            synchronized (appender) {
                snap = appender.list.toArray(new ILoggingEvent[0]);
            }
            for (ILoggingEvent e : snap) {
                if (matcher.test(e)) {
                    return;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        fail("predicate never matched. captured: " + appender.list);
    }
}
