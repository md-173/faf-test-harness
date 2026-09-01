package com.faforever.testharness.client.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.faforever.testharness.client.config.IceAdapterSettings;
import com.faforever.testharness.client.process.IceReachabilityCheck.Result;
import com.faforever.testharness.client.process.IceReachabilityCheck.Verdict;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link IceReachabilityCheck}: one per verdict, each produced deterministically.
 *
 * <p>The reachable case runs against {@link FakeIceAdapter} spawned as a real subprocess through
 * the real {@link IceAdapterLauncher}, so the launch and the port pre-flight are exercised rather
 * than bypassed. The failure cases pick a {@link FakeIceAdapter.Mode} that omits exactly one of the
 * things the check requires.
 *
 * <p>No test waits on a sleep or on a port it does not own. The fake binds its ports only after
 * being spawned, which is what the check's connect retry is for; the ports-in-use test holds a real
 * listener open for the whole test rather than releasing one and assuming nothing else claims it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
final class IceReachabilityCheckTest {

    /**
     * Budget for the reachable path. Generous because it covers a JVM cold start, but never waited
     * out: the check returns as soon as the fake answers.
     */
    private static final Duration BUDGET = Duration.ofSeconds(20);

    /**
     * Budget for the cases that must expire. Short so the suite does not idle, but not so short
     * that the connect phase — which reserves the later phases' share and so gets half of a budget
     * this size — leaves too little room for a fake adapter's JVM to start under CI load.
     */
    private static final Duration SHORT_BUDGET = Duration.ofSeconds(6);

    /**
     * Slack allowed on top of {@link #SHORT_BUDGET} for teardown, which runs outside the check's
     * deadline. The documented bound is twice the check's 2 s terminate grace; this ceiling is
     * comfortably above it so only an unbounded teardown fails the assertion.
     */
    private static final int TEARDOWN_CEILING_SECONDS = 12;

    @TempDir private Path tempDir;

