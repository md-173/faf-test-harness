package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.LaunchIdentity;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.time.Duration;

class DummyGameLauncher extends MockGameLauncher {
    private final boolean throwException;
    private final ProcessBuilder builder;
    private SubprocessManager subprocess;
    private LaunchIdentity identity;

    // #211: HOSTING/JOINING/STARTING_GAME now drive to TERMINATED on GameExited, so a launcher
    // whose default subprocess exits on its own would race that transition against whatever state
    // a test is asserting on. "sort" with no arguments blocks on stdin EOF on both Windows and
    // POSIX (the GameEndReportingTest HANGING_PROCESS pattern), keeping the process alive for the
    // test's duration unless a test explicitly supplies its own (quick-exiting) builder.
    //
    // #303 asked whether that default should be a stub that exits promptly instead, on the grounds
    // that blocking forever is a trap for the next person. It stays as it is, and the card's
    // premise does not hold on #211's terms: a promptly-exiting stub completes gameExit and posts
    // GameExited, which #211 made a TERMINATED edge from STARTING_GAME, HOSTING and JOINING — so it
    // would silently race the state assertions in happyPath and disconnection rather than fail
    // loudly.
    //
    // A portable alternative does exist, and #302 names it: a small Java stub re-invoked through
    // the current JRE, the pattern shared's TestSupport.forMain already uses. It is not reachable
    // from here — mock-client declares only implementation project(':shared') with no dependency on
    // shared's test output, so TestSupport and TestChild are not on this source set's test
    // classpath without new test-fixtures wiring — and a re-invoked JVM per stub costs roughly
    // 100-200ms against sort's ~1ms, twice per launching test. That, not the absence of an option,
    // is why sort stays. What changed instead is where the obligation lives. A test that spawns one
    // of these owns it until it drives the lifecycle
    // to TERMINATED — SessionTeardown is what actually reaps it — and LifecycleTest now asserts in
    // @AfterEach that nothing it launched is still alive, so the next leak fails in the test that
    // caused it rather than surfacing as a stray SIGTERM warning under a later Gradle task.
    DummyGameLauncher(MockClientConfig config) {
        this(config, false, new ProcessBuilder("sort"));
    }

    DummyGameLauncher(MockClientConfig config, boolean throwException) {
        this(config, throwException, new ProcessBuilder("sort"));
    }

    DummyGameLauncher(MockClientConfig config, boolean throwException, ProcessBuilder builder) {
        super(config);
        this.throwException = throwException;
        this.builder = builder;
    }

    @Override
    public SubprocessManager start(LaunchIdentity launchIdentity) throws MockGameLaunchException {
        this.identity = launchIdentity;
        return start();
    }

    @Override
    public SubprocessManager start() throws MockGameLaunchException {
        if (throwException) {
            throw new MockGameLaunchException("Mock Game Launch failed");
        }
        try {
            subprocess =
                    SubprocessManager.start(builder, "DUMMY SUBPROCESS", Duration.ofSeconds(5));
            return subprocess;
        } catch (IOException e) {
            throw new MockGameLaunchException(e.getMessage());
        }
    }

    public SubprocessManager getSubprocess() {
        return subprocess;
    }

    public boolean subprocessStarted() {
        return subprocess != null;
    }

    /** The identity the lifecycle launched under, or null if only the diagnostic path was used. */
    public LaunchIdentity getIdentity() {
        return identity;
    }
}
