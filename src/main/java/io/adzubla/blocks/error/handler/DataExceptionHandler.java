package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Translates JDBC data exceptions into RFC 9457 {@link org.springframework.http.ProblemDetail} responses.
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
 *   <li>{@link org.springframework.dao.OptimisticLockingFailureException} → 409 {@code OPTIMISTIC_LOCKING_FAILURE}
 * </ul>
 * <p>
 * Constraint names are extracted from the PostgreSQL error message via regex so the response body
 * exposes only a stable {@code code} value, never raw database messages.
 *
 * <p>JPA's {@code EntityNotFoundException} is handled separately by
 * {@link JpaEntityNotFoundExceptionHandler}, which is only registered when JPA is on the classpath.
 *
 * <p><b>Not handled here.</b> Every other {@code org.springframework.dao.DataAccessException}
 * subtype, and every raw JPA/JDBC exception that Spring's exception translation doesn't reach,
 * falls through to {@link GlobalExceptionHandler}'s {@code @ExceptionHandler(Exception.class)}
 * catch-all: HTTP 500, {@code code=INTERNAL_SERVER_ERROR}, a generic client-facing detail message,
 * and the full exception (including any raw SQL or driver-specific detail in the cause chain)
 * logged server-side at {@code ERROR}. Notable examples that land there instead of a 4xx response:
 * <ul>
 *   <li>Transient/lock failures — {@code CannotAcquireLockException},
 *       {@code PessimisticLockingFailureException}, {@code DeadlockLoseDataAccessException},
 *       {@code CannotSerializeTransactionException}, {@code QueryTimeoutException}
 *   <li>Connectivity/config failures — {@code DataAccessResourceFailureException},
 *       {@code CannotGetJdbcConnectionException}
 *   <li>Programming/schema errors — {@code InvalidDataAccessResourceUsageException} (including
 *       {@code BadSqlGrammarException}), {@code TypeMismatchDataAccessException}
 *   <li>{@code PermissionDeniedDataAccessException}, {@code UncategorizedDataAccessException} /
 *       {@code UncategorizedSQLException} (vendor {@code SQLException} Spring couldn't classify)
 *   <li>Raw JPA exceptions not routed through a Spring Data repository proxy —
 *       {@code jakarta.persistence.PersistenceException}, {@code EntityExistsException},
 *       {@code OptimisticLockException}, {@code PessimisticLockException},
 *       {@code LockTimeoutException}, {@code NoResultException}, {@code NonUniqueResultException},
 *       {@code TransactionRequiredException}, {@code RollbackException} — these are only translated
 *       into the {@code org.springframework.dao} hierarchy above when thrown from behind a
 *       {@code @Repository} bean under {@code PersistenceExceptionTranslationPostProcessor}
 * </ul>
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
        var status = HttpStatus.CONFLICT;
        var rootMessage = Objects.requireNonNullElse(ex.getMostSpecificCause().getMessage(), "");
        var uniqueMatcher = UNIQUE_CONSTRAINT.matcher(rootMessage);
        var constraint = uniqueMatcher.find() ? uniqueMatcher.group(1) : "unknown";
        log.warn("{} {}: Duplicate key violation on constraint '{}': {}",
                status.value(), ErrorCode.DUPLICATE_VALUE, constraint, rootMessage);
        var problem = ProblemDetail.forStatusAndDetail(status, msg("error.duplicate-value.detail"));
        problem.setTitle(msg("error.duplicate-value.title"));
        problem.setProperty("code", ErrorCode.DUPLICATE_VALUE.name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        var status = HttpStatus.CONFLICT;
        var rootMessage = Objects.requireNonNullElse(ex.getMostSpecificCause().getMessage(), "");

        var uniqueMatcher = UNIQUE_CONSTRAINT.matcher(rootMessage);
        if (uniqueMatcher.find()) {
            log.warn("{} {}: Unique constraint violation on '{}': {}",
                    status.value(), ErrorCode.DUPLICATE_VALUE, uniqueMatcher.group(1), rootMessage);
            var problem = ProblemDetail.forStatusAndDetail(status, msg("error.duplicate-value.detail"));
            problem.setTitle(msg("error.duplicate-value.title"));
            problem.setProperty("code", ErrorCode.DUPLICATE_VALUE.name());
            return ResponseEntity.status(status).body(problem);
        }

        var fkMatcher = FK_CONSTRAINT.matcher(rootMessage);
        if (fkMatcher.find()) {
            log.warn("{} {}: Foreign key constraint violation on '{}': {}",
                    status.value(), ErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    fkMatcher.group(1), rootMessage);
            var problem = ProblemDetail.forStatusAndDetail(status, msg("error.referential-integrity.detail"));
            problem.setTitle(msg("error.referential-integrity.title"));
            problem.setProperty("code", ErrorCode.REFERENTIAL_INTEGRITY_VIOLATION.name());
            return ResponseEntity.status(status).body(problem);
        }

        log.warn("{} {}: Unclassified data integrity violation ({}): {}",
                status.value(), ErrorCode.DATA_INTEGRITY_VIOLATION,
                ex.getClass().getSimpleName(), rootMessage);
        log.trace("Stack trace:", ex);
        var problem = ProblemDetail.forStatusAndDetail(status, msg("error.data-integrity.detail"));
        problem.setTitle(msg("error.data-integrity.title"));
        problem.setProperty("code", ErrorCode.DATA_INTEGRITY_VIOLATION.name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<ProblemDetail> handleEmptyResult(EmptyResultDataAccessException ex) {
        var status = HttpStatus.NOT_FOUND;
        log.debug("{} {}: Empty result: {}", status.value(), ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(status, msg("error.resource-not-found.detail"));
        problem.setTitle(msg("error.resource-not-found.title"));
        problem.setProperty("code", ErrorCode.RESOURCE_NOT_FOUND.name());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
        var status = HttpStatus.CONFLICT;
        log.warn("{} {}: Optimistic locking failure: {}",
                status.value(), ErrorCode.OPTIMISTIC_LOCKING_FAILURE, ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(status, msg("error.optimistic-locking.detail"));
        problem.setTitle(msg("error.optimistic-locking.title"));
        problem.setProperty("code", ErrorCode.OPTIMISTIC_LOCKING_FAILURE.name());
        return ResponseEntity.status(status).body(problem);
    }
}
