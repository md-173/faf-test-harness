package com.faforever.testharness.shared.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fixture main class for {@link SubprocessManagerShutdownTest}. Spawns a long-running grandchild
 * through {@link SubprocessManager} (so the JVM shutdown hook is installed), prints the
 * grandchild's PID once the grandchild is running, and parks until killed. Given {@code pdeathsig},
 * it launches the grandchild behind {@link ParentDeathSignal#prefix()}, as the launchers launch
 * theirs.
 */
public final class HarnessChild {

    private HarnessChild() {}

    public static void main(String[] args) throws Exception {
        List<String> command = new ArrayList<>();
        if (args.length > 0 && args[0].equals("pdeathsig")) {
            // This JVM's own probe decides, and the test's cannot vouch for it.
            if (ParentDeathSignal.prefix().isEmpty()) {
                throw new IllegalStateException("setpriv --pdeathsig is not usable in this JVM");
            }
            command.addAll(ParentDeathSignal.prefix());
        }
        command.addAll(TestSupport.testChild("hook", "ready", "bye").command());
        LineWaiter ready = new LineWaiter();
        SubprocessManager m =
                SubprocessManager.start(
                        new ProcessBuilder(command), "Grandchild", Duration.ofSeconds(2), ready);
        // Reported only once it runs, so setpriv has set the signal before any kill can land.
        ready.awaitLine("ready"::equals, Duration.ofSeconds(30));
        System.out.println("GRANDCHILD_PID=" + m.pid());
        System.out.flush();
        Thread.sleep(Duration.ofSeconds(60).toMillis());
    }
}
