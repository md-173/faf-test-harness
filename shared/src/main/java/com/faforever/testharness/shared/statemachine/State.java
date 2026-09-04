package com.faforever.testharness.shared.statemachine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Represents a state. */
public class State {
    /** Logger instance for this class. */
    private static final Logger LOG = LoggerFactory.getLogger(State.class);

    /** A unique name for this state. */
    private final String name;

    /** Each type of event drives a set of potential transition. */
    private final HashMap<Class<? extends Event>, List<Transition>> transitions;

    /** A set of hooks to run when this state is entered. */
    private final List<Runnable> entryHooks;

    /** A set of hooks to run when this state is exited. */
    private final List<Runnable> exitHooks;

    /**
     * Initializes the state.
     *
     * @param name the given name of the state.
     */
    public State(String name) {
        this.name = name;
        this.transitions = new HashMap<>();
        this.entryHooks = new ArrayList<>();
        this.exitHooks = new ArrayList<>();
    }

    /**
     * Getter for the state's name.
     *
     * @return the given name.
     */
    public String getName() {
        return name;
    }

    /**
     * Register an action to run when this state is entered.
     *
     * @param action the action to run.
     */
    public void onEntry(Runnable action) {
        entryHooks.add(action);
    }

    /**
     * Register an action to run when this state is exited.
     *
     * @param action the action to run.
     */
    public void onExit(Runnable action) {
        exitHooks.add(action);
    }

    /**
     * Perform all entry actions.
     *
     * <p>Each hook is isolated: one that throws is logged and the rest still run. See {@link
     * #runHooks} for why that is not merely tidiness.
     */
    public void entry() {
        runHooks(entryHooks, "entry");
    }

    /**
     * Perform all exit actions.
     *
     * <p>Each hook is isolated, exactly as in {@link #entry()}.
     */
    public void exit() {
        runHooks(exitHooks, "exit");
    }

    /**
     * Runs every hook in {@code hooks}, containing and logging any that throws.
     *
     * <p>An escaping hook used to abort the transition midway (WBS-2.3.7-fix, #258). {@code
     * Transition.transition} runs {@code from.exit()} then {@code to.entry()} and only then returns
     * the new state, so a throw from either left the machine reporting the state it had already
     * left — with that state's exit hooks run and the target's entry hooks half-run — and the
     * assignment, the timeout cancellation and the {@code stateReached} completion all skipped.
     *
     * <p>Each of the three callers disposed of it differently and none of them repaired it. On the
     * event path the exception propagated into a netty handler or a {@code CompletableFuture}
     * continuation and vanished with no trace. On the timeout path it was caught and logged as "may
     * be inconsistent", which was honest but fixed nothing. On the CLI path it hung the process:
     * the future a subcommand blocks on is completed after the state assignment, so a throwing hook
     * meant the main thread waited forever, the JVM never exited, and the subprocess registry's
     * shutdown hook never ran — leaving a live child orphaned.
     *
     * <p>Isolating here rather than reordering the assignment is deliberate. Reordering would also
     * have worked, and is what the card proposed first, but it changes when entry hooks run
     * relative to the state becoming visible and to pending timeouts being cancelled — and
     * production depends on that ordering, most visibly in the mock game, whose ENDED entry hook is
     * the shutdown sequence. Containment fixes every symptom above and moves nothing. It also
     * matches what this codebase already does where a hook sequence must not be derailed by one
     * step: {@code GameShutdown.run} isolates each of its own steps for the same reason.
     *
     * <p>A hook that throws still did not do its job, and swallowing that silently would be its own
     * defect — hence ERROR, naming the state and the phase, rather than a debug line.
     *
     * @param hooks the hooks to run, in registration order.
     * @param phase {@code "entry"} or {@code "exit"}, for the diagnostic.
     */
    private void runHooks(final List<Runnable> hooks, final String phase) {
        for (var hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOG.error("{} hook for state {} threw; continuing", phase, name, e);
            }
        }
    }

    /**
     * Create a simple transition from this state to {@code other}, any time {@code event} happens.
     * Transitions are tried in the order they were registered and the first one whose guard passes
     * will be the only one to occur.
     *
     * @param event trigger for transition to start.
     * @param other new state to go to.
     */
    public void registerTransition(Class<? extends Event> event, State other) {
        registerTransition(event, other, null, null);
    }

    /**
     * Create a transition from this state to {@code other} any time {@code event} and also {@code
     * guard} evaluates to {@code true}. This transition calls {@code action}. Transitions are tried
     * in the order they were registered and the first one whose guard passes will be the only one
     * to occur.
     *
     * @param event trigger for transition to start.
     * @param other new state to go to.
     * @param action occurs upon succesful transition.
     * @param guard must be true for transition to happen.
     */
    public void registerTransition(
            Class<? extends Event> event,
            State other,
            TransitionAction action,
            Predicate<Event> guard) {
        Transition t = new Transition(this, other, action, guard);
        transitions.computeIfAbsent(event, k -> new ArrayList<>()).add(t);
    }

    /**
     * Obtains the corresponding transition for the event type.
     *
     * @param event the event type
     * @return a list of all matching transitions. Will be empty if no transitions were registered
     *     for this event type.
     */
    public List<Transition> getTransitions(Class<? extends Event> event) {
        return transitions.getOrDefault(event, List.of());
    }
}
