package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import jakarta.persistence.EntityNotFoundException;
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
 * Translates JPA's {@link EntityNotFoundException} into an RFC 9457
 * {@link org.springframework.http.ProblemDetail} response.
 *
 * <p>Split out from {@link DataExceptionHandler} because
 * {@code jakarta.persistence.EntityNotFoundException} is only on the classpath when the consuming
 * application pulls in JPA (e.g. {@code spring-boot-starter-data-jpa});
 * {@code ErrorHandlerAutoConfiguration} registers this bean only when that class is present.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class JpaEntityNotFoundExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JpaEntityNotFoundExceptionHandler.class);

    private final ErrorMessages messages;

    public JpaEntityNotFoundExceptionHandler(MessageSource messageSource) {
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
}
