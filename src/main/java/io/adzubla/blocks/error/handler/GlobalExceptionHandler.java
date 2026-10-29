package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Catch-all handler covering Spring MVC infrastructure exceptions and any {@link Exception}
 * not claimed by a higher-precedence handler.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} to handle the full set of standard Spring MVC
 * exceptions (method-not-allowed, unsupported media type, missing parameters, etc.), enriching
 * every {@link org.springframework.http.ProblemDetail} body produced by
 * {@link #handleExceptionInternal} with a stable {@link ErrorCode} string.
 *
 * <p>A final {@code @ExceptionHandler(Exception.class)} catch-all returns 500
 * {@code INTERNAL_SERVER_ERROR} and logs the stack trace at {@code ERROR} level, with two
 * exceptions:
 * <ul>
 *   <li>Exceptions annotated with {@link ResponseStatus @ResponseStatus} (directly or on a
 *       superclass) keep their declared status and reason, with {@code code=ERROR_RESPONSE} —
 *       mirroring what Spring's {@code ResponseStatusExceptionResolver} would have done had this
 *       catch-all not claimed them first.
 *   <li>Spring Security's {@code AccessDeniedException} and {@code AuthenticationException} (e.g.
 *       from {@code @PreAuthorize} method security) are rethrown so they propagate to Spring
 *       Security's {@code ExceptionTranslationFilter} and produce its 401/403 response. These are
 *       detected by class name, so Spring Security remains an optional dependency.
 * </ul>
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Spring Security exceptions that must reach ExceptionTranslationFilter instead of becoming a 500.
    private static final List<Class<?>> PROPAGATED_EXCEPTIONS = Stream.of(
                    "org.springframework.security.access.AccessDeniedException",
                    "org.springframework.security.core.AuthenticationException")
            .map(GlobalExceptionHandler::loadOptionalClass)
            .filter(Objects::nonNull)
            .toList();

    private final MessageSource messageSource;
    private final ErrorMessages messages;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
        this.messages = new ErrorMessages(messageSource);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        var code = codeFor(ex);
        if (statusCode.is5xxServerError()) {
            log.error("Unexpected error handling request, responding {} {}", statusCode.value(), code, ex);
        } else {
            log.warn("{} {}: {}", statusCode.value(), code, ex.getMessage());
            log.trace("Stack trace:", ex);
        }
        var result = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (result != null && result.getBody() instanceof ProblemDetail pd) {
            pd.setProperty("code", code);
        }
        return result;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        for (var type : PROPAGATED_EXCEPTIONS) {
            if (type.isInstance(ex)) {
                throw ex;
            }
        }
        var responseStatus = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(), ResponseStatus.class);
        if (responseStatus != null) {
            return handleAnnotatedStatus(ex, responseStatus);
        }
        log.error("{} {}: Unexpected error", HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL_SERVER_ERROR, ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                messages.get("error.internal.detail"));
        problem.setTitle(messages.get("error.internal.title"));
        problem.setProperty("code", ErrorCode.INTERNAL_SERVER_ERROR.name());
        return ResponseEntity.internalServerError().body(problem);
    }

    private ResponseEntity<ProblemDetail> handleAnnotatedStatus(Exception ex, ResponseStatus responseStatus) {
        var status = responseStatus.code();
        var code = ErrorCode.ERROR_RESPONSE;
        if (status.is5xxServerError()) {
            log.error("{} {}: Unexpected error", status.value(), code, ex);
        } else {
            log.warn("{} {}: {}", status.value(), code, ex.getMessage());
            log.trace("Stack trace:", ex);
        }
        var problem = ProblemDetail.forStatus(status);
        var reason = responseStatus.reason();
        if (StringUtils.hasText(reason)) {
            // Like ResponseStatusExceptionResolver, the reason may be a message code.
            problem.setDetail(messageSource.getMessage(reason, null, reason, LocaleContextHolder.getLocale()));
        }
        problem.setProperty("code", code.name());
        return ResponseEntity.status(status).body(problem);
    }

    private static Class<?> loadOptionalClass(String className) {
        var classLoader = GlobalExceptionHandler.class.getClassLoader();
        return ClassUtils.isPresent(className, classLoader)
                ? ClassUtils.resolveClassName(className, classLoader)
                : null;
    }

    private static String codeFor(Exception ex) {
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return ErrorCode.METHOD_NOT_ALLOWED.name();
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE.name();
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return ErrorCode.NOT_ACCEPTABLE.name();
        }
        if (ex instanceof MissingPathVariableException) {
            return ErrorCode.MISSING_PATH_VARIABLE.name();
        }
        if (ex instanceof MissingServletRequestParameterException) {
            return ErrorCode.MISSING_REQUEST_PARAMETER.name();
        }
        if (ex instanceof MissingServletRequestPartException) {
            return ErrorCode.MISSING_REQUEST_PART.name();
        }
        if (ex instanceof ServletRequestBindingException) {
            return ErrorCode.REQUEST_BINDING_ERROR.name();
        }
        if (ex instanceof NoResourceFoundException) {
            return ErrorCode.ROUTE_NOT_FOUND.name();
        }
        if (ex instanceof NoHandlerFoundException) {
            return ErrorCode.ROUTE_NOT_FOUND.name();
        }
        if (ex instanceof AsyncRequestTimeoutException) {
            return ErrorCode.REQUEST_TIMEOUT.name();
        }
        if (ex instanceof MaxUploadSizeExceededException) {
            return ErrorCode.PAYLOAD_TOO_LARGE.name();
        }
        if (ex instanceof ConversionNotSupportedException) {
            return ErrorCode.CONVERSION_NOT_SUPPORTED.name();
        }
        if (ex instanceof TypeMismatchException) {
            return ErrorCode.PARAMETER_TYPE_MISMATCH.name();
        }
        if (ex instanceof HttpMessageNotWritableException) {
            return ErrorCode.MESSAGE_NOT_WRITABLE.name();
        }
        if (ex instanceof HandlerMethodValidationException) {
            return ErrorCode.VALIDATION_FAILED.name();
        }
        if (ex instanceof MethodValidationException) {
            return ErrorCode.METHOD_VALIDATION_ERROR.name();
        }
        if (ex instanceof BindException) {
            return ErrorCode.VALIDATION_FAILED.name();
        }
        if (ex instanceof ErrorResponseException) {
            return ErrorCode.ERROR_RESPONSE.name();
        }
        return ErrorCode.INTERNAL_SERVER_ERROR.name();
    }
}
