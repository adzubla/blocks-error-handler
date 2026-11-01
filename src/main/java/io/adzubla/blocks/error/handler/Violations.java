package io.adzubla.blocks.error.handler;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Violations of a Spring method-validation result (controller or {@code @Validated} bean
     * parameters). A constrained scalar parameter is reported as {@code $.<name>}, with the
     * element index or key appended for a container element ({@code $.tags[1]}); a validated
     * object contributes the paths of its own fields, and an object-level or cross-parameter
     * constraint is reported as {@code $}.
     */
    static List<Map<String, String>> of(MethodValidationResult result) {
        var violations = new ArrayList<Map<String, String>>();
        for (var parameter : result.getParameterValidationResults()) {
            if (parameter instanceof ParameterErrors errors) {
                var root = "$" + containerSuffix(parameter);
                for (var error : errors.getAllErrors()) {
                    violations.add(error instanceof FieldError fe
                            ? of(root + "." + fe.getField(), fe.getRejectedValue(), fe.getDefaultMessage())
                            : withoutValue(root, error.getDefaultMessage()));
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
