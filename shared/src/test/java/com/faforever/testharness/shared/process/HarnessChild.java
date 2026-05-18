package com.faforever.testharness.shared.process;

import java.time.Duration;

/**
 * Fixture main class for {@link SubprocessManagerShutdownTest}. Spawns a long-running grandchild
 * through {@link SubprocessManager} (so the JVM shutdown hook is installed), prints the
 * grandchild's PID, and parks until killed.
 */
public final class HarnessChild {

    private HarnessChild() {}

    public static void main(String[] args) throws Exception {
        SubprocessManager m =
                SubprocessManager.start(
                        TestSupport.testChild("sleep", "60000"),
                        "Grandchild",
                        Duration.ofSeconds(2));
        System.out.println("GRANDCHILD_PID=" + m.pid());
        System.out.flush();
        Thread.sleep(Duration.ofSeconds(60).toMillis());
    }
}
