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
import org.springframework.validation.FieldError;
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
 * <p>Handles two exception types:
 * <ul>
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException} — raised by
 *       {@code @Valid} on {@code @RequestBody} parameters; violations are collected from the
 *       {@link org.springframework.validation.BindingResult}.
 *   <li>{@link org.springframework.web.method.annotation.HandlerMethodValidationException} — raised
 *       by {@code @Validated} on individual method parameters or return values.
 * </ul>
 *
 * <p>Every violation is mapped to a {@code violations} array entry with:
 * <ul>
 *   <li>{@code path} — JSONPath notation (e.g. {@code $.address.street})
 *   <li>{@code invalidValue} — serialised as string; {@code "null"} when the value is {@code null}
 *   <li>{@code message} — the constraint violation message
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ValidationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ValidationExceptionHandler.class);

    private final MessageSource messageSource;

    public ValidationExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        var violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> {
                    if (error instanceof FieldError fe) {
                        return Map.of(
                                "path", "$." + fe.getField(),
                                "invalidValue", fe.getRejectedValue() != null
                                        ? String.valueOf(fe.getRejectedValue()) : "null",
                                "message", fe.getDefaultMessage() != null ? fe.getDefaultMessage() : ""
                        );
                    }
                    return Map.of(
                            "path", "$." + error.getObjectName(),
                            "message", error.getDefaultMessage() != null ? error.getDefaultMessage() : ""
                    );
                })
                .toList();

        log.debug("Validation failed with {} violation(s)", violations.size());
        log.trace("Stack trace:", ex);
        return unprocessable(violations);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(
            HandlerMethodValidationException ex) {
        var violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> {
                            String field = result.getMethodParameter().getParameterName();
                            if (error instanceof FieldError fe) {
                                field = fe.getField();
                            }
                            return Map.of(
                                    "path", "$." + (field != null ? field : "?"),
                                    "message", error.getDefaultMessage() != null
                                            ? error.getDefaultMessage() : ""
                            );
                        }))
                .toList();

        log.debug("Handler method validation failed with {} violation(s)", violations.size());
        log.trace("Stack trace:", ex);
        return unprocessable(violations);
    }

    private ResponseEntity<ProblemDetail> unprocessable(List<Map<String, String>> violations) {
        var locale = LocaleContextHolder.getLocale();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                messageSource.getMessage("error.validation-failed.detail", null, locale));
        problem.setTitle(messageSource.getMessage("error.validation-failed.title", null, locale));
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("violations", violations);
        return ResponseEntity.unprocessableContent().body(problem);
    }
}
