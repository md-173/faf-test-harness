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
                    LOG.debug(
                            "Transition from {} to {} caused by {} successful",
                            state.getName(),
                            newState.getName(),
                            event);
                    state = newState;
                    for (var timeout : timeouts) {
                        timeout.cancel();
                    }
                    CompletableFuture<Void> alert = awaitedStates.remove(state);
                    if (alert != null) {
                        alert.complete(null);
                    }
                    timeouts.clear();
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
     * Sets up a timeout that will cause a transition to state {@code to} if no other transition
     * after {@code millis} elapses.
     *
     * @param millis the time in milliseconds to wait before changing states.
     * @param to the new state to go to.
     */
    public synchronized void setTimeout(long millis, State to) {
        LOG.debug("Setting up timeout for {}ms into {}", millis, to.getName());
        UpdateStateTask task = new UpdateStateTask(to);
        timeouts.add(task);
        timeoutTimer.schedule(task, millis);
    }

    private class UpdateStateTask extends TimerTask {
        /** Transition to fire when the timeout finishes. */
        private final Transition transition;

        UpdateStateTask(State to) {
            // Wrap state in a transition so that entry and exit hooks are performed correctly.
            // Safe to give current `state` as `from` parameter as the task will be cancelled if
            // state changes.
            this.transition = new Transition(state, to, null, null);
        }

        @Override
        public void run() {
            // Synchronize with receiveEvent by using the outer class instance as monitor.
            synchronized (StateMachine.this) {
                // No need to check guard and no actual event that triggered this.
                state = transition.transition(null);
                LOG.debug("Timeout fired, new state is {}", state.getName());
                // State transition occured, any other timeouts are cancelled.
                for (var timeout : timeouts) {
                    timeout.cancel();
                }
                timeouts.clear();
            }
        }
    }
}
