package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.client.process.LaunchIdentity;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.time.Duration;

class DummyIceLauncher extends IceAdapterLauncher {
    private final boolean throwException;
    private final ProcessBuilder builder;
    private SubprocessManager subprocess;
    private LaunchIdentity identity;

    // #211: see DummyGameLauncher's matching constructors for why the default builder must not
    // exit on its own.
    DummyIceLauncher(MockClientConfig config) {
        this(config, false, new ProcessBuilder("sort"));
    }

    DummyIceLauncher(MockClientConfig config, boolean throwException) {
        this(config, throwException, new ProcessBuilder("sort"));
    }

    DummyIceLauncher(MockClientConfig config, boolean throwException, ProcessBuilder builder) {
        super(config);
        this.throwException = throwException;
        this.builder = builder;
    }

    @Override
    public SubprocessManager start(LaunchIdentity launchIdentity) throws IceAdapterLaunchException {
        this.identity = launchIdentity;
        return start();
    }

    @Override
    public SubprocessManager start() throws IceAdapterLaunchException {
        if (throwException) {
            throw new IceAdapterLaunchException("Ice Adapter Launch failed");
        }
        try {
            subprocess =
                    SubprocessManager.start(builder, "DUMMY SUBPROCESS", Duration.ofSeconds(5));
            return subprocess;
        } catch (IOException e) {
            throw new IceAdapterLaunchException(e.getMessage());
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
