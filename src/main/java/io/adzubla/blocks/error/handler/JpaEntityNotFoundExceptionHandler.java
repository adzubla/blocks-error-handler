package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import jakarta.persistence.EntityNotFoundException;
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

/**
 * Translates JPA's {@link EntityNotFoundException} into an RFC 9457
 * {@link org.springframework.http.ProblemDetail} response.
 *
 * <p>Split out from {@link DataExceptionHandler} and gated behind
 * {@link ConditionalOnClass @ConditionalOnClass(EntityNotFoundException.class)} because
 * {@code jakarta.persistence.EntityNotFoundException} is only on the classpath when the consuming
 * application pulls in JPA (e.g. {@code spring-boot-starter-data-jpa}). Without this guard, this
 * bean would fail to load in any application that doesn't use JPA.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnClass(EntityNotFoundException.class)
public class JpaEntityNotFoundExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(JpaEntityNotFoundExceptionHandler.class);

    private final MessageSource messageSource;

    public JpaEntityNotFoundExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEntityNotFound(EntityNotFoundException ex) {
        log.debug("Entity not found: {}", ex.getMessage());
        var locale = LocaleContextHolder.getLocale();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                messageSource.getMessage("error.resource-not-found.detail", null, locale));
        problem.setTitle(messageSource.getMessage("error.resource-not-found.title", null, locale));
        problem.setProperty("code", ErrorCode.RESOURCE_NOT_FOUND.name());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
