package com.example.demo.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.ConversionNotSupportedException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.validation.method.MethodValidationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
 * {@code INTERNAL_SERVER_ERROR} and logs the stack trace at {@code ERROR} level.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        var result = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (result != null && result.getBody() instanceof ProblemDetail pd) {
            pd.setProperty("code", codeFor(ex));
        }
        return result;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        var locale = LocaleContextHolder.getLocale();
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                messageSource.getMessage("error.internal.detail", null, locale));
        problem.setTitle(messageSource.getMessage("error.internal.title", null, locale));
        problem.setProperty("code", ErrorCode.INTERNAL_SERVER_ERROR.name());
        return ResponseEntity.internalServerError().body(problem);
    }

    private static String codeFor(Exception ex) {
        return switch (ex) {
            case HttpRequestMethodNotSupportedException e -> ErrorCode.METHOD_NOT_ALLOWED.name();
            case HttpMediaTypeNotSupportedException e -> ErrorCode.UNSUPPORTED_MEDIA_TYPE.name();
            case HttpMediaTypeNotAcceptableException e -> ErrorCode.NOT_ACCEPTABLE.name();
            case MissingPathVariableException e -> ErrorCode.MISSING_PATH_VARIABLE.name();
            case MissingServletRequestParameterException e -> ErrorCode.MISSING_REQUEST_PARAMETER.name();
            case MissingServletRequestPartException e -> ErrorCode.MISSING_REQUEST_PART.name();
            case ServletRequestBindingException e -> ErrorCode.REQUEST_BINDING_ERROR.name();
            case NoResourceFoundException e -> ErrorCode.ROUTE_NOT_FOUND.name();
            case NoHandlerFoundException e -> ErrorCode.ROUTE_NOT_FOUND.name();
            case AsyncRequestTimeoutException e -> ErrorCode.REQUEST_TIMEOUT.name();
            case MaxUploadSizeExceededException e -> ErrorCode.PAYLOAD_TOO_LARGE.name();
            case ConversionNotSupportedException e -> ErrorCode.CONVERSION_NOT_SUPPORTED.name();
            case TypeMismatchException e -> ErrorCode.PARAMETER_TYPE_MISMATCH.name();
            case HttpMessageNotWritableException e -> ErrorCode.MESSAGE_NOT_WRITABLE.name();
            case HandlerMethodValidationException e -> ErrorCode.VALIDATION_FAILED.name();
            case MethodValidationException e -> ErrorCode.METHOD_VALIDATION_ERROR.name();
            default -> ErrorCode.INTERNAL_SERVER_ERROR.name();
        };
    }
}
