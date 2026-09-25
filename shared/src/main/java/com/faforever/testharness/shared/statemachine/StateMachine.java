package com.faforever.testharness.shared.statemachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** State machine, which drives transitions and keeps current state. */
public class StateMachine implements EventListener {

    /** Logger instance for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(StateMachine.class);

    /** The current state of the machine. */
    private volatile State state;

    /** The policy to use when an event is received and no matching transition is found. */
    private final InvalidTransitionPolicy transitionPolicy;

    /** Timer used for timeouts. */
    private final Timer timeoutTimer;

    /**
     * Timeouts armed and not yet fired or disarmed, guarded by this machine's monitor. {@link
     * #cancel()} leaves it alone, since {@link Timer#cancel()} already discards everything
     * scheduled, so afterwards it can still hold tasks that will never run.
     */
    private final List<TimerTask> timeouts;

    /** A map from states to a future that should be completed when the state is reached. */
    private final Map<State, CompletableFuture<Void>> awaitedStates;

    /**
     * Orders {@link #cancel()} against the arming step of {@link #setTimeout(long, State,
     * TransitionAction)}, so a timeout is never handed to a timer {@code cancel()} has already
     * stopped. It is held only around that check and a single {@link Timer} call, never across a
     * transition or a log call, which is what lets {@code cancel()} return while a transition
     * action holds this machine's monitor (WBS-2.3.7-fix, #328).
     */
    private final Object schedulingLock = new Object();

    /**
     * Whether {@link #cancel()} has run. Written under {@link #schedulingLock}, where {@link
     * #setTimeout(long, State, TransitionAction)} reads it to become a no-op rather than throwing.
     * Volatile because a timeout the timer has already dequeued reads it under this machine's
     * monitor instead.
     */
    private volatile boolean cancelled;

    /**
     * Initializes the machine with its initial state and policy.
     *
     * @param initialState the initial state of the machine.
     * @param policy the policy to use when an event is received and no transition is found.
     */
    public StateMachine(State initialState, InvalidTransitionPolicy policy) {
        this.state = initialState;
        this.transitionPolicy = policy;
        this.timeoutTimer = new Timer(true);
        this.timeouts = new ArrayList<>();
        this.awaitedStates = new HashMap<>();

        LOG.info(
                "Created StateMachine with initial state {} and policy {}",
                this.state.getName(),
                this.transitionPolicy);
    }

    /**
     * Initializes the machine with its initial state.
     *
     * @param initialState the initial state of the machine.
     */
    public StateMachine(State initialState) {
        this(initialState, InvalidTransitionPolicy.IGNORE);
    }

    /**
     * Getter for the current state of the machine.
     *
     * @return the current machine state.
     */
    public State getState() {
        return state;
    }

    /**
     * Gives a future that completes when the state is reached. If the state machine's current state
     * is {@code s} then the future completes immediately.
     *
     * <p><b>Not resolved by {@link #cancel()}.</b> If {@code s} is never reached — including
     * because {@link #cancel()} has stopped the time-based scheduling that would have reached it —
     * the future returned here is left pending forever; {@code cancel()} does not complete, cancel,
     * or otherwise resolve it. A caller that blocks on this future (e.g. with {@code get()}) must
     * have some other bound, such as a timeout, if the state it names might not be reached
     * (WBS-2.3.7-fix, #260).
     *
     * @param s state to wait for.
     * @return a future that only completes when the state is reached.
     */
    public synchronized CompletableFuture<Void> stateReached(State s) {
        if (state == s) {
            // Return an already completed future if the state has already been reached.
            return CompletableFuture.completedFuture(null);
        } else {
            return awaitedStates.computeIfAbsent(s, ignored -> new CompletableFuture<>());
        }
    }

    /**
     * Forwards event to its current state, then updates state.
     *
     * @throws InvalidTransitionException if the {@link StateMachine} policy was set to {@link
     *     InvalidTransitionPolicy#THROW} and the transition is invalid/non-existent.
     */
    @Override
    public synchronized void receiveEvent(Event event) {
        LOG.debug("Received event {}", event);
        List<Transition> transitions = state.getTransitions(event.getClass());
        if (transitions.isEmpty()) {
            LOG.warn("No matching transitions for {}", event.getClass().getSimpleName());
            if (transitionPolicy == InvalidTransitionPolicy.THROW) {
                throw new InvalidTransitionException(
                        String.format(
                                "No valid transitions for events of type %s",
                                event.getClass().getSimpleName()));
            }
        } else {
            LOG.debug(
                    "Obtained a set of transitions for {}, attempting now",
                    event.getClass().getSimpleName());
            for (var t : transitions) {
                if (t.guard(event)) {
                    List<TimerTask> armedBefore = List.copyOf(timeouts);
                    State newState = t.transition(event);
                    if (newState == null) {
                        // The event was handled but no transition occurred, so none of the
                        // bookkeeping that follows a state change applies: pending timeouts stay
                        // armed and no awaited state is completed.
                        LOG.debug(
                                "Event {} handled with no state change, staying in {}",
                                event,
                                state.getName());
                    } else {
                        LOG.debug(
                                "Transition from {} to {} caused by {} successful",
                                state.getName(),
                                newState.getName(),
                                event);
                        commitTransition(newState, armedBefore);
                    }
                    // Stop trying more transitions.
                    return;
                }
            }
            LOG.debug(
                    "All transitions for {} failed due to guards",
                    event.getClass().getSimpleName());
        }
    }

