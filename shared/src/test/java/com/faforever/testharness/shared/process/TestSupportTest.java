package com.faforever.testharness.shared.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The child JVMs {@link TestSupport} builds write only what the child writes (#516). A JVM that
 * sees {@code JAVA_TOOL_OPTIONS} or one of its siblings prints a {@code Picked up …} line on stderr
 * first, which broke {@code ProcessOutputLoggerLineObserverTest} on any machine that sets one.
 */
final class TestSupportTest {

    /**
     * The strip removes all three variables and nothing else. Checked on a map that holds them,
     * because the test worker's own environment usually does not, which would make an assertion on
     * a real child's environment pass whether or not anything was removed.
     */
    @Test
    void theJvmOptionVariablesAreRemovedAndNothingElse() {
        Map<String, String> environment = new HashMap<>();
        environment.put("JAVA_TOOL_OPTIONS", "-Dfaf.probe=1");
        environment.put("JDK_JAVA_OPTIONS", "-Dfaf.probe=2");
        environment.put("_JAVA_OPTIONS", "-Dfaf.probe=3");
        environment.put("LOG_FILE", "kept.jsonl");

        TestSupport.withoutJvmOptionVariables(environment);

        assertEquals(Map.of("LOG_FILE", "kept.jsonl"), environment);
    }

    /**
     * {@link TestSupport#forMain} applies the strip. Only conclusive where the test worker has one
     * of the variables set; the map test above covers the strip itself everywhere.
     */
    @Test
    void aChildBuiltByForMainCarriesNoneOfThem() {
        Map<String, String> environment = TestSupport.testChild("exit", "0").environment();

        for (String name : TestSupport.JVM_OPTION_VARIABLES) {
            assertFalse(environment.containsKey(name), name + " reached the child's environment");
        }
    }
}
