package io.adzubla.blocks.error.handler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the entries of the {@code violations} array shared by the validation handlers.
 *
 * <p>{@code invalidValue} follows one convention across the library: present with the value as a
 * string when known, JSON {@code null} when the rejected value was {@code null}, and absent when
 * there is no single rejected value (e.g. an object-level constraint).
 */
final class Violations {

    private Violations() {
    }

    static Map<String, String> of(String path, Object invalidValue, String message) {
        var violation = new LinkedHashMap<String, String>();
        violation.put("path", path);
        violation.put("invalidValue", invalidValue != null ? String.valueOf(invalidValue) : null);
        violation.put("message", message != null ? message : "");
        return violation;
    }

    static Map<String, String> withoutValue(String path, String message) {
        var violation = new LinkedHashMap<String, String>();
        violation.put("path", path);
        violation.put("message", message != null ? message : "");
        return violation;
    }
}