    /**
     * Adopts the result of a transition that actually changed state: makes it current, disarms the
     * timeouts that were pending when the transition began (a state change is exactly what they
     * wait for) and releases anything blocked on {@link #stateReached(State)} for the new state.
     * Only ever called with a genuine new state; see {@link Transition#transition(Event)} for when
     * there isn't one.
     *
     * <p>A timeout armed during the transition itself, by its action or by an exit or entry hook,
     * is not in {@code armedBefore} and stays armed: it belongs to the state the machine lands in,
     * a failure state included, and the next commit disarms it (WBS-2.3.7-fix, #259). So does one
     * armed by a {@link #stateReached(State)} callback during this commit. Clearing the whole list
     * here used to discard such a timeout silently.
     *
     * <p>The caller must already hold this machine's monitor.
     *
     * @param newState the state the machine has just moved into.
     * @param armedBefore the timeouts that were pending when the transition began.
     */
    private void commitTransition(State newState, List<TimerTask> armedBefore) {
        state = newState;
        for (var timeout : armedBefore) {
            timeout.cancel();
        }
        timeouts.removeAll(armedBefore);
        // `awaitedStates` never holds an entry for the current state, which is why nothing can be
        // left waiting on a state the machine is already in. Three things guarantee it and all
        // three must be kept: `state` is written only here and in the constructor, this removal is
        // unconditional on every commit, and `stateReached` short-circuits under this same monitor
        // when asked for the current state.
        CompletableFuture<Void> alert = awaitedStates.remove(state);
        if (alert != null) {
            alert.complete(null);
        }
    }

    /**
     * Sets up a timeout that will cause a transition to state {@code to} if no other transition
     * after {@code millis} elapses.
     *
     * @param millis the time in milliseconds to wait before changing states.
     * @param to the new state to go to.
     */
    public synchronized void setTimeout(long millis, State to) {
        setTimeout(millis, to, null);
    }

    /**
     * Sets up a timeout that will cause a transition to state {@code to} if no other transition
     * after {@code millis} elapses. This transition causes {@code action} to fire.
     *
     * <p>The clock starts now, and any transition that commits after this call disarms the timeout
     * except the one in progress when it is armed: a timeout armed from a transition's action, or
     * from an exit or entry hook, belongs to the state that transition lands in and survives its
     * commit (WBS-2.3.7-fix, #259). A deadline meant to run from entry to a state is therefore
     * armed in that state's entry hook.
     *
     * @param millis the time in milliseconds to wait before changing states.
     * @param to the new state to go to.
     * @param action the action to fire when the timeout occurs.
     */
    public synchronized void setTimeout(long millis, State to, TransitionAction action) {
        UpdateStateTask task = new UpdateStateTask(to, action);
        boolean armed;
        // The check and the schedule are one step as far as cancel() is concerned. It no longer
        // takes this machine's monitor (#328), so without the lock it could stop the timer between
        // the two, and Timer.schedule would then throw. Nothing else runs under it, logging
        // included.
        synchronized (schedulingLock) {
            armed = !cancelled;
            if (armed) {
                timeoutTimer.schedule(task, millis);
            }
        }
        if (!armed) {
            // The machine is shutting down and the timer is dead, so Timer.schedule would throw
            // IllegalStateException. Silently arming nothing is the honest reading of the request:
            // cancel() means no scheduled transition may fire after it, and this is one. It also
            // stops a caller having to know the ordering: a SIGTERM landing between the shutdown
            // hook being installed and a lifecycle arming its first timeout used to take the
            // process down with an uncaught throw instead of an exit code.
            LOG.debug("Ignoring timeout into {}; scheduling is already cancelled", to.getName());
            return;
        }
        // Added after scheduling but still under this machine's monitor, which the task's run()
        // must take before it looks for itself here.
        timeouts.add(task);
        LOG.debug("Setting up timeout for {}ms into {}", millis, to.getName());
    }

