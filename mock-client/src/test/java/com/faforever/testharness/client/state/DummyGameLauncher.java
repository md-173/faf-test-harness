package com.faforever.testharness.client.state;

import com.faforever.testharness.client.config.MockClientConfig;
import com.faforever.testharness.client.process.MockGameLaunchException;
import com.faforever.testharness.client.process.MockGameLauncher;
import com.faforever.testharness.shared.process.SubprocessManager;
import java.io.IOException;
import java.time.Duration;

class DummyGameLauncher extends MockGameLauncher {
    private final boolean throwException;
    private final ProcessBuilder builder;
    private SubprocessManager subprocess;

    DummyGameLauncher(MockClientConfig config) {
        this(config, false, new ProcessBuilder("echo"));
    }

    DummyGameLauncher(MockClientConfig config, boolean throwException) {
        this(config, throwException, new ProcessBuilder("echo"));
    }

    DummyGameLauncher(MockClientConfig config, boolean throwException, ProcessBuilder builder) {
        super(config);
        this.throwException = throwException;
        this.builder = builder;
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
}
