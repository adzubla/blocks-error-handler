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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
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
 * {@link #handleExceptionInternal} with a stable {@link ErrorCode} string. A parameter of the wrong
 * type and a missing required parameter also get {@code path} and {@code expectedType}.
 *
 * <p>Validation failures that reach this handler — only when {@link ValidationExceptionHandler} is
 * disabled or replaced — are answered with 422, matching their {@code VALIDATION_FAILED} code,
 * instead of Spring's default 400. A {@link MultipartException} is answered with 400
 * {@code MALFORMED_MULTIPART_REQUEST}.
 *
 * <p>A final {@code @ExceptionHandler(Exception.class)} catch-all returns 500
 * {@code INTERNAL_SERVER_ERROR} and logs the stack trace at {@code ERROR} level, except for:
 * <ul>
 *   <li>Exceptions annotated with {@link ResponseStatus @ResponseStatus} (directly or on a
 *       superclass, or wrapped in another exception) keep their declared status and reason, with
 *       {@code code=ERROR_RESPONSE} —
 *       mirroring what Spring's {@code ResponseStatusExceptionResolver} would have done had this
 *       catch-all not claimed them first.
 *   <li>Spring Security's {@code AccessDeniedException} and {@code AuthenticationException} (e.g.
 *       from {@code @PreAuthorize} method security), even wrapped in another exception, are
 *       rethrown so they propagate to Spring
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
    private final Violations values;

    /**
     * Uses the default sensitive fields; prefer
     * {@link #GlobalExceptionHandler(MessageSource, ValidationExceptionProperties)}.
     */
    public GlobalExceptionHandler(MessageSource messageSource) {
        this(messageSource, new ValidationExceptionProperties());
    }

    public GlobalExceptionHandler(MessageSource messageSource, ValidationExceptionProperties properties) {
        this.messageSource = messageSource;
        this.messages = new ErrorMessages(messageSource);
        this.values = new Violations(messages, properties.getSensitiveFields());
    }

    /**
     * A query or path parameter of the wrong type is reported like a body field of the wrong type:
     * with its {@code path}, the {@code expectedType} in OpenAPI terms, and the {@code invalidValue}
     * unless the parameter is sensitive.
     */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var name = ex instanceof MethodArgumentTypeMismatchException matme ? matme.getName() : ex.getPropertyName();
        var expectedType = OpenApiTypes.of(ex.getRequiredType());
        if (name == null || expectedType == null) {
            return super.handleTypeMismatch(ex, headers, status, request);
        }
        var path = "$." + name;
        var exposed = values.exposes(path, ex.getValue());
        var detail = exposed
                ? messages.get("error.parameter-type-mismatch.detail", ex.getValue(), name, expectedType)
                : messages.get("error.parameter-type-mismatch.detail.hidden", name, expectedType);
        // The detail can still be overridden with Spring's own message code for this exception;
        // such an override gets the value only when it may be returned.
        var body = createProblemDetail(ex, status, detail, null,
                new Object[]{name, exposed ? ex.getValue() : null, expectedType}, request);
        body.setProperty("path", path);
        if (exposed) {
            body.setProperty("invalidValue", ex.getValue() != null ? String.valueOf(ex.getValue()) : null);
        }
        body.setProperty("expectedType", expectedType);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * A missing required query parameter names its {@code path} and, when known, the
     * {@code expectedType} in OpenAPI terms.
     */
    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ex.getBody().setProperty("path", "$." + ex.getParameterName());
        var parameter = ex.getMethodParameter();
        if (parameter != null) {
            ex.getBody().setProperty("expectedType", OpenApiTypes.of(parameter.getNestedParameterType()));
        }
        return super.handleMissingServletRequestParameter(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ex.getBody().setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        return super.handleMethodArgumentNotValid(ex, headers, HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // A return-value violation keeps Spring's 500: it is a server error, not a client one.
        if (!ex.isForReturnValue()) {
            ex.getBody().setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
            status = HttpStatus.UNPROCESSABLE_CONTENT;
        }
        return super.handleHandlerMethodValidationException(ex, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        var code = codeFor(ex);
        if (statusCode.is5xxServerError()) {
            log.error("Unexpected error handling request, responding {} {}", statusCode.value(), code, ex);
        } else {
            log.warn("{} {}: {}", statusCode.value(), code, Causes.describe(ex));
            log.trace("Stack trace:", ex);
        }
        var result = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (result != null && result.getBody() instanceof ProblemDetail pd) {
            pd.setProperty("code", code);
        }
        return result;
    }

    /**
     * A multipart request that can't be parsed, or a request that isn't multipart sent to an endpoint
     * expecting one, is the client's fault. {@link MaxUploadSizeExceededException} (a subclass) keeps
     * Spring's own 413 mapping.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> handleMultipart(MultipartException ex) {
        log.warn("{} {}: {}", HttpStatus.BAD_REQUEST.value(), ErrorCode.MALFORMED_MULTIPART_REQUEST, Causes.describe(ex));
        log.trace("Stack trace:", ex);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                messages.get("error.malformed-multipart.detail"));
        problem.setTitle(messages.get("error.malformed-multipart.title"));
        problem.setProperty("code", ErrorCode.MALFORMED_MULTIPART_REQUEST.name());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) throws Exception {
        // Both lookups walk the cause chain, as Spring Security's ExceptionTranslationFilter and
        // Spring's ResponseStatusExceptionResolver do: the exception that matters is often wrapped.
        var chain = Causes.chain(ex);
        for (var cause : chain) {
            for (var type : PROPAGATED_EXCEPTIONS) {
                if (type.isInstance(cause)) {
                    throw ex;
                }
            }
        }
        for (var cause : chain) {
            var responseStatus = AnnotatedElementUtils.findMergedAnnotation(cause.getClass(), ResponseStatus.class);
            if (responseStatus != null) {
                return handleAnnotatedStatus(ex, responseStatus);
            }
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
            log.warn("{} {}: {}", status.value(), code, Causes.describe(ex));
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
        if (ex instanceof MissingPathVariableException mpve) {
            // Present but converted to null, such as a blank UUID, is the client's fault and a 400;
            // only a variable missing from the mapping is the server's 500 MISSING_PATH_VARIABLE.
            return mpve.isMissingAfterConversion()
                    ? ErrorCode.REQUEST_BINDING_ERROR.name() : ErrorCode.MISSING_PATH_VARIABLE.name();
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
        if (ex instanceof HttpMessageNotReadableException) {
            return ErrorCode.MALFORMED_REQUEST_BODY.name();
        }
        if (ex instanceof HttpMessageNotWritableException) {
            return ErrorCode.MESSAGE_NOT_WRITABLE.name();
        }
        // The next two are normally claimed first by ValidationExceptionHandler; reached only when
        // an application disables or replaces it (blocks.error.validation.enabled=false).
        if (ex instanceof HandlerMethodValidationException hmve) {
            return hmve.isForReturnValue()
                    ? ErrorCode.INTERNAL_SERVER_ERROR.name() : ErrorCode.VALIDATION_FAILED.name();
        }
        if (ex instanceof MethodArgumentNotValidException) {
            return ErrorCode.VALIDATION_FAILED.name();
        }
        if (ex instanceof MethodValidationException) {
            return ErrorCode.METHOD_VALIDATION_ERROR.name();
        }
        if (ex instanceof ErrorResponseException) {
            return ErrorCode.ERROR_RESPONSE.name();
        }
        return ErrorCode.INTERNAL_SERVER_ERROR.name();
    }
}
