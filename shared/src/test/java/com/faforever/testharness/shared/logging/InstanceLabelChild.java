package com.faforever.testharness.shared.logging;

import org.slf4j.LoggerFactory;

/**
 * Child program for {@link InstanceLabelEndToEndTest}. Configures logging exactly as a real
 * component's {@code Main} does, emits one record, then shuts logging down so the JSONL file is
 * flushed and closed before the JVM exits.
 *
 * <p>It exists because a process cannot set its own environment variables in Java. Proving that
 * {@code INSTANCE_NAME} reaches the label a harness reads therefore requires a real child process
 * launched with that variable set.
 */
public final class InstanceLabelChild {

    private InstanceLabelChild() {}

    /**
     * Entry point.
     *
     * @param args first element is the component name to configure
     */
    public static void main(final String[] args) {
        LoggingSetup.configure(args[0]);
        LoggerFactory.getLogger(InstanceLabelChild.class).info("state entry: CONNECTING");
        LoggingSetup.shutdown();
    }
}
