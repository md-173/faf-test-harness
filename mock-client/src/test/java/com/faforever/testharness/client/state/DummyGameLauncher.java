package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.time.Duration;

class DummyGameLauncher extends MockGameLauncher {
    private boolean subprocessStarted = false;
    private final boolean throwException;

    DummyGameLauncher(MockClientConfig config) {
        this(config, false);
    }

    DummyGameLauncher(MockClientConfig config, boolean throwException) {
        super(config);
        this.throwException = throwException;
    }

    @Override
    public SubprocessManager start() throws MockGameLaunchException {
        subprocessStarted = true;
        if (throwException) {
            throw new MockGameLaunchException("Mock Game Launch failed");
        }
        try {
            return SubprocessManager.start(
                    new ProcessBuilder("echo"), "DUMMY SUBPROCESS", Duration.ofSeconds(5));
        } catch (IOException e) {
            throw new MockGameLaunchException(e.getMessage());
        }
    }

    public boolean subprocessStarted() {
        return subprocessStarted;
    }
}
