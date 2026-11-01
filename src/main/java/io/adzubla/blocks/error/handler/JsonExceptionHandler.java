package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.MissingInjectableValueException;
import tools.jackson.databind.exc.PropertyBindingException;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.json.JsonMapper;

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
 *   <li>{@code PropertyBindingException} (unrecognized or ignored property) → {@code UNKNOWN_JSON_FIELD}
 *       (known fields listed)
 *   <li>{@code InvalidFormatException} on an enum → {@code INVALID_ENUM_VALUE} (valid values listed)
 *   <li>{@code InvalidFormatException} on a scalar → {@code INVALID_FIELD_VALUE}
 *   <li>{@code InvalidNullException} → {@code NULL_VALUE_NOT_ALLOWED}
 *   <li>{@code InvalidTypeIdException} → {@code INVALID_TYPE_ID}
 *   <li>{@code MismatchedInputException} → {@code TYPE_MISMATCH}
 *   <li>{@code ValueInstantiationException} → {@code INVALID_FIELD_VALUE}
 *   <li>{@code MissingInjectableValueException} → 500 {@code INTERNAL_SERVER_ERROR} (a server-side
 *       mapping defect, not a client error)
 *   <li>{@code InputCoercionException} → {@code INTEGER_OVERFLOW} (valid range included)
 *   <li>{@code StreamReadException} → {@code MALFORMED_JSON} (line/column included)
 *   <li>Anything else → {@code MALFORMED_REQUEST_BODY}
 * </ul>
 *
 * <p>The {@code validValues} of an enum are rendered the way the application's {@link ObjectMapper}
 * serializes each constant, so {@code @JsonProperty}, {@code @JsonValue} and enum-related mapper
 * features are honoured — they are the values the client is expected to send.
 *
 * <p><b>Jackson 3 note:</b> uses the {@code tools.jackson.*} package namespace shipped with
 * Spring Boot 4, not the legacy {@code com.fasterxml.jackson.*} packages.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JsonExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonExceptionHandler.class);

    private final ErrorMessages messages;
    private final ObjectMapper objectMapper;

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

    public JsonExceptionHandler(MessageSource messageSource, ObjectMapper objectMapper) {
        this.messages = new ErrorMessages(messageSource);
        this.objectMapper = objectMapper;
    }

    /**
     * Uses a default {@link JsonMapper} to render enum values, ignoring the application's Jackson
     * customizations; prefer {@link #JsonExceptionHandler(MessageSource, ObjectMapper)}.
     */
    public JsonExceptionHandler(MessageSource messageSource) {
        this(messageSource, JsonMapper.shared());
    }

    private String msg(String key, Object... args) {
        return messages.get(key, args);
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

        if (cause instanceof PropertyBindingException upe) {
            var problem = unrecognizedProperty(upe);
            log.debug("{} {}: Unknown JSON field: {}",
                    HttpStatus.BAD_REQUEST.value(), problem.code(), upe.getPropertyName());
            log.trace("Stack trace:", upe);
            return badRequest(problem.toProblemDetail());
        }
        if (cause instanceof InvalidFormatException ife) {
            var problem = invalidFormat(ife);
            log.debug("{} {}: Invalid JSON format at path {}: {}",
                    HttpStatus.BAD_REQUEST.value(), problem.code(), jsonPath(ife.getPath()), ife.getMessage());
            log.trace("Stack trace:", ife);
            return badRequest(problem.toProblemDetail());
        }
        if (cause instanceof InvalidNullException ine) {
            var problem = invalidNull(ine);
            log.debug("{} {}: Null not allowed at path {}: {}",
                    HttpStatus.BAD_REQUEST.value(), problem.code(), jsonPath(ine.getPath()), ine.getMessage());
            log.trace("Stack trace:", ine);
            return badRequest(problem.toProblemDetail());
        }
        if (cause instanceof InvalidTypeIdException itie) {
            var problem = invalidTypeId(itie);
            log.debug("{} {}: Invalid type id at path {}: {}",
                    HttpStatus.BAD_REQUEST.value(), problem.code(), jsonPath(itie.getPath()), itie.getMessage());
            log.trace("Stack trace:", itie);
            return badRequest(problem.toProblemDetail());
        }
        if (cause instanceof MissingInjectableValueException) {
            // The client sent nothing wrong: the target type declares an injectable value nobody provides.
            // (InvalidDefinitionException never gets here: Spring wraps it in HttpMessageConversionException,
            // which GlobalExceptionHandler answers with the same 500.)
            log.error("{} {}: Unusable JSON mapping for request body",
                    HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL_SERVER_ERROR, cause);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                    msg("error.internal.detail"));
            problem.setTitle(msg("error.internal.title"));
            problem.setProperty("code", ErrorCode.INTERNAL_SERVER_ERROR.name());
            return ResponseEntity.internalServerError().body(problem);
        }
        if (cause instanceof ValueInstantiationException vie) {
            var problem = valueInstantiation(vie);
            log.debug("{} {}: Cannot instantiate value at path {}: {}",
                    HttpStatus.BAD_REQUEST.value(), problem.code(), jsonPath(vie.getPath()), vie.getMessage());
            log.trace("Stack trace:", vie);
            return badRequest(problem.toProblemDetail());
        }
        if (cause instanceof MismatchedInputException mie) {
            var problem = mismatchedInput(mie);
            log.debug("{} {}: Mismatched JSON input at path {}: {}",
                    HttpStatus.BAD_REQUEST.value(), problem.code(), jsonPath(mie.getPath()), mie.getMessage());
            log.trace("Stack trace:", mie);
            return badRequest(problem.toProblemDetail());
        }
        if (cause instanceof InputCoercionException ice) {
            var problem = coercionError(ice);
            log.debug("{} {}: Integer overflow: {}", HttpStatus.BAD_REQUEST.value(), problem.code(), ice.getMessage());
            log.trace("Stack trace:", ice);
            return badRequest(problem.toProblemDetail());
        }
        if (cause instanceof StreamReadException sre) {
            var problem = parseError(sre);
            log.debug("{} {}: JSON parse error: {}", HttpStatus.BAD_REQUEST.value(), problem.code(), sre.getMessage());
            log.trace("Stack trace:", sre);
            return badRequest(problem.toProblemDetail());
        }

        log.debug("{} {}: Unreadable request body: {}",
                HttpStatus.BAD_REQUEST.value(), ErrorCode.MALFORMED_REQUEST_BODY, ex.getMessage());
        log.trace("Stack trace:", ex);
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

    private JsonProblem unrecognizedProperty(PropertyBindingException ex) {
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

    private JsonProblem invalidFormat(InvalidFormatException ex) {
        var path = jsonPath(ex.getPath());
        var targetType = ex.getTargetType();

        if (targetType != null && targetType.isEnum()) {
            var validValues = Arrays.stream(targetType.getEnumConstants())
                    .map(this::jsonValue)
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

    private JsonProblem invalidNull(InvalidNullException ex) {
        var path = jsonPath(ex.getPath());
        return new JsonProblem(msg("error.null-value.title"), msg("error.null-value.detail", path),
                path, null, null, null, null, null, null, ErrorCode.NULL_VALUE_NOT_ALLOWED);
    }

    private JsonProblem invalidTypeId(InvalidTypeIdException ex) {
        var path = jsonPath(ex.getPath());
        var base = ex.getBaseType();
        var baseName = base != null ? base.getRawClass().getSimpleName() : null;
        return new JsonProblem(msg("error.invalid-type-id.title"),
                msg("error.invalid-type-id.detail", ex.getTypeId(), baseName != null ? baseName : "?", path),
                path, ex.getTypeId(), baseName, null, null, null, null, ErrorCode.INVALID_TYPE_ID);
    }

    private JsonProblem valueInstantiation(ValueInstantiationException ex) {
        var path = jsonPath(ex.getPath());
        var type = ex.getType();
        var typeName = type != null ? type.getRawClass().getSimpleName() : "?";
        return new JsonProblem(msg("error.invalid-value.title"),
                msg("error.invalid-instance.detail", typeName, path),
                path, null, typeName, null, null, null, null, ErrorCode.INVALID_FIELD_VALUE);
    }

    private String jsonValue(Object enumConstant) {
        try {
            var node = objectMapper.valueToTree(enumConstant);
            return node.isString() ? node.asString() : node.toString();
        } catch (JacksonException e) {
            return ((Enum<?>) enumConstant).name();
        }
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
