package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.NoResultException;
import jakarta.persistence.OptimisticLockException;
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

/**
 * Translates raw JPA exceptions into RFC 9457 {@link org.springframework.http.ProblemDetail}
 * responses, answering them like their Spring-translated counterparts in
 * {@link DataExceptionHandler}:
 *
 * <ul>
 *   <li>{@link EntityNotFoundException}, {@link NoResultException} → 404 {@code RESOURCE_NOT_FOUND}
 *   <li>{@link OptimisticLockException} → 409 {@code OPTIMISTIC_LOCKING_FAILURE}
 *   <li>{@link EntityExistsException} → 409 {@code DATA_INTEGRITY_VIOLATION}
 * </ul>
 *
 * <p>Spring translates {@code NoResultException}, {@code OptimisticLockException} and
 * {@code EntityExistsException} into the {@code org.springframework.dao} hierarchy only when they
 * are thrown from behind a {@code @Repository} proxy; used directly through an
 * {@code EntityManager} (e.g. in a service) they arrive raw, which this handler covers.
 *
 * <p>Split out from {@link DataExceptionHandler} because
 * {@code jakarta.persistence.EntityNotFoundException} is only on the classpath when the consuming
 * application pulls in JPA (e.g. {@code spring-boot-starter-data-jpa});
 * {@code ErrorHandlerAutoConfiguration} registers this bean only when that class is present.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JpaExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JpaExceptionHandler.class);

    private final ErrorMessages messages;

    public JpaExceptionHandler(MessageSource messageSource) {
        this.messages = new ErrorMessages(messageSource);
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEntityNotFound(EntityNotFoundException ex) {
        log.debug("{} {}: Entity not found: {}", HttpStatus.NOT_FOUND.value(), ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messages.get("error.resource-not-found.detail"));
        problem.setTitle(messages.get("error.resource-not-found.title"));
        problem.setProperty("code", ErrorCode.RESOURCE_NOT_FOUND.name());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(NoResultException.class)
    public ResponseEntity<ProblemDetail> handleNoResult(NoResultException ex) {
        return handleEntityNotFoundLike(ex);
    }

    private ResponseEntity<ProblemDetail> handleEntityNotFoundLike(RuntimeException ex) {
        log.debug("{} {}: No result: {}", HttpStatus.NOT_FOUND.value(), ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messages.get("error.resource-not-found.detail"));
        problem.setTitle(messages.get("error.resource-not-found.title"));
        problem.setProperty("code", ErrorCode.RESOURCE_NOT_FOUND.name());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockException ex) {
        log.warn("{} {}: Optimistic locking failure: {}",
                HttpStatus.CONFLICT.value(), ErrorCode.OPTIMISTIC_LOCKING_FAILURE, ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messages.get("error.optimistic-locking.detail"));
        problem.setTitle(messages.get("error.optimistic-locking.title"));
        problem.setProperty("code", ErrorCode.OPTIMISTIC_LOCKING_FAILURE.name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(EntityExistsException.class)
    public ResponseEntity<ProblemDetail> handleEntityExists(EntityExistsException ex) {
        log.warn("{} {}: Entity already exists: {}",
                HttpStatus.CONFLICT.value(), ErrorCode.DATA_INTEGRITY_VIOLATION, ex.getMessage());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                messages.get("error.data-integrity.detail"));
        problem.setTitle(messages.get("error.data-integrity.title"));
        problem.setProperty("code", ErrorCode.DATA_INTEGRITY_VIOLATION.name());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
