package com.example.demo.error;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Translates JPA and JDBC data exceptions into RFC 9457 {@link org.springframework.http.ProblemDetail} responses.
 *
 * <ul>
 *   <li>{@link org.springframework.dao.DuplicateKeyException} → 409 {@code DUPLICATE_VALUE}
 *   <li>{@link org.springframework.dao.DataIntegrityViolationException} with a unique-constraint message
 *       → 409 {@code DUPLICATE_VALUE}
 *   <li>{@link org.springframework.dao.DataIntegrityViolationException} with a foreign-key message
 *       → 409 {@code REFERENTIAL_INTEGRITY_VIOLATION}
 *   <li>{@link org.springframework.dao.DataIntegrityViolationException} (other)
 *       → 409 {@code DATA_INTEGRITY_VIOLATION}
 *   <li>{@link org.springframework.dao.EmptyResultDataAccessException} → 404 {@code RESOURCE_NOT_FOUND}
 *   <li>{@link jakarta.persistence.EntityNotFoundException} → 404 {@code RESOURCE_NOT_FOUND}
 * </ul>
 * <p>
 * Constraint names are extracted from the PostgreSQL error message via regex so the response body
 * exposes only a stable {@code code} value, never raw database messages.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DataExceptionHandler.class);

    private final MessageSource messageSource;

    public DataExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    private String msg(String key) {
        return messageSource.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    // PostgreSQL: ERROR: duplicate key value violates unique constraint "constraint_name"
    private static final Pattern UNIQUE_CONSTRAINT = Pattern.compile(
            "unique constraint \"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    // PostgreSQL: ERROR: insert or update on table "t" violates foreign key constraint "c"
    private static final Pattern FK_CONSTRAINT = Pattern.compile(
            "foreign key constraint \"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateKey(DuplicateKeyException ex) {
        var rootMessage = Objects.requireNonNullElse(ex.getMostSpecificCause().getMessage(), "");
        var uniqueMatcher = UNIQUE_CONSTRAINT.matcher(rootMessage);
        var constraint = uniqueMatcher.find() ? uniqueMatcher.group(1) : "unknown";
        log.warn("Duplicate key violation on constraint '{}': {}", constraint, rootMessage);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.duplicate-value.detail"));
        problem.setTitle(msg("error.duplicate-value.title"));
        problem.setProperty("code", ErrorCode.DUPLICATE_VALUE.name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        var rootMessage = Objects.requireNonNullElse(ex.getMostSpecificCause().getMessage(), "");

        var uniqueMatcher = UNIQUE_CONSTRAINT.matcher(rootMessage);
        if (uniqueMatcher.find()) {
            log.warn("Unique constraint violation on '{}': {}", uniqueMatcher.group(1), rootMessage);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.duplicate-value.detail"));
            problem.setTitle(msg("error.duplicate-value.title"));
            problem.setProperty("code", ErrorCode.DUPLICATE_VALUE.name());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        var fkMatcher = FK_CONSTRAINT.matcher(rootMessage);
        if (fkMatcher.find()) {
            log.warn("Foreign key constraint violation on '{}': {}", fkMatcher.group(1), rootMessage);
            var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.referential-integrity.detail"));
            problem.setTitle(msg("error.referential-integrity.title"));
            problem.setProperty("code", ErrorCode.REFERENTIAL_INTEGRITY_VIOLATION.name());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        log.warn("Unclassified data integrity violation: {}", rootMessage);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, msg("error.data-integrity.detail"));
        problem.setTitle(msg("error.data-integrity.title"));
        problem.setProperty("code", ErrorCode.DATA_INTEGRITY_VIOLATION.name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<ProblemDetail> handleEmptyResult(EmptyResultDataAccessException ex) {
        log.debug("Empty result: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.resource-not-found.detail"));
        problem.setTitle(msg("error.resource-not-found.title"));
        problem.setProperty("code", ErrorCode.RESOURCE_NOT_FOUND.name());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEntityNotFound(EntityNotFoundException ex) {
        log.debug("Entity not found: {}", ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, msg("error.resource-not-found.detail"));
        problem.setTitle(msg("error.resource-not-found.title"));
        problem.setProperty("code", ErrorCode.RESOURCE_NOT_FOUND.name());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
