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
import org.springframework.validation.method.MethodValidationException;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;
import java.util.Map;

/**
 * Translates Bean Validation failures into RFC 9457 {@link org.springframework.http.ProblemDetail}
 * responses with HTTP 422 Unprocessable Content.
 *
 * <p>Handles three exception types:
 * <ul>
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException} — raised by
 *       {@code @Valid} on {@code @RequestBody} parameters; violations are collected from the
 *       {@link org.springframework.validation.BindingResult}.
 *   <li>{@link org.springframework.web.method.annotation.HandlerMethodValidationException} — raised
 *       by Spring MVC's built-in method validation of controller parameters and return values.
 *   <li>{@link org.springframework.validation.method.MethodValidationException} — raised by
 *       {@code @Validated} beans when method-validation violations are adapted
 *       ({@code spring.validation.method.adapt-constraint-violations=true}); answered like the
 *       controller case.
 * </ul>
 *
 * <p>{@code jakarta.validation.ConstraintViolationException} — raised by {@code @Validated} on
 * non-controller Spring beans, or by direct {@code Validator} calls — is handled separately by
 * {@link ConstraintViolationExceptionHandler}, which is only registered when Bean Validation is on
 * the classpath.
 *
 * <p>Every violation is mapped to a {@code violations} array entry with:
 * <ul>
 *   <li>{@code path} — JSONPath notation (e.g. {@code $.address.street}); {@code $} for a
 *       constraint on the request object as a whole
 *   <li>{@code invalidValue} — a simple value serialised as string; JSON {@code null} when the
 *       value is {@code null}; absent for a complex value such as a collection or an object, and for
 *       an object-level or cross-parameter constraint
 *   <li>{@code message} — the constraint violation message
 * </ul>
 *
 * <p>A violation on a controller's <em>return value</em> means the server produced invalid data,
 * not that the client sent it, so it is answered like any unexpected error: HTTP 500
 * {@code INTERNAL_SERVER_ERROR}, with the exception logged at {@code ERROR}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ValidationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ValidationExceptionHandler.class);

    private final ErrorMessages messages;

    public ValidationExceptionHandler(MessageSource messageSource) {
        this.messages = new ErrorMessages(messageSource);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        var violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> Violations.of("$", error))
                .toList();

        log.debug("{} {}: Validation failed with {} violation(s)",
                HttpStatus.UNPROCESSABLE_CONTENT.value(), ErrorCode.VALIDATION_FAILED, violations.size());
        log.trace("Stack trace:", ex);
        return unprocessable(violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(
            HandlerMethodValidationException ex) {
        return methodValidation(ex, ex.isForReturnValue(), ex);
    }

    /**
     * Raised by {@code @Validated} beans when {@code MethodValidationPostProcessor} adapts
     * constraint violations ({@code spring.validation.method.adapt-constraint-violations=true}).
     */
    @ExceptionHandler(MethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(MethodValidationException ex) {
        return methodValidation(ex, ex.isForReturnValue(), ex);
    }

    private ResponseEntity<ProblemDetail> methodValidation(
            MethodValidationResult result, boolean forReturnValue, Exception ex) {
        if (forReturnValue) {
            return returnValueViolation(ex);
        }
        var violations = Violations.of(result);
        log.debug("{} {}: Method validation failed with {} violation(s)",
                HttpStatus.UNPROCESSABLE_CONTENT.value(), ErrorCode.VALIDATION_FAILED, violations.size());
        log.trace("Stack trace:", ex);
        return unprocessable(violations);
    }

    private ResponseEntity<ProblemDetail> returnValueViolation(Exception ex) {
        log.error("{} {}: Return value constraint violation",
                HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL_SERVER_ERROR, ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                messages.get("error.internal.detail"));
        problem.setTitle(messages.get("error.internal.title"));
        problem.setProperty("code", ErrorCode.INTERNAL_SERVER_ERROR.name());
        return ResponseEntity.internalServerError().body(problem);
    }

    private ResponseEntity<ProblemDetail> unprocessable(List<Map<String, String>> violations) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                messages.get("error.validation-failed.detail"));
        problem.setTitle(messages.get("error.validation-failed.title"));
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("violations", violations);
        return ResponseEntity.unprocessableContent().body(problem);
    }
}
