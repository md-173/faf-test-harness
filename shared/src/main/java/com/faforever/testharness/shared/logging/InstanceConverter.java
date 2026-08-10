package com.faforever.testharness.shared.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter for the {@code %instance} conversion word.
 *
 * <p>Resolves the instance label from the event's MDC map first, then from the {@code
 * LoggerContext} property map, then falls back to the empty string. Both sources are set by {@link
 * LoggingSetup#configure}. The context property covers async worker threads and subprocess capture
 * threads, which never inherit the thread-local MDC.
 *
 * <p>{@link ComponentConverter} has a literal fallback label but this converter does not. A
 * single-instance run has no instance name and renders nothing, so its output stays identical to
 * what it produced before WBS-3.1.6.2.
 *
 * <p>Registered in {@code logback.xml} via:
 *
 * <pre>{@code
 * <conversionRule conversionWord="instance"
 *     converterClass="com.faforever.testharness.shared.logging.InstanceConverter"/>
 * }</pre>
 */
public final class InstanceConverter extends ClassicConverter {

    /**
     * Resolves the instance label for an event. Shared with {@link JsonLineEncoder} so console and
     * JSONL output never disagree.
     *
     * @param event the Logback event
     * @return the instance name, or an empty string if none is set
     */
    public static String resolve(final ILoggingEvent event) {
        String fromMdc = event.getMDCPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY);
        if (fromMdc != null && !fromMdc.isEmpty()) {
            return fromMdc;
        }
        String fromContext =
                event.getLoggerContextVO().getPropertyMap().get(LoggingSetup.INSTANCE_MDC_KEY);
        if (fromContext != null && !fromContext.isEmpty()) {
            return fromContext;
        }
        return "";
    }

    /**
     * Renders the console segment for the instance label. The brackets and leading space live here
     * rather than in the pattern so an unnamed instance contributes nothing instead of an empty
     * pair of brackets.
     *
     * @param event the Logback event
     * @return the bracketed label preceded by a space, or an empty string if no instance is set
     */
    @Override
    public String convert(final ILoggingEvent event) {
        String instance = resolve(event);
        return instance.isEmpty() ? "" : " [" + instance + "]";
    }
}
