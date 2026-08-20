package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.Map;

/**
 * Translates Bean Validation's {@link ConstraintViolationException} — raised by {@code @Validated}
 * on non-controller Spring beans (e.g. a service layer) or by direct {@code Validator} calls — into
 * an RFC 9457 {@link org.springframework.http.ProblemDetail} response with HTTP 422 Unprocessable
 * Content.
 *
 * <p>Split out from {@link ValidationExceptionHandler} and gated behind
 * {@link ConditionalOnClass @ConditionalOnClass(ConstraintViolationException.class)} because
 * {@code jakarta.validation.ConstraintViolationException} is only on the classpath when the
 * consuming application pulls in Bean Validation (e.g. {@code spring-boot-starter-validation}).
 * Without this guard, this bean would fail to load in any application that doesn't use it.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnClass(ConstraintViolationException.class)
public class ConstraintViolationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ConstraintViolationExceptionHandler.class);

    private final MessageSource messageSource;

    public ConstraintViolationExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        var violations = ex.getConstraintViolations().stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(violation -> Map.of(
                        "path", "$." + violation.getPropertyPath().toString(),
                        "invalidValue", violation.getInvalidValue() != null
                                ? String.valueOf(violation.getInvalidValue()) : "null",
                        "message", violation.getMessage() != null ? violation.getMessage() : ""
                ))
                .toList();

        log.debug("{} {}: Constraint violation with {} violation(s)",
                HttpStatus.UNPROCESSABLE_CONTENT.value(), ErrorCode.VALIDATION_FAILED, violations.size());
        log.trace("Stack trace:", ex);

        var locale = LocaleContextHolder.getLocale();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                messageSource.getMessage("error.validation-failed.detail", null, locale));
        problem.setTitle(messageSource.getMessage("error.validation-failed.title", null, locale));
        problem.setProperty("code", ErrorCode.VALIDATION_FAILED.name());
        problem.setProperty("violations", violations);
        return ResponseEntity.unprocessableContent().body(problem);
    }
}
