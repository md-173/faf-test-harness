package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.IceAdapterLaunchException;
import com.faforever.testharness.client.process.IceAdapterLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.time.Duration;

class DummyIceLauncher extends IceAdapterLauncher {
    private boolean subprocessStarted = false;
    private final boolean throwException;

    DummyIceLauncher(MockClientConfig config) {
        this(config, false);
    }

    DummyIceLauncher(MockClientConfig config, boolean throwException) {
        super(config);
        this.throwException = throwException;
    }

    @Override
    public SubprocessManager start() throws IceAdapterLaunchException {
        subprocessStarted = true;
        if (throwException) {
            throw new IceAdapterLaunchException("Ice Adapter Launch failed");
        }
        try {
            return SubprocessManager.start(
                    new ProcessBuilder("echo"), "DUMMY SUBPROCESS", Duration.ofSeconds(5));
        } catch (IOException e) {
            throw new IceAdapterLaunchException(e.getMessage());
        }
    }

    public boolean subprocessStarted() {
        return subprocessStarted;
    }
}
