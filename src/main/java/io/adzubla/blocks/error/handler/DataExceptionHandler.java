package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
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

import java.sql.SQLException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Translates Spring data-access exceptions into RFC 9457 {@link org.springframework.http.ProblemDetail} responses.
 *
 * <ul>
 *   <li>{@link org.springframework.dao.DuplicateKeyException} → 409 {@code DUPLICATE_VALUE}
 *   <li>{@link org.springframework.dao.DataIntegrityViolationException} with a unique-constraint message
 *       → 409 {@code DUPLICATE_VALUE}
 *   <li>{@link org.springframework.dao.DataIntegrityViolationException} with a foreign-key message
 *       → 409 {@code REFERENTIAL_INTEGRITY_VIOLATION} — with a distinct detail message when the
 *       violation comes from updating or deleting a row that is still referenced, as opposed to
 *       inserting or updating a row that references a missing one
 *   <li>{@link org.springframework.dao.DataIntegrityViolationException} (other)
 *       → 409 {@code DATA_INTEGRITY_VIOLATION}
 *   <li>{@link org.springframework.dao.EmptyResultDataAccessException} → 404 {@code RESOURCE_NOT_FOUND}
 *   <li>{@link org.springframework.dao.OptimisticLockingFailureException} → 409 {@code OPTIMISTIC_LOCKING_FAILURE}
 * </ul>
 * <p>
 * Unique and foreign-key violations are recognised by the SQLState of the underlying
 * {@link SQLException} ({@code 23505} / {@code 23503}), so classification doesn't depend on the
 * language the database writes its messages in; the English PostgreSQL message text is a fallback
 * for drivers that report no SQLState. Constraint names, and — for duplicate-key and foreign-key
 * violations — the offending value ({@code invalidValue}), are extracted from the PostgreSQL error
 * message via regex; when the value can't be extracted, {@code invalidValue} is omitted. The database
 * column name itself is deliberately not exposed (it may not match a client-facing field name at
 * all — different casing, a related entity, a generated key). The response body exposes only these
 * structured fields plus a stable {@code code} value; the raw driver message text itself is never
 * forwarded to the client. {@code invalidValue} can be turned off with
 * {@code blocks.error.data.expose-invalid-value=false} (see {@link DataExceptionProperties}).
 *
 * <p>Raw JPA exceptions are handled by {@link JpaExceptionHandler}, and Spring Data's
 * {@code PropertyReferenceException} by {@link SpringDataExceptionHandler}; both are only
 * registered when their libraries are on the classpath.
 *
 * <p><b>Not handled here.</b> Every other {@code org.springframework.dao.DataAccessException}
 * subtype, and every raw JPA/JDBC exception that Spring's exception translation doesn't reach,
 * falls through to {@link GlobalExceptionHandler}: HTTP 500, {@code INTERNAL_SERVER_ERROR}, a
 * generic detail message, and the full exception logged server-side at {@code ERROR}. Notable
 * examples:
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
 *   <li>Other raw JPA exceptions — {@code jakarta.persistence.PersistenceException},
 *       {@code PessimisticLockException}, {@code LockTimeoutException},
 *       {@code NonUniqueResultException}, {@code TransactionRequiredException},
 *       {@code RollbackException} — which Spring translates into the types above only when they
 *       are thrown from behind a {@code @Repository} bean
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DataExceptionHandler.class);

    private final ErrorMessages messages;
    private final boolean exposeInvalidValue;

    public DataExceptionHandler(MessageSource messageSource, DataExceptionProperties properties) {
        this.messages = new ErrorMessages(messageSource);
        this.exposeInvalidValue = properties.isExposeInvalidValue();
    }

    private String msg(String key) {
        return messages.get(key);
    }

    // PostgreSQL: ERROR: duplicate key value violates unique constraint "constraint_name"
    private static final Pattern UNIQUE_CONSTRAINT = Pattern.compile(
            "unique constraint \"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    // PostgreSQL: ERROR: insert or update on table "t" violates foreign key constraint "c"
    private static final Pattern FK_CONSTRAINT = Pattern.compile(
            "foreign key constraint \"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    // PostgreSQL: Detail: Key (column)=(value) already exists.
    //             Detail: Key (column)=(value) is not present in table "t".
    //             Detail: Key (column)=(value) is still referenced from table "t".
    // Anchored on the trailing phrase so columns and values containing parentheses — expression
    // indexes like lower(email), values like "Widget (large)" — are captured whole.
    private static final Pattern KEY_DETAIL = Pattern.compile(
            "Key \\((.+?)\\)=\\((.*)\\) (?:already exists|is not present|is still referenced)",
            Pattern.CASE_INSENSITIVE);

    // Same, for messages in another language (lc_messages), e.g. "Chave (name)=(x) já existe.":
    // the value runs to the last ')' on the line, as the translated trailing phrase has none.
    private static final Pattern LOCALIZED_KEY_DETAIL = Pattern.compile("\\((.+?)\\)=\\((.*)\\)");

    // PostgreSQL: ERROR: update or delete on table "t" violates foreign key constraint "c" on table "u"
    //             Detail: Key (id)=(1) is still referenced from table "u".
    private static final Pattern STILL_REFERENCED = Pattern.compile(
            "update or delete on table|is still referenced", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUOTED_IDENTIFIER = Pattern.compile("\"[^\"]*\"");

    // SQL:2003 / PostgreSQL SQLState codes.
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    // Server-side only: the database column name, logged for troubleshooting but never returned
    // to the client (see class Javadoc).
    private record OffendingKey(String column, String value) {
        private static final OffendingKey UNKNOWN = new OffendingKey("unknown", null);
    }

    private static OffendingKey offendingKey(String rootMessage) {
        var matcher = KEY_DETAIL.matcher(rootMessage);
        if (matcher.find()) {
            return new OffendingKey(matcher.group(1), matcher.group(2));
        }
        matcher = LOCALIZED_KEY_DETAIL.matcher(rootMessage);
        return matcher.find() ? new OffendingKey(matcher.group(1), matcher.group(2)) : OffendingKey.UNKNOWN;
    }

    private static String sqlState(Throwable ex) {
        for (var t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static boolean isStillReferenced(String rootMessage) {
        if (STILL_REFERENCED.matcher(rootMessage).find()) {
            return true;
        }
        // Translated messages keep PostgreSQL's placeholders: the "still referenced" form names
        // three identifiers on its first line (table, constraint, referencing table), the
        // "referenced row missing" form two.
        var firstLine = rootMessage.lines().findFirst().orElse("");
        return QUOTED_IDENTIFIER.matcher(firstLine).results().count() >= 3;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateKey(DuplicateKeyException ex) {
        var status = HttpStatus.CONFLICT;
        var rootMessage = Objects.requireNonNullElse(ex.getMostSpecificCause().getMessage(), "");
        var uniqueMatcher = UNIQUE_CONSTRAINT.matcher(rootMessage);
        var constraint = uniqueMatcher.find() ? uniqueMatcher.group(1) : "unknown";
        var key = offendingKey(rootMessage);
        log.warn("{} {}: Duplicate key violation on constraint '{}', column '{}': {}",
                status.value(), ErrorCode.DUPLICATE_VALUE, constraint, key.column(), rootMessage);
        return keyViolation(status, ErrorCode.DUPLICATE_VALUE,
                "error.duplicate-value.title", "error.duplicate-value.detail", key);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        var status = HttpStatus.CONFLICT;
        var rootMessage = Objects.requireNonNullElse(ex.getMostSpecificCause().getMessage(), "");
        var sqlState = sqlState(ex);

        var uniqueMatcher = UNIQUE_CONSTRAINT.matcher(rootMessage);
        var uniqueFound = uniqueMatcher.find();
        if (uniqueFound || UNIQUE_VIOLATION.equals(sqlState)) {
            var key = offendingKey(rootMessage);
            log.warn("{} {}: Unique constraint violation on '{}', column '{}': {}",
                    status.value(), ErrorCode.DUPLICATE_VALUE, uniqueFound ? uniqueMatcher.group(1) : "unknown",
                    key.column(), rootMessage);
            return keyViolation(status, ErrorCode.DUPLICATE_VALUE,
                    "error.duplicate-value.title", "error.duplicate-value.detail", key);
        }

        var fkMatcher = FK_CONSTRAINT.matcher(rootMessage);
        var fkFound = fkMatcher.find();
        if (fkFound || FOREIGN_KEY_VIOLATION.equals(sqlState)) {
            var key = offendingKey(rootMessage);
            var stillReferenced = isStillReferenced(rootMessage);
            log.warn("{} {}: Foreign key constraint violation on '{}' ({}), column '{}': {}",
                    status.value(), ErrorCode.REFERENTIAL_INTEGRITY_VIOLATION, fkFound ? fkMatcher.group(1) : "unknown",
                    stillReferenced ? "row still referenced" : "referenced row missing",
                    key.column(), rootMessage);
            var detailKey = stillReferenced
                    ? "error.referential-integrity.still-referenced.detail"
                    : "error.referential-integrity.detail";
            return keyViolation(status, ErrorCode.REFERENTIAL_INTEGRITY_VIOLATION,
                    "error.referential-integrity.title", detailKey, key);
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

    private ResponseEntity<ProblemDetail> keyViolation(HttpStatus status, ErrorCode code,
                                                       String titleKey, String detailKey, OffendingKey key) {
        var problem = ProblemDetail.forStatusAndDetail(status, msg(detailKey));
        problem.setTitle(msg(titleKey));
        problem.setProperty("code", code.name());
        if (exposeInvalidValue && key.value() != null) {
            problem.setProperty("invalidValue", key.value());
        }
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
