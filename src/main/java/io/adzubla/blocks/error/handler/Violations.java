package io.adzubla.blocks.error.handler;

import org.springframework.beans.BeanUtils;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;

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
 */
final class Violations {

    private final List<String> sensitiveWords;

    Violations(List<String> sensitiveFields) {
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
        return error instanceof FieldError fe
                ? of(root + "." + fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage())
                : withoutValue(root, error.getDefaultMessage());
    }

    Map<String, String> of(String path, Object invalidValue, String message) {
        if (isSensitive(path) || (invalidValue != null && !BeanUtils.isSimpleValueType(invalidValue.getClass()))) {
            return withoutValue(path, message);
        }
        var violation = new LinkedHashMap<String, String>();
        violation.put("path", path);
        violation.put("invalidValue", invalidValue != null ? String.valueOf(invalidValue) : null);
        violation.put("message", message != null ? message : "");
        return violation;
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
     * parameters). A constrained scalar parameter is reported as {@code $.<name>}, with the
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
                var name = parameter.getMethodParameter().getParameterName();
                var path = "$." + (name != null ? name : "?") + containerSuffix(parameter);
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
