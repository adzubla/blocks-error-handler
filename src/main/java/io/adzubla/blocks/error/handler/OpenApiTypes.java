package io.adzubla.blocks.error.handler;

import org.springframework.beans.BeanUtils;

import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Names a Java type the way an OpenAPI schema would, for the {@code expectedType} of an error
 * response: the type, followed by the format in parentheses when there is one
 * ({@code integer (int32)}, {@code string (date)}).
 *
 * <p>A client knows the API by its contract, not by the classes behind it, so a Java class name
 * ({@code BigDecimal}, {@code ProductRequest}) means nothing to it and exposes an implementation
 * detail. An enum is a {@code string}; its accepted values are reported separately.
 */
final class OpenApiTypes {

    static final String ARRAY = "array";
    static final String OBJECT = "object";

    private OpenApiTypes() {
    }

    /**
     * The OpenAPI name of {@code type}, or {@code null} when the type is unknown.
     */
    static String of(Class<?> type) {
        if (type == null) {
            return null;
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class) {
            return "integer (int32)";
        }
        if (type == long.class || type == Long.class) {
            return "integer (int64)";
        }
        if (type == byte.class || type == Byte.class || type == short.class || type == Short.class
                || BigInteger.class.isAssignableFrom(type)) {
            return "integer";
        }
        if (type == float.class || type == Float.class) {
            return "number (float)";
        }
        if (type == double.class || type == Double.class) {
            return "number (double)";
        }
        if (Number.class.isAssignableFrom(type)) {
            return "number";
        }
        if (type == LocalDate.class) {
            return "string (date)";
        }
        if (type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class
                || type == LocalDateTime.class || Date.class.isAssignableFrom(type)
                || Calendar.class.isAssignableFrom(type)) {
            return "string (date-time)";
        }
        if (type == UUID.class) {
            return "string (uuid)";
        }
        if (type == URI.class || type == URL.class) {
            return "string (uri)";
        }
        if (type == byte[].class) {
            return "string (byte)";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return ARRAY;
        }
        if (Map.class.isAssignableFrom(type)) {
            return OBJECT;
        }
        // Enums, strings, characters, and the other values written as a JSON string (a time, a
        // duration, a locale, a currency...).
        if (type == char.class || TemporalAmount.class.isAssignableFrom(type) || BeanUtils.isSimpleValueType(type)) {
            return "string";
        }
        return OBJECT;
    }
}
