package com.example.demo.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Translates Jackson 3 deserialization failures into RFC 9457
 * {@link org.springframework.http.ProblemDetail} responses with HTTP 400.
 *
 * <p>Intercepts {@link org.springframework.http.converter.HttpMessageNotReadableException} and
 * inspects its cause chain to produce a structured response with field path, rejected value,
 * expected type, and source location where applicable:
 *
 * <ul>
 *   <li>{@code UnrecognizedPropertyException} → {@code UNKNOWN_JSON_FIELD} (known fields listed)
 *   <li>{@code InvalidFormatException} on an enum → {@code INVALID_ENUM_VALUE} (valid values listed)
 *   <li>{@code InvalidFormatException} on a scalar → {@code INVALID_FIELD_VALUE}
 *   <li>{@code MismatchedInputException} → {@code TYPE_MISMATCH}
 *   <li>{@code InputCoercionException} → {@code INTEGER_OVERFLOW} (valid range included)
 *   <li>{@code StreamReadException} → {@code MALFORMED_JSON} (line/column included)
 *   <li>Anything else → {@code MALFORMED_REQUEST_BODY}
 * </ul>
 *
 * <p><b>Jackson 3 note:</b> uses the {@code tools.jackson.*} package namespace shipped with
 * Spring Boot 4, not the legacy {@code com.fasterxml.jackson.*} packages.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JsonExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonExceptionHandler.class);

    private final MessageSource messageSource;

    private static final Map<Class<?>, String> NUMERIC_RANGES = Map.of(
            byte.class,    "[" + Byte.MIN_VALUE    + ", " + Byte.MAX_VALUE    + "]",
            Byte.class,    "[" + Byte.MIN_VALUE    + ", " + Byte.MAX_VALUE    + "]",
            short.class,   "[" + Short.MIN_VALUE   + ", " + Short.MAX_VALUE   + "]",
            Short.class,   "[" + Short.MIN_VALUE   + ", " + Short.MAX_VALUE   + "]",
            int.class,     "[" + Integer.MIN_VALUE + ", " + Integer.MAX_VALUE + "]",
            Integer.class, "[" + Integer.MIN_VALUE + ", " + Integer.MAX_VALUE + "]",
            long.class,    "[" + Long.MIN_VALUE    + ", " + Long.MAX_VALUE    + "]",
            Long.class,    "[" + Long.MIN_VALUE    + ", " + Long.MAX_VALUE    + "]"
    );

    public JsonExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    // Common shape for every 400 response from this handler.
    // Only non-null fields are written to the ProblemDetail.
    private record JsonProblem(
            String title, String detail,
            String path, Object invalidValue,
            String expectedType, List<String> validValues,
            String validRange, Integer line, Integer column,
            ErrorCode code) {

        ProblemDetail toProblemDetail() {
            var p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
            p.setTitle(title);
            p.setProperty("code", code.name());
            if (path != null) p.setProperty("path", path);
            if (invalidValue != null) p.setProperty("invalidValue", String.valueOf(invalidValue));
            if (expectedType != null) p.setProperty("expectedType", expectedType);
            if (validValues != null) p.setProperty("validValues", validValues);
            if (validRange != null) p.setProperty("validRange", validRange);
            if (line != null) p.setProperty("line", line);
            if (column != null) p.setProperty("column", column);
            return p;
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handle(HttpMessageNotReadableException ex) {
        var cause = ex.getCause();

        if (cause instanceof UnrecognizedPropertyException upe) {
            log.debug("Unknown JSON field: {}", upe.getPropertyName());
            log.trace("Stack trace:", upe);
            return badRequest(unrecognizedProperty(upe).toProblemDetail());
        }
        if (cause instanceof InvalidFormatException ife) {
            log.debug("Invalid JSON format at path {}: {}", jsonPath(ife.getPath()), ife.getMessage());
            log.trace("Stack trace:", ife);
            return badRequest(invalidFormat(ife).toProblemDetail());
        }
        if (cause instanceof MismatchedInputException mie) {
            log.debug("Mismatched JSON input at path {}: {}", jsonPath(mie.getPath()), mie.getMessage());
            log.trace("Stack trace:", mie);
            return badRequest(mismatchedInput(mie).toProblemDetail());
        }
        if (cause instanceof InputCoercionException ice) {
            log.debug("Integer overflow: {}", ice.getMessage());
            log.trace("Stack trace:", ice);
            return badRequest(coercionError(ice).toProblemDetail());
        }
        if (cause instanceof StreamReadException sre) {
            log.debug("JSON parse error: {}", sre.getMessage());
            log.trace("Stack trace:", sre);
            return badRequest(parseError(sre).toProblemDetail());
        }

        log.warn("Unreadable request body", ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg("error.malformed-body.detail"));
        problem.setTitle(msg("error.malformed-body.title"));
        problem.setProperty("code", ErrorCode.MALFORMED_REQUEST_BODY.name());
        return badRequest(problem);
    }

    private JsonProblem parseError(StreamReadException ex) {
        var loc = ex.getLocation();
        Integer line = loc != null ? loc.getLineNr() : null;
        Integer column = loc != null ? loc.getColumnNr() : null;
        String detail = loc != null
                ? msg("error.malformed-json.detail.location", line, column)
                : msg("error.malformed-json.detail");
        return new JsonProblem(msg("error.malformed-json.title"), detail,
                null, null, null, null, null, line, column, ErrorCode.MALFORMED_JSON);
    }

    private JsonProblem coercionError(InputCoercionException ex) {
        var loc = ex.getLocation();
        var targetType = ex.getTargetType();
        var typeName = targetType != null ? targetType.getSimpleName() : "?";
        var range = NUMERIC_RANGES.get(targetType);
        Integer line = loc != null ? loc.getLineNr() : null;
        Integer column = loc != null ? loc.getColumnNr() : null;
        String detail;
        if (range != null && loc != null) {
            detail = msg("error.integer-overflow.detail.range-location", typeName, range, line, column);
        } else if (range != null) {
            detail = msg("error.integer-overflow.detail.range", typeName, range);
        } else if (loc != null) {
            detail = msg("error.integer-overflow.detail.location", typeName, line, column);
        } else {
            detail = msg("error.integer-overflow.detail", typeName);
        }
        return new JsonProblem(msg("error.integer-overflow.title"), detail,
                null, null, typeName, null, range, line, column, ErrorCode.INTEGER_OVERFLOW);
    }

    private JsonProblem unrecognizedProperty(UnrecognizedPropertyException ex) {
        var known = ex.getKnownPropertyIds();
        List<String> validValues = known != null
                ? known.stream().map(Object::toString).toList()
                : null;
        String detail = (known != null && !known.isEmpty())
                ? msg("error.unknown-field.detail.with-known", ex.getPropertyName(), known)
                : msg("error.unknown-field.detail", ex.getPropertyName());
        return new JsonProblem(msg("error.unknown-field.title"), detail,
                jsonPath(ex.getPath()), null, null, validValues, null, null, null,
                ErrorCode.UNKNOWN_JSON_FIELD);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private JsonProblem invalidFormat(InvalidFormatException ex) {
        var path = jsonPath(ex.getPath());
        var targetType = ex.getTargetType();

        if (targetType != null && targetType.isEnum()) {
            var validValues = Arrays.stream(((Class<? extends Enum>) targetType).getEnumConstants())
                    .map(Enum::name)
                    .toList();
            var detail = msg("error.invalid-enum.detail",
                    ex.getValue(), targetType.getSimpleName(), path, validValues);
            return new JsonProblem(msg("error.invalid-enum.title"), detail,
                    path, ex.getValue(), targetType.getSimpleName(), validValues, null, null, null,
                    ErrorCode.INVALID_ENUM_VALUE);
        }

        var typeName = targetType != null ? targetType.getSimpleName() : "?";
        var detail = msg("error.invalid-value.detail", ex.getValue(), typeName, path);
        return new JsonProblem(msg("error.invalid-value.title"), detail,
                path, ex.getValue(), typeName, null, null, null, null,
                ErrorCode.INVALID_FIELD_VALUE);
    }

    private JsonProblem mismatchedInput(MismatchedInputException ex) {
        var path = jsonPath(ex.getPath());
        var targetType = ex.getTargetType();
        var detail = msg("error.type-mismatch.detail", path, friendlyTypeName(targetType));
        var typeName = targetType != null ? targetType.getSimpleName() : null;
        return new JsonProblem(msg("error.type-mismatch.title"), detail,
                path, null, typeName, null, null, null, null, ErrorCode.TYPE_MISMATCH);
    }

    private static String jsonPath(List<JacksonException.Reference> refs) {
        if (refs == null || refs.isEmpty()) return "$";
        var sb = new StringBuilder("$");
        for (var ref : refs) {
            if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            } else if (ref.getPropertyName() != null) {
                sb.append('.').append(ref.getPropertyName());
            }
        }
        return sb.toString();
    }

    private String friendlyTypeName(Class<?> type) {
        if (type == null) return msg("error.friendly-type.compatible");
        if (List.class.isAssignableFrom(type) || type.isArray()) return msg("error.friendly-type.array");
        if (Map.class.isAssignableFrom(type)) return msg("error.friendly-type.object");
        return type.getSimpleName();
    }

    private static ResponseEntity<ProblemDetail> badRequest(ProblemDetail problem) {
        return ResponseEntity.badRequest().body(problem);
    }
}
