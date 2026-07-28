package com.example.demo.error;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * {@link ResponseBodyAdvice} that stamps the current Micrometer trace ID into every
 * {@link org.springframework.http.ProblemDetail} response body produced by an
 * {@code @ExceptionHandler} method.
 *
 * <p>The {@code traceId} property is written only when a Brave/OTel span is active for the
 * current request (i.e. when {@link io.micrometer.tracing.Tracer#currentSpan()} is non-null).
 * If the {@link io.micrometer.tracing.Tracer} bean is absent from the application context the
 * advice is a no-op, so projects without Micrometer Tracing on the classpath are unaffected.
 *
 * <p>{@link #supports} is scoped to {@code @ExceptionHandler} methods, so normal
 * controller responses are never intercepted.
 */
@RestControllerAdvice
public class ProblemDetailTraceAdvice implements ResponseBodyAdvice<Object> {

    private final Tracer tracer;

    public ProblemDetailTraceAdvice(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.hasMethodAnnotation(ExceptionHandler.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail pd && tracer != null) {
            var span = tracer.currentSpan();
            if (span != null) {
                pd.setProperty("traceId", span.context().traceId());
            }
        }
        return body;
    }
}
