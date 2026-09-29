package com.faforever.testharness.shared.process;

import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The argv prefix that has the kernel send a child SIGTERM when the JVM that started it dies, even
 * by SIGKILL or the OOM killer, which run no shutdown hook. This is layer 2 of
 * subprocess-orchestration-spec §7.3; {@link SubprocessRegistry}'s hook is layer 1.
 *
 * <p>The prefix is util-linux's {@code setpriv --pdeathsig TERM --}. setpriv sets {@code
 * PR_SET_PDEATHSIG} and then execs the child in its own process, so the PID the harness tracks is
 * still the child's. SIGTERM is also the first signal {@link SubprocessManager#terminate} sends:
 * the adapter's JVM exits on it, and mock-game runs its own teardown.
 *
 * <p>The prefix is empty on anything but Linux, and on a Linux host without a setpriv that can set
 * the signal (util-linux 2.33 and later can). A child then outlives a killed JVM, as it always did.
 * Which applies is decided once per JVM, by running the prefix itself.
 *
 * <p>The kernel sends the signal when the <em>thread</em> that started the child exits, not the
 * process ({@code prctl(2)}). A child launched with this prefix must therefore be started by {@link
 * SubprocessManager#start}, which starts every child from one thread that lives as long as the JVM.
 */
public final class ParentDeathSignal {

    /** Diagnostic logger, for the one line saying the prefix is not usable. */
    private static final Logger LOG = LoggerFactory.getLogger(ParentDeathSignal.class);

    /**
     * The prefix where it works; {@code setpriv} is looked up on {@code PATH}, as the probe's is.
     */
    private static final List<String> SETPRIV = List.of("setpriv", "--pdeathsig", "TERM", "--");

    /** Longest the probe may take. It exits within milliseconds, so this only bounds a hang. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private ParentDeathSignal() {}

    /**
     * Returns the prefix to put in front of a child's argv: {@code [setpriv, --pdeathsig, TERM,
     * --]} on a Linux host where it works, otherwise empty. The first call runs the probe; the
     * answer holds for the life of the JVM.
     *
     * @return an immutable list, empty where the prefix is not usable
     */
    public static List<String> prefix() {
        return Holder.PREFIX;
    }

    /**
     * Whether {@code candidate} can set the signal here, found by running it in front of its own
     * setpriv's {@code --version}. A missing setpriv, one without {@code --pdeathsig} (util-linux
     * before 2.33, or BusyBox's), one that cannot set it, and one that hangs all answer {@code
     * false}. Never throws: {@link Holder} runs this during class initialisation, where an
     * exception would fail every later launch.
     *
     * @param candidate the prefix to try; its first element is the setpriv to run
     * @return {@code true} if the probe exited 0 within {@link #PROBE_TIMEOUT}
     */
    static boolean works(final List<String> candidate) {
        List<String> probe = new ArrayList<>(candidate);
        probe.add(candidate.get(0));
        probe.add("--version");
        try {
            Process process =
                    new ProcessBuilder(probe)
                            .redirectErrorStream(true)
                            .redirectOutput(Redirect.DISCARD)
                            .start();
            if (exitedWithin(process, PROBE_TIMEOUT)) {
                return process.exitValue() == 0;
            }
            process.destroyForcibly();
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Waits for {@code process} to exit without giving up on an interrupt, since the answer is kept
     * for the life of the JVM and an interrupt must not turn into "not usable". Keeps the caller's
     * interrupt flag.
     *
     * @param process the probe
     * @param timeout the longest to wait
     * @return {@code true} if the probe exited in time
     */
    private static boolean exitedWithin(final Process process, final Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return process.waitFor(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Runs the probe once, on the first {@link #prefix()} call. */
    private static final class Holder {

        /** The prefix this JVM uses. */
        static final List<String> PREFIX = decide();

        private Holder() {}

        /**
         * Decides the prefix for this JVM.
         *
         * @return {@link #SETPRIV} on a Linux host where it works, otherwise empty
         */
        private static List<String> decide() {
            if (!System.getProperty("os.name", "").startsWith("Linux")) {
                return List.of();
            }
            if (works(SETPRIV)) {
                return SETPRIV;
            }
            LOG.info(
                    "setpriv --pdeathsig (util-linux 2.33 or later) is not usable here, so the"
                            + " adapter and game keep running if this JVM is killed");
            return List.of();
        }
    }
}
