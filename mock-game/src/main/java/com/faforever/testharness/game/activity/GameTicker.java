package com.faforever.testharness.game.activity;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic tick source pacing the mock game's simulated activity (WBS-3.2.3).
 *
 * <p><b>Not a keep-alive.</b> Verified against the FAF sources: the ICE adapter imposes no liveness
 * requirement on the game — java-ice-adapter's {@code GPGNetServer} read loop blocks indefinitely
 * with no socket timeout, no ping, and no idle disconnect, and the server-side GPGNet command set
 * (faf-server {@code gameconnection.py}) has no heartbeat message. Peer-level keep-alives are
 * handled inside the adapter by ice4j. This class therefore sends no protocol frames of any kind;
 * it only paces activity that consumers attach — the UDP traffic sender (R48, WBS-3.2.2.5) emits
 * packets per tick.
 *
 * <p>The mode is fixed at construction:
 *
 * <ul>
 *   <li>{@link #realTime(Duration, Runnable)} — {@link #start()} schedules the callback on a single
 *       daemon thread ({@code game-ticker}), first tick one interval after start. {@code
 *       scheduleWithFixedDelay} is used rather than fixed-rate so a delayed tick is never followed
 *       by a catch-up burst; nothing on the wire depends on the average rate.
 *   <li>{@link #manual(Runnable)} — tests drive time deterministically via {@link #advance(int)},
 *       which delivers ticks synchronously on the calling thread. No scheduler thread ever exists.
 * </ul>
 *
 * <p>Calling the other mode's method throws {@link IllegalStateException}, so an accidental mix
 * (e.g. a test that starts a schedule and then asserts on manual ticks) fails loudly on the first
 * run instead of flaking.
 *
 * <p>The callback is exception-isolated: a throwing tick is logged and the schedule continues. This
 * is load-bearing, not convention — a {@link ScheduledExecutorService} silently cancels a periodic
 * task whose body throws, so the catch is what keeps the timer alive.
 *
 * <p>{@link #stop()} is idempotent, safe before {@link #start()}, and terminal (matching {@code
 * StateMachine#cancel()}): a stopped ticker never delivers again, in either mode, and {@code
 * start()} after {@code stop()} is a no-op. The scheduler thread is a daemon, so it can never block
 * JVM exit.
 */
public final class GameTicker {

    /** SLF4J logger — see logback.xml for the {@code component=MockGame} MDC. */
    private static final Logger LOG = LoggerFactory.getLogger(GameTicker.class);

    /** Delay between ticks in real-time mode; {@code null} in manual mode. */
    private final Duration interval;

    /** The consumer behaviour delivered once per tick. */
    private final Runnable onTick;

    /** True for a manual-advance ticker, false for a scheduled real-time ticker. */
    private final boolean manual;

    /** Single-thread daemon scheduler; created by {@link #start()}, guarded by {@code this}. */
    private ScheduledExecutorService executor;

    /** True once {@link #start()} has scheduled; guarded by {@code this}. */
    private boolean started;

    /** True once {@link #stop()} has run; volatile for the lock-free read in {@link #deliver()}. */
    private volatile boolean stopped;

    /**
     * Use {@link #realTime(Duration, Runnable)} or {@link #manual(Runnable)}.
     *
     * @param interval delay between ticks, or {@code null} in manual mode
     * @param onTick the consumer behaviour delivered once per tick
     * @param manual true for manual-advance mode
     */
    private GameTicker(final Duration interval, final Runnable onTick, final boolean manual) {
        this.interval = interval;
        this.onTick = onTick;
        this.manual = manual;
    }

    /**
     * Creates a real-time ticker delivering ticks every {@code interval} once {@link #start()} is
     * called.
     *
     * @param interval delay between ticks; must be positive
     * @param onTick the consumer behaviour delivered once per tick; must not be {@code null}
     * @return the ticker, not yet started
     * @throws IllegalArgumentException if {@code interval} is zero or negative
     */
    public static GameTicker realTime(final Duration interval, final Runnable onTick) {
        Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        return new GameTicker(interval, Objects.requireNonNull(onTick, "onTick"), false);
    }

    /**
     * Creates a manual-advance ticker for deterministic tests: ticks are delivered only by {@link
     * #advance(int)}, synchronously, with no wall-clock dependence.
     *
     * @param onTick the consumer behaviour delivered once per tick; must not be {@code null}
     * @return the ticker
     */
    public static GameTicker manual(final Runnable onTick) {
        return new GameTicker(null, Objects.requireNonNull(onTick, "onTick"), true);
    }

    /**
     * Starts the real-time schedule: first tick one interval from now, then one per interval.
     * Idempotent — already started or already stopped is a no-op.
     *
     * @throws IllegalStateException on a manual-mode ticker
     */
    public synchronized void start() {
        if (manual) {
            throw new IllegalStateException(
                    "manual-mode ticker has no schedule; drive it with advance()");
        }
        if (started || stopped) {
            return;
        }
        started = true;
        executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "game-ticker");
                            t.setDaemon(true);
                            return t;
                        });
        // Nanosecond units so a positive sub-millisecond interval doesn't truncate to an illegal
        // zero delay.
        executor.scheduleWithFixedDelay(
                this::deliver, interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Delivers {@code ticks} ticks synchronously on the calling thread, stopping early if {@link
     * #stop()} has run. Delivers nothing when {@code ticks} is not positive or the ticker is
     * stopped.
     *
     * @param ticks the number of ticks to deliver
     * @throws IllegalStateException on a real-time ticker
     */
    public synchronized void advance(final int ticks) {
        if (!manual) {
            throw new IllegalStateException(
                    "real-time ticker is driven by its schedule; advance() is manual-mode only");
        }
        for (int i = 0; i < ticks && !stopped; i++) {
            deliver();
        }
    }

    /**
     * Stops tick delivery permanently. Idempotent, safe before {@link #start()}, and terminal: no
     * tick is delivered after this returns (an in-flight real-time tick may complete concurrently).
     */
    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Delivers one tick, isolating callback exceptions so the schedule survives (log-and-continue).
     * {@link Error}s are deliberately not caught: manual mode propagates them synchronously to the
     * advancing test (an {@code AssertionError} in a callback should fail that test), and a
     * real-time schedule should not outlive a JVM-level failure.
     */
    private void deliver() {
        if (stopped) {
            return;
        }
        try {
            onTick.run();
        } catch (RuntimeException e) {
            LOG.warn("tick callback threw; continuing", e);
        }
    }
}
