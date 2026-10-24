package com.faforever.testharness.client.config;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The leaf components of {@link MockClientConfig} under their flat names, which are the names of
 * the options that set them and the config-file keys. A leaf of a nested group is named by the
 * group's prefix and its own name, so {@code oauth().tokenUrl()} is {@code oauthTokenUrl}; a
 * top-level component is a leaf under its own name.
 *
 * <p>Shared by the drift guard, which matches these names to the options, and by the tests that
 * check a copy of a config moves no value to the wrong component.
 */
public final class ConfigLeaves {

    /** Each group component, with the prefix its leaves drop from their flat names. */
    private static final Map<String, String> GROUP_PREFIXES =
            Map.of(
                    "oauth", "oauth",
                    "iceAdapter", "iceAdapter",
                    "mockGame", "mockGame",
                    "logging", "log",
                    "faults", "");

    private ConfigLeaves() {}

    /**
     * The flat name of every leaf, in component order.
     *
     * @return the names, as a list so that two leaves sharing a name stay visible
     */
    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : MockClientConfig.class.getRecordComponents()) {
            String prefix = GROUP_PREFIXES.get(component.getName());
            if (prefix == null) {
                names.add(component.getName());
            } else {
                for (RecordComponent leaf : component.getType().getRecordComponents()) {
                    names.add(flatName(prefix, leaf.getName()));
                }
            }
        }
        return names;
    }

    /**
     * Every leaf of {@code config} by flat name, in component order. A value may be {@code null}: a
     * config on the access-token channel has no refresh-token file.
     *
     * @param config the config to read
     * @return each leaf's flat name and value
     * @throws ReflectiveOperationException if an accessor cannot be invoked
     */
    public static Map<String, Object> of(final MockClientConfig config)
            throws ReflectiveOperationException {
        Map<String, Object> leaves = new LinkedHashMap<>();
        for (RecordComponent component : MockClientConfig.class.getRecordComponents()) {
            Object value = component.getAccessor().invoke(config);
            String prefix = GROUP_PREFIXES.get(component.getName());
            if (prefix == null) {
                leaves.put(component.getName(), value);
            } else {
                for (RecordComponent leaf : component.getType().getRecordComponents()) {
                    leaves.put(flatName(prefix, leaf.getName()), leaf.getAccessor().invoke(value));
                }
            }
        }
        return leaves;
    }

    private static String flatName(final String prefix, final String leaf) {
        return prefix.isEmpty()
                ? leaf
                : prefix + Character.toUpperCase(leaf.charAt(0)) + leaf.substring(1);
    }
}
