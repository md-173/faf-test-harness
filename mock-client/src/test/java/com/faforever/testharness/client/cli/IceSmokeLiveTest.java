package com.faforever.testharness.client.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.faforever.testharness.client.config.ConfigLoader;
import com.faforever.testharness.client.process.FakeAdapterStub;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import picocli.CommandLine;

/**
 * Live proof that the shipped {@code ice-smoke} command passes against the <em>real</em> {@code
 * faf-ice-adapter} jar (WBS-3.1.4.3). Tagged {@code integration} so it runs under {@code ./gradlew
 * integrationTest}, not the default {@code test} task.
 *
 * <p>{@link IceSmokeCommandTest} pins the command's contract against a fake adapter, which proves
 * the wiring but not that the real binary behaves the way the check assumes — that its GPGNet
 * accept is announced over JSON-RPC, and that both endpoints come up inside the budget. This covers
 * exactly that gap, through the same picocli entry point an operator uses.
 *
 * <p><b>Gating.</b> Mirrors {@code IceAdapterConnectionLiveSmokeTest}: an {@link EnabledIf} probe
 * self-skips (does not fail) when no real adapter jar is resolvable, from {@code
 * FAF_ICE_ADAPTER_JAR} or the default {@code faf-ice-adapter.jar}. Provisioning is documented in
 * {@code documentation/operations/ice-adapter-setup.md} and deliberately not duplicated here.
 *
 * <p>The budget passed below is the assertion about speed: a pass means the whole check — spawn,
 * connect, round-trip, GPGNet probe, teardown — finished inside it. That is a bound the command
 * enforces itself, not a wall-clock measurement taken here, so a loaded CI box cannot turn a slow
 * machine into a mystery failure — it fails as an unreachable verdict with the phase named.
 */
@Tag("integration")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class IceSmokeLiveTest {

    /** Environment override for the adapter jar, consistent with R74's documented setup. */
    private static final String ADAPTER_JAR_ENV = "FAF_ICE_ADAPTER_JAR";

    /**
     * Budget handed to the command. Comfortably above the ~2 s a healthy adapter needs, and far
     * enough below the class timeout that a blown budget still reports as a verdict.
     */
    private static final Duration BUDGET = Duration.ofSeconds(30);

    @Test
    @EnabledIf("adapterJarAvailable")
    void iceSmokePassesAgainstTheRealAdapter() {
        Path binary = resolveAdapterBinary();
        int[] ports = FakeAdapterStub.freePorts();

        long start = System.nanoTime();
        int exit =
                execute(
                        new String[] {
                            "ice-smoke",
                            "--ice-adapter-binary-path=" + binary.toAbsolutePath(),
                            "--ice-adapter-rpc-port=" + ports[0],
                            "--ice-adapter-gpg-net-port=" + ports[1],
                            "--ice-adapter-lobby-port=" + ports[2],
                            "--timeout-seconds=" + BUDGET.toSeconds()
                        });
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        System.out.println("[live] ice-smoke against the real adapter took " + elapsed);
        assertEquals(
                ExitCodes.OK,
                exit,
                "ice-smoke must pass against a real adapter; it ran for " + elapsed);
    }

    private static int execute(final String[] args) {
        CommandLine cmd = ConfigLoader.newCommandLine(args, Map.of());
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute(args);
    }

    /** {@code @EnabledIf} probe — skips cleanly (not fails) when no real adapter jar is present. */
    @SuppressWarnings("unused")
    static boolean adapterJarAvailable() {
        Path binary = findAdapterBinary();
        if (binary == null) {
            System.out.println(
                    "[live] skipping ice-smoke live test: no real faf-ice-adapter jar found (set "
                            + ADAPTER_JAR_ENV
                            + " or run ./gradlew downloadIceAdapter; see "
                            + "documentation/operations/ice-adapter-setup.md).");
        }
        return binary != null;
    }

    /**
     * Non-null variant for the test body; the binary is guaranteed present once the gate passes.
     */
    private static Path resolveAdapterBinary() {
        Path binary = findAdapterBinary();
        if (binary == null) {
            throw new IllegalStateException("adapter jar vanished after the @EnabledIf gate");
        }
        return binary;
    }

    /**
     * Resolve the real adapter jar: the {@code FAF_ICE_ADAPTER_JAR} env override first, then the
     * default {@code faf-ice-adapter.jar} relative to the subproject CWD and the repo root.
     */
    private static Path findAdapterBinary() {
        String override = System.getenv(ADAPTER_JAR_ENV);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            return Files.isRegularFile(p) ? p : null;
        }
        for (String candidate : new String[] {"faf-ice-adapter.jar", "../faf-ice-adapter.jar"}) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }
}