    @Test
    void reachableAdapterPassesAndIsTornDown() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.FULL);

        Result result = new IceReachabilityCheck(stub.settings(), BUDGET).run();

        assertEquals(Verdict.REACHABLE, result.verdict(), result.detail());
        assertTrue(result.reachable(), "a served adapter is the success case");
        assertTrue(
                result.detail().contains(Integer.toString(stub.rpcPort())),
                "the pass line should name the endpoints it proved; got: " + result.detail());
        assertPortReleased(stub.rpcPort(), "the adapter must be terminated before returning");
    }

    @Test
    void adapterThatRefusesTheRequestStillCountsAsAnswering() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.ERRORING_RPC);

        Result result = new IceReachabilityCheck(stub.settings(), BUDGET).run();

        // An error response is an answer: it proves the adapter read the frame, dispatched it, and
        // replied. Reporting that as "did not answer" would name the wrong fault.
        assertEquals(Verdict.REACHABLE, result.verdict(), result.detail());
    }

    @Test
    void adapterIgnoringSigtermIsStillKilledAndTheRunStaysBounded() throws Exception {
        // A plain script that traps SIGTERM: teardown must escalate to SIGKILL within its grace
        // rather than hanging, and must do so even though the check itself already failed.
        Path stubborn = tempDir.resolve("sigterm-ignoring-adapter");
        Files.writeString(stubborn, "#!/bin/sh\ntrap '' TERM\nwhile true; do sleep 1; done\n");
        assertTrue(stubborn.toFile().setExecutable(true), "could not mark stub executable");
        IceAdapterSettings settings =
                FakeAdapterStub.settingsFor(stubborn, FakeAdapterStub.freePorts());

        long start = System.nanoTime();
        Result result = new IceReachabilityCheck(settings, SHORT_BUDGET).run();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertEquals(Verdict.RPC_UNREACHABLE, result.verdict(), result.detail());
        // Budget + twice the teardown grace is the documented bound; the ceiling here is loose
        // enough that only a genuinely unbounded teardown trips it.
        assertTrue(
                elapsed.compareTo(SHORT_BUDGET.plusSeconds(TEARDOWN_CEILING_SECONDS)) < 0,
                "teardown must be bounded, not open-ended; the run took " + elapsed);
        assertPortReleased(
                settings.rpcPort(), "a SIGTERM-ignoring adapter must still be killed off");
    }

    @Test
    void missingBinaryFailsFastWithoutSpawning() {
        Path missing = tempDir.resolve("no-such-faf-ice-adapter");
        IceAdapterSettings settings =
                FakeAdapterStub.settingsFor(missing, FakeAdapterStub.freePorts());

        Result result = new IceReachabilityCheck(settings, BUDGET).run();

        assertEquals(Verdict.LAUNCH_FAILED, result.verdict(), result.detail());
        assertTrue(
                result.detail().contains("binary not found"),
                "the failure should name the missing binary; got: " + result.detail());
    }

    @Test
    void portAlreadyInUseIsReportedBeforeAnythingIsSpawned() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.FULL);
        // Held open for the whole test: the check must see a genuinely occupied port, not a port
        // that merely happened to be free a moment ago.
        try (ServerSocket squatter = new ServerSocket(stub.rpcPort())) {
            Result result = new IceReachabilityCheck(stub.settings(), SHORT_BUDGET).run();

            assertEquals(Verdict.PORTS_IN_USE, result.verdict(), result.detail());
            assertTrue(
                    result.detail().contains(Integer.toString(squatter.getLocalPort())),
                    "the failure should name the busy port; got: " + result.detail());
        }
    }

    @Test
    void adapterThatNeverListensIsUnreachable() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.NO_LISTEN);

        Result result = new IceReachabilityCheck(stub.settings(), SHORT_BUDGET).run();

        assertEquals(Verdict.RPC_UNREACHABLE, result.verdict(), result.detail());
        assertTrue(
                result.detail().contains("JSON-RPC"),
                "the failure should name the JSON-RPC phase; got: " + result.detail());
    }

    @Test
    void adapterThatExitsImmediatelyIsReportedAsExited() throws Exception {
        FakeAdapterStub stub =
                FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.EXIT_IMMEDIATELY);

        Result result = new IceReachabilityCheck(stub.settings(), SHORT_BUDGET).run();

        assertEquals(Verdict.ADAPTER_EXITED, result.verdict(), result.detail());
    }

    @Test
    void adapterThatNeverAnswersARequestIsReportedSilent() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.DEAF_RPC);

        Result result = new IceReachabilityCheck(stub.settings(), SHORT_BUDGET).run();

        assertEquals(Verdict.RPC_SILENT, result.verdict(), result.detail());
        assertTrue(
                result.detail().contains("setLobbyInitMode"),
                "the failure should name the request that went unanswered; got: "
                        + result.detail());
    }

    @Test
    void adapterWithNoGpgNetListenerIsUnreachableOnThatEndpoint() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.RPC_ONLY);

        Result result = new IceReachabilityCheck(stub.settings(), SHORT_BUDGET).run();

        assertEquals(Verdict.GPGNET_UNREACHABLE, result.verdict(), result.detail());
        assertTrue(
                result.detail().contains(Integer.toString(stub.gpgNetPort())),
                "the failure should name the GPGNet port; got: " + result.detail());
    }

    @Test
    void gpgNetPortThatAcceptsButIsNeverAnnouncedIsNotAPass() throws Exception {
        FakeAdapterStub stub = FakeAdapterStub.create(tempDir, FakeIceAdapter.Mode.SILENT_GPGNET);

        Result result = new IceReachabilityCheck(stub.settings(), SHORT_BUDGET).run();

        assertEquals(Verdict.GPGNET_UNCONFIRMED, result.verdict(), result.detail());
        assertFalse(result.reachable(), "an open port alone must not pass the check");
    }

    /**
     * Fails unless {@code port} can be bound again, which it can only be once the adapter that held
     * it is gone. A positive assertion on purpose: proving teardown by binding a port ourselves
     * says something about the process under test, where probing for a refused connection would
     * only say that nobody happened to be listening (issue #287's lesson).
     */
    private static void assertPortReleased(final int port, final String message) {
        try (ServerSocket rebound = new ServerSocket(port)) {
            assertTrue(rebound.isBound(), message);
        } catch (IOException e) {
            throw new AssertionError(message + " (port " + port + " still held: " + e + ")", e);
        }
    }
}
