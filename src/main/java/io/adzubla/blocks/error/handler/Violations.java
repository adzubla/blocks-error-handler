package io.adzubla.blocks.error.handler;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the entries of the {@code violations} array shared by the validation handlers.
 *
 * <p>{@code invalidValue} follows one convention across the library: the value as a string when it
 * is a simple value (a string, number, boolean, date, enum and the like, see
 * {@link BeanUtils#isSimpleValueType}), JSON {@code null} when the rejected value was {@code null},
 * and absent otherwise. A complex value must not be echoed: its {@code toString()} could include
 * fields the client never sent or shouldn't see. That covers collections, arrays and objects,
 * including those rejected by an object-level or cross-parameter constraint.
 *
 * <p>It is also absent, whatever the value, for a field whose path contains one of the configured
 * sensitive words (see {@link ValidationExceptionProperties#getSensitiveFields()}). The whole path
 * is checked, so {@code $.credentials.pin} and a map key in {@code $.attributes[token]} count too.
 *
 * <p>{@code message} is the constraint's message, except for a binding failure (a value that can't
 * be converted to the field's type), which gets a generic message instead of the conversion error,
 * and an {@code expectedType} named as in an OpenAPI schema (see {@link OpenApiTypes}).
 */
final class Violations {

    private final ErrorMessages messages;
    private final List<String> sensitiveWords;

    Violations(ErrorMessages messages, List<String> sensitiveFields) {
        this.messages = messages;
        this.sensitiveWords = sensitiveFields.stream()
                .map(Violations::normalize)
                // A blank word would be contained in every path, hiding every value.
                .filter(word -> !word.isBlank())
                .toList();
    }

    /**
     * Entry for a binding error below {@code root}.
     */
    Map<String, String> of(String root, ObjectError error) {
        if (!(error instanceof FieldError fe)) {
            return withoutValue(root, error.getDefaultMessage());
        }
        var path = root + "." + fe.getField();
        if (!fe.isBindingFailure()) {
            return of(path, fe.getRejectedValue(), fe.getDefaultMessage());
        }
        // A binding failure (a value that can't be converted to the field's type) carries the
        // conversion exception's text as its default message, naming Java types the client has no
        // business seeing: report the expected type in OpenAPI terms instead.
        var expectedType = expectedType(fe);
        var violation = of(path, fe.getRejectedValue(), expectedType != null
                ? messages.get("error.validation-failed.binding-failure.expected", expectedType)
                : messages.get("error.validation-failed.binding-failure"));
        if (expectedType != null) {
            violation.put("expectedType", expectedType);
        }
        return violation;
    }

    private static String expectedType(FieldError error) {
        return error.contains(TypeMismatchException.class)
                ? OpenApiTypes.of(error.unwrap(TypeMismatchException.class).getRequiredType())
                : null;
    }

    /**
     * One-line summary of the violations for the log: each path with its message and rejected
     * value. The value follows the same rules as the response, so it is left out for a sensitive
     * field and for a complex value.
     */
    static String summary(List<Map<String, String>> violations) {
        return violations.stream()
                .map(Violations::summary)
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private static String summary(Map<String, String> violation) {
        var summary = violation.get("path") + ": " + violation.get("message");
        if (!violation.containsKey("invalidValue")) {
            return summary;
        }
        var value = violation.get("invalidValue");
        return summary + " (rejected value: " + (value != null ? "'" + value + "'" : "null") + ")";
    }

    Map<String, String> of(String path, Object invalidValue, String message) {
        if (!exposes(path, invalidValue)) {
            return withoutValue(path, message);
        }
        var violation = new LinkedHashMap<String, String>();
        violation.put("path", path);
        violation.put("invalidValue", invalidValue != null ? String.valueOf(invalidValue) : null);
        violation.put("message", message != null ? message : "");
        return violation;
    }

    /**
     * Whether the value rejected at {@code path} may be returned to the client: not for a sensitive
     * field, nor for a complex value.
     */
    boolean exposes(String path, Object invalidValue) {
        return !isSensitive(path)
                && (invalidValue == null || BeanUtils.isSimpleValueType(invalidValue.getClass()));
    }

    private static Map<String, String> withoutValue(String path, String message) {
        var violation = new LinkedHashMap<String, String>();
        violation.put("path", path);
        violation.put("message", message != null ? message : "");
        return violation;
    }

    private boolean isSensitive(String path) {
        var normalized = normalize(path);
        return sensitiveWords.stream().anyMatch(normalized::contains);
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /**
     * Violations of a Spring method-validation result (controller or {@code @Validated} bean
     * parameters). A constrained scalar parameter is reported as {@code $.<name>}, the name being
     * the one the client used when the parameter declares it ({@code @RequestParam("page_size")}), with the
     * element index or key appended for a container element ({@code $.tags[1]}); a validated
     * object contributes the paths of its own fields, and an object-level or cross-parameter
     * constraint is reported as {@code $}.
     */
    List<Map<String, String>> of(MethodValidationResult result) {
        var violations = new ArrayList<Map<String, String>>();
        for (var parameter : result.getParameterValidationResults()) {
            if (parameter instanceof ParameterErrors errors) {
                var root = "$" + containerSuffix(parameter);
                for (var error : errors.getAllErrors()) {
                    violations.add(of(root, error));
                }
            } else {
                var path = "$." + clientName(parameter.getMethodParameter()) + containerSuffix(parameter);
                for (var error : parameter.getResolvableErrors()) {
                    violations.add(of(path, parameter.getArgument(), error.getDefaultMessage()));
                }
            }
        }
        for (MessageSourceResolvable error : result.getCrossParameterValidationResults()) {
            violations.add(withoutValue("$", error.getDefaultMessage()));
        }
        return violations;
    }

    // The name the client used for a controller parameter (@RequestParam("page_size") int pageSize
    // is "page_size"), falling back to the Java parameter name, which is all a service method has.
    private static String clientName(MethodParameter parameter) {
        String name = null;
        var requestParam = parameter.getParameterAnnotation(RequestParam.class);
        var pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        var requestHeader = parameter.getParameterAnnotation(RequestHeader.class);
        var cookieValue = parameter.getParameterAnnotation(CookieValue.class);
        var requestPart = parameter.getParameterAnnotation(RequestPart.class);
        if (requestParam != null) {
            name = firstText(requestParam.name(), requestParam.value());
        } else if (pathVariable != null) {
            name = firstText(pathVariable.name(), pathVariable.value());
        } else if (requestHeader != null) {
            name = firstText(requestHeader.name(), requestHeader.value());
        } else if (cookieValue != null) {
            name = firstText(cookieValue.name(), cookieValue.value());
        } else if (requestPart != null) {
            name = firstText(requestPart.name(), requestPart.value());
        }
        if (name == null) {
            name = parameter.getParameterName();
        }
        return name != null ? name : "?";
    }

    private static String firstText(String name, String value) {
        return StringUtils.hasText(name) ? name : StringUtils.hasText(value) ? value : null;
    }

    private static String containerSuffix(ParameterValidationResult parameter) {
        if (parameter.getContainerIndex() != null) {
            return "[" + parameter.getContainerIndex() + "]";
        }
        if (parameter.getContainerKey() != null) {
            return "[" + parameter.getContainerKey() + "]";
        }
        return "";
    }
}
