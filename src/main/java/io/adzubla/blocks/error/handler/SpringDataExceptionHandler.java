package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates Spring Data's {@link PropertyReferenceException} into an RFC 9457
 * {@link org.springframework.http.ProblemDetail} response with HTTP 400 and
 * {@code code=INVALID_PROPERTY_REFERENCE}.
 *
 * <p>This is what a request like {@code GET /products?sort=nonexistent} produces: the
 * {@code Sort} built from the query string names a property the entity doesn't have. Repository
 * proxies usually wrap it in an {@code InvalidDataAccessApiUsageException}; the exception handler
 * lookup walks the cause chain, so this handler still receives it. The offending property name is
 * returned as {@code invalidValue}; the entity type is not exposed.
 *
 * <p>Split out from {@link DataExceptionHandler} because {@code spring-data-commons} is optional:
 * {@code ErrorHandlerAutoConfiguration} registers this bean only when
 * {@code PropertyReferenceException} is on the classpath.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpringDataExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SpringDataExceptionHandler.class);

    private final ErrorMessages messages;

    public SpringDataExceptionHandler(MessageSource messageSource) {
        this.messages = new ErrorMessages(messageSource);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ProblemDetail> handlePropertyReference(PropertyReferenceException ex) {
        log.debug("{} {}: Unknown property '{}' on {}", HttpStatus.BAD_REQUEST.value(),
                ErrorCode.INVALID_PROPERTY_REFERENCE, ex.getPropertyName(), ex.getType());
        log.trace("Stack trace:", ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.get("error.invalid-property.detail", ex.getPropertyName()));
        problem.setTitle(messages.get("error.invalid-property.title"));
        problem.setProperty("code", ErrorCode.INVALID_PROPERTY_REFERENCE.name());
        problem.setProperty("invalidValue", ex.getPropertyName());
        return ResponseEntity.badRequest().body(problem);
    }
}
