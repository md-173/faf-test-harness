package com.faforever.testharness.shared.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter for the {@code %component} conversion word.
 *
 * <p>Resolves the component label for a log event in the following order:
 *
 * <ol>
 *   <li>the event's MDC map (thread-local, set by {@link LoggingSetup#configure})
 *   <li>the {@code LoggerContext} property map (JVM-wide, also set by {@link
 *       LoggingSetup#configure} so async worker threads that never saw the MDC still render
 *       correctly)
 *   <li>the literal {@value #UNKNOWN} as a last-resort fallback
 * </ol>
 *
 * <p>Each harness process runs exactly one component, so the {@code LoggerContext} value is always
 * the right answer when the MDC is empty.
 *
 * <p>Registered in {@code logback.xml} via:
 *
 * <pre>{@code
 * <conversionRule conversionWord="component"
 *     converterClass="com.faforever.testharness.shared.logging.ComponentConverter"/>
 * }</pre>
 */
public final class ComponentConverter extends ClassicConverter {

    /** Value returned when neither the MDC nor the LoggerContext carries a component name. */
    public static final String UNKNOWN = "Unknown";

    /**
     * Resolves the component label for an event, used by both this converter and {@link
     * JsonLineEncoder} so console and JSONL output never disagree.
     *
     * @param event the Logback event
     * @return the component name, or {@value #UNKNOWN} if none is set
     */
    public static String resolve(final ILoggingEvent event) {
        String fromMdc = event.getMDCPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY);
        if (fromMdc != null && !fromMdc.isEmpty()) {
            return fromMdc;
        }
        String fromContext =
                event.getLoggerContextVO().getPropertyMap().get(LoggingSetup.COMPONENT_MDC_KEY);
        if (fromContext != null && !fromContext.isEmpty()) {
            return fromContext;
        }
        return UNKNOWN;
    }

    @Override
    public String convert(final ILoggingEvent event) {
        return resolve(event);
    }
}
