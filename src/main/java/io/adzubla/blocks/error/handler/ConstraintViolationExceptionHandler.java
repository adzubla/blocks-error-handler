package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Translates Bean Validation's {@link ConstraintViolationException} — raised by {@code @Validated}
 * on non-controller Spring beans (e.g. a service layer) or by direct {@code Validator} calls — into
 * an RFC 9457 {@link org.springframework.http.ProblemDetail} response with HTTP 422 Unprocessable
 * Content.
 *
 * <p>Split out from {@link ValidationExceptionHandler} because
 * {@code jakarta.validation.ConstraintViolationException} is only on the classpath when the
 * consuming application pulls in Bean Validation (e.g. {@code spring-boot-starter-validation});
 * {@code ErrorHandlerAutoConfiguration} registers this bean only when that class is present.
 *
 * <p>Each violation's {@code path} is rendered from the Bean Validation property path in JSONPath
 * style, dropping nodes that are Java implementation details rather than client-facing names: the
 * method name of a method-validation violation, and a parameter name when the violation is on that
 * parameter's properties or on the object as a whole (mirroring how
 * {@link ValidationExceptionHandler} reports request bodies). So
 * {@code void create(@Valid Request request)} reports {@code $.name}, while
 * {@code void find(@NotBlank String code)} reports {@code $.code} and
 * {@code void tag(List<@NotBlank String> tags)} reports {@code $.tags[0]}. {@code invalidValue}
 * follows the convention in {@link Violations}, so a complex value or sensitive field is never
 * echoed.
 *
 * <p>A violation on a method's <em>return value</em> means the server produced invalid data, not
 * that the client sent it, so it is answered like any unexpected error: HTTP 500
 * {@code INTERNAL_SERVER_ERROR}, with the exception logged at {@code ERROR}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConstraintViolationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ConstraintViolationExceptionHandler.class);

    private final ErrorMessages messages;
    private final Violations violationMapper;

    public ConstraintViolationExceptionHandler(MessageSource messageSource, ValidationExceptionProperties properties) {
        this.messages = new ErrorMessages(messageSource);
        this.violationMapper = new Violations(properties.getSensitiveFields());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        // getConstraintViolations() is null when the exception was built with only a message.
        Set<ConstraintViolation<?>> constraintViolations =
                Objects.requireNonNullElse(ex.getConstraintViolations(), Set.of());
        if (constraintViolations.stream().anyMatch(ConstraintViolationExceptionHandler::isReturnValueViolation)) {
            return returnValueViolation(ex);
        }
        var violations = constraintViolations.stream()
                .map(violation -> violationMapper.of(jsonPath(violation.getPropertyPath()),
                        violation.getInvalidValue(), violation.getMessage()))
                // By message too, so several violations on one path come out in a stable order.
                .sorted(Comparator.<Map<String, String>, String>comparing(v -> v.get("path"))
                        .thenComparing(v -> v.get("message")))
                .toList();

        log.debug("{} {}: Constraint violation with {} violation(s)",
                HttpStatus.UNPROCESSABLE_CONTENT.value(), ErrorCode.VALIDATION_FAILED, violations.size());
        log.trace("Stack trace:", ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                messages.get("error.validation-failed.detail"));
        problem.setTitle(messages.get("error.validation-failed.title"));
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("violations", violations);
        return ResponseEntity.unprocessableContent().body(problem);
    }

    private ResponseEntity<ProblemDetail> returnValueViolation(ConstraintViolationException ex) {
        log.error("{} {}: Return value constraint violation",
                HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL_SERVER_ERROR, ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                messages.get("error.internal.detail"));
        problem.setTitle(messages.get("error.internal.title"));
        problem.setProperty("code", ErrorCode.INTERNAL_SERVER_ERROR.name());
        return ResponseEntity.internalServerError().body(problem);
    }

    private static boolean isReturnValueViolation(ConstraintViolation<?> violation) {
        for (var node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.RETURN_VALUE) {
                return true;
            }
        }
        return false;
    }

    private static String jsonPath(Path propertyPath) {
        var nodes = new ArrayList<Path.Node>();
        propertyPath.forEach(nodes::add);
        var sb = new StringBuilder("$");
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            // The index/key of an element in a container is carried by the node that follows it.
            if (node.isInIterable()) {
                var position = node.getIndex() != null ? node.getIndex() : node.getKey();
                sb.append('[').append(position != null ? position : "").append(']');
            }
            var kind = node.getKind();
            var next = i + 1 < nodes.size() ? nodes.get(i + 1) : null;
            var named = kind == ElementKind.PROPERTY
                    || (kind == ElementKind.PARAMETER
                        && (next == null || (next.getKind() != ElementKind.PROPERTY && next.getKind() != ElementKind.BEAN)));
            if (named && node.getName() != null) {
                sb.append('.').append(node.getName());
            }
        }
        return sb.toString();
    }
}
