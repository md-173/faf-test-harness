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

    /** Collection of tasks scheduled, kept to cancel them later if necessary. */
    private final List<TimerTask> timeouts;

    /** A map from states to a future that should be completed when the state is reached. */
    private final Map<State, CompletableFuture<Void>> awaitedStates;

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
                        commitTransition(newState);
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
     * Adopts the result of a transition that actually changed state: makes it current, disarms
     * every pending timeout (a state change is exactly what timeouts wait for) and releases
     * anything blocked on {@link #stateReached(State)} for the new state. Only ever called with a
     * genuine new state; see {@link Transition#transition(Event)} for when there isn't one.
     *
     * <p>The caller must already hold this machine's monitor.
     *
     * @param newState the state the machine has just moved into.
     */
    private void commitTransition(State newState) {
        state = newState;
        for (var timeout : timeouts) {
            timeout.cancel();
        }
        timeouts.clear();
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
     * @param millis the time in milliseconds to wait before changing states.
     * @param to the new state to go to.
     * @param action the action to fire when the timeout occurs.
     */
    public synchronized void setTimeout(long millis, State to, TransitionAction action) {
        LOG.debug("Setting up timeout for {}ms into {}", millis, to.getName());
        UpdateStateTask task = new UpdateStateTask(to, action);
        timeouts.add(task);
        timeoutTimer.schedule(task, millis);
    }

    /**
     * Stops the machine's time-based scheduling: cancels every pending timeout and shuts down the
     * timer thread, so no scheduled transition can fire after this returns. Intended for the
     * shutdown path — it is terminal, so {@link #setTimeout(long, State)} must not be called again
     * afterwards (the underlying timer is dead). Event-driven transitions via {@link
     * #receiveEvent(Event)} are unaffected. Idempotent: calling it more than once is safe.
     */
    public synchronized void cancel() {
        for (var timeout : timeouts) {
            timeout.cancel();
        }
        timeouts.clear();
        timeoutTimer.cancel();
    }

    private class UpdateStateTask extends TimerTask {
        /** Transition to fire when the timeout finishes. */
        private final Transition transition;

        UpdateStateTask(State to, TransitionAction action) {
            // Wrap state in a transition so that entry and exit hooks are performed correctly.
            // The captured `state` is only a valid `from` while this task is still pending, which
            // run() re-checks under the monitor before doing anything with it.
            this.transition = new Transition(state, to, action, null);
        }

        @Override
        public void run() {
            // Synchronize with receiveEvent by using the outer class instance as monitor.
            synchronized (StateMachine.this) {
                // TimerTask.cancel() cannot stop a task the timer thread has already dequeued: it
                // runs anyway and blocks here until the thread that cancelled it releases the
                // monitor. Membership of `timeouts` settles whether that happened, because every
                // commit clears the list. Without this, a cancelled timeout would commit a
                // transition out of a `from` state the machine has already left.
                // Removing it here also keeps `timeouts` meaning "pending": this task has now run.
                // Note this only covers cancellation. It is checked before the action runs, so an
                // action that moves the machine itself (by calling receiveEvent) still leaves the
                // captured `from` stale.
                if (!timeouts.remove(this)) {
                    LOG.debug("Timeout fired after being cancelled, ignoring");
                    return;
                }
                // No need to check guard and no actual event that triggered this.
                State newState;
                try {
                    newState = transition.transition(null);
                } catch (RuntimeException e) {
                    // Letting this escape would kill the timer thread, and every later setTimeout
                    // would then throw IllegalStateException. The thrower may be the action or
                    // either state's hooks, so do not name one; and if it was `entry()`, `exit()`
                    // has already run, leaving the machine inconsistent rather than untouched.
                    // The stack trace is the only reliable guide to which.
                    LOG.error(
                            "Timeout transition out of {} threw; state left as {}, which may be"
                                    + " inconsistent if exit or entry hooks had already run",
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
                commitTransition(newState);
            }
        }
    }
}
