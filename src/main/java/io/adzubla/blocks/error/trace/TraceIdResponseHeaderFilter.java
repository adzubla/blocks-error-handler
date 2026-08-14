package io.adzubla.blocks.error.trace;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that adds an {@value #HEADER_NAME} response header to HTTP responses.
 *
 * <p>The filter runs at {@link Ordered#LOWEST_PRECEDENCE}, making it the innermost filter in the
 * chain. At that point {@code ServerHttpObservationFilter} — which runs at a much earlier/outer
 * position — has already started the Brave/OTel span, so
 * {@link io.micrometer.tracing.Tracer#currentSpan()} is non-null for sampled requests.
 *
 * <p>By default the header is added to every response, success and error alike, and is written
 * <em>before</em> delegating further so it is always present regardless of whether a downstream
 * handler commits the response early. When {@link TraceIdHeaderProperties#isErrorOnly()} is
 * {@code true} the header is only added to error responses (status 400+); this requires waiting
 * for the downstream handler to finish so the final status is known, so the header will be
 * silently omitted if a downstream handler commits the response before returning.
 *
 * <p>If the {@link io.micrometer.tracing.Tracer} bean is absent the filter delegates without
 * setting the header, making the Micrometer Tracing bean itself optional at runtime. However, the
 * {@code io.micrometer.tracing.Tracer} class is imported directly as a field type here, so the
 * class must still be on the classpath at load time; hence the
 * {@link ConditionalOnClass @ConditionalOnClass(Tracer.class)} guard, without which this bean would
 * fail to load in any application that doesn't have Micrometer Tracing on the classpath.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnClass(Tracer.class)
@EnableConfigurationProperties(TraceIdHeaderProperties.class)
public class TraceIdResponseHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Trace-Id";

    private final Tracer tracer;
    private final boolean errorOnly;

    public TraceIdResponseHeaderFilter(ObjectProvider<Tracer> tracerProvider, TraceIdHeaderProperties properties) {
        this.tracer = tracerProvider.getIfAvailable();
        this.errorOnly = properties.isErrorOnly();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var span = tracer != null ? tracer.currentSpan() : null;
        if (span == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (errorOnly) {
            filterChain.doFilter(request, response);
            if (response.getStatus() >= 400 && !response.isCommitted()) {
                response.setHeader(HEADER_NAME, span.context().traceId());
            }
        } else {
            response.setHeader(HEADER_NAME, span.context().traceId());
            filterChain.doFilter(request, response);
        }
    }
}
