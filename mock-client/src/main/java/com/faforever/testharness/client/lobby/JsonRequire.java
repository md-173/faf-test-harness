package com.faforever.testharness.client.lobby;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Static helpers for pulling required fields off a {@link JsonNode}. Each accessor throws {@link
 * IllegalArgumentException} when the field is missing, null, or of the wrong JSON type — turning
 * "silent default" classes of bug (e.g. a missing {@code session} field decoding to {@code 0} and
 * being echoed back to the server) into immediate, observable failures.
 *
 * <p>Intended for consumers that read a small number of fields off an inbound lobby frame directly,
 * rather than decoding the frame into a typed record. Consumers handling large or nested payloads
 * should prefer a typed record and let Jackson's record-aware deserialisation do the same
 * validation in one call.
 */
public final class JsonRequire {

    private JsonRequire() {}

    /**
     * Read a required string field. Empty strings are accepted; if you also need non-blank,
     * post-check the result.
     *
     * @param node the parent JSON object
     * @param field the field name
     * @return the field's text value
     * @throws IllegalArgumentException if the field is missing or not a JSON string
     */
    public static String stringField(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw new IllegalArgumentException(field + " is required (string)");
        }
        return value.asText();
    }

    /**
     * Read a required integer field that fits in a {@code long}.
     *
     * @param node the parent JSON object
     * @param field the field name
     * @return the field's value as a {@code long}
     * @throws IllegalArgumentException if the field is missing or not a JSON integer
     */
    public static long longField(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " is required (integer)");
        }
        return value.asLong();
    }

    /**
     * Read a required integer field that fits in an {@code int}.
     *
     * @param node the parent JSON object
     * @param field the field name
     * @return the field's value as an {@code int}
     * @throws IllegalArgumentException if the field is missing or not a JSON integer
     */
    public static int intField(final JsonNode node, final String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " is required (integer)");
        }
        return value.asInt();
    }
}