    /**
     * Stops the machine's time-based scheduling: shuts down the timer thread, so once this returns
     * no timeout goes on to take this machine's monitor and start a transition, including one the
     * timer has already dequeued and that is waiting for the monitor. A timeout that already holds
     * the monitor when this is called runs to completion and commits, as an event-driven transition
     * does, and that includes one whose own transition calls this: stopping one after its hooks
     * have run but before its commit would leave the machine half-transitioned (#258). Intended for
     * the shutdown path: it is terminal, so a later {@link #setTimeout(long, State)} arms nothing
     * and returns rather than throwing on the dead timer. Event-driven transitions via {@link
     * #receiveEvent(Event)} are unaffected. Idempotent: calling it more than once is safe.
     *
     * <p><b>Never waits for a transition</b> (WBS-2.3.7-fix, #328). It takes only {@link
     * #schedulingLock}, never this machine's monitor, so it returns at once even while a transition
     * action holds the monitor, blocked on I/O, a lock or a sleep. It used to be {@code
     * synchronized}, which put the mock game's teardown behind a stalled GPGNet write (#299).
     *
     * <p><b>Does not touch {@link #awaitedStates}.</b> A future already handed out by {@link
     * #stateReached(State)} for a state this machine never reaches is left pending forever — this
     * method neither completes nor cancels it, and no future one taken after this call is resolved
     * either. This is a known, deliberate gap (WBS-2.3.7-fix, #260): closing it by cancelling those
     * futures here regressed the ordinary shutdown path (a JVM shutdown hook calling this from a
     * thread with no transition in flight, racing the thread still parked on {@link
     * #stateReached(State)}), so the omission is documented rather than papered over. Callers that
     * block on a {@link #stateReached(State)} future for a state that might never be reached must
     * bound that wait themselves.
     */
    public void cancel() {
        synchronized (schedulingLock) {
            cancelled = true;
            timeoutTimer.cancel();
        }
    }

    private class UpdateStateTask extends TimerTask {
        /** The state the timeout moves the machine to. */
        private final State to;

        /** The action to run on the way, or {@code null} for none. */
        private final TransitionAction action;

        UpdateStateTask(State to, TransitionAction action) {
            this.to = to;
            this.action = action;
        }

        @Override
        public void run() {
            // Synchronize with receiveEvent by using the outer class instance as monitor.
            synchronized (StateMachine.this) {
                if (cancelled) {
                    // Dequeued before cancel() stopped the timer, then parked on the monitor.
                    // cancel() never takes this monitor and leaves `timeouts` alone (#328), so
                    // this flag is the only thing that stops the task here.
                    LOG.debug("Timeout fired after scheduling was cancelled, ignoring");
                    return;
                }
                // TimerTask.cancel() cannot stop a task the timer thread has already dequeued: it
                // runs anyway and blocks here until the thread that cancelled it releases the
                // monitor. Membership of `timeouts` settles whether that happened, because every
                // commit removes the timeouts that were pending when it began. Without this, a
                // timeout cancelled by a commit would still fire, moving the machine out of a state
                // it was not armed in.
                // Removing it here also keeps `timeouts` meaning "pending": this task has now run.
                // Note this only covers cancellation. It is checked before the action runs, so an
                // action that moves the machine itself (by calling receiveEvent) still leaves the
                // `from` below stale.
                if (!timeouts.remove(this)) {
                    LOG.debug("Timeout fired after being cancelled, ignoring");
                    return;
                }
                List<TimerTask> armedBefore = List.copyOf(timeouts);
                // Built now rather than when armed, so that exit hooks run for the state actually
                // being left. A timeout armed during a transition is created while `state` still
                // names the state being left, and it outlives that transition's commit (#259). For
                // a timeout still pending, the current state is the one it belongs to.
                Transition transition = new Transition(state, to, action, null);
                // No need to check guard and no actual event that triggered this.
                State newState;
                try {
                    newState = transition.transition(null);
                } catch (RuntimeException e) {
                    // Letting this escape would kill the timer thread, and every later setTimeout
                    // would then throw IllegalStateException. The thrower can only be the
                    // transition action: `State.runHooks` contains every RuntimeException a hook
                    // raises, so no hook failure reaches this catch. The transition therefore
                    // never half-ran — nothing was assigned and the machine is still in `state`.
                    LOG.error(
                            "Timeout transition out of {} threw in its action; state left as {}",
                            state.getName(),
                            state.getName(),
                            e);
                    return;
                }
                if (newState == null) {
                    // The timeout's own action failed without naming a failure state, or it targets
                    // the state we are already in. Either way nothing changed, so other timeouts
                    // stay armed and no awaited state is completed.
                    LOG.debug("Timeout fired with no state change, staying in {}", state.getName());
                    return;
                }
                LOG.debug("Timeout fired, new state is {}", newState.getName());
                commitTransition(newState, armedBefore);
            }
        }
    }
}
