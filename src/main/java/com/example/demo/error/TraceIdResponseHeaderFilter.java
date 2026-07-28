package com.example.demo.error;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet filter that adds an {@value #HEADER_NAME} response header to every HTTP response.
 *
 * <p>The filter runs at {@link Ordered#LOWEST_PRECEDENCE}, making it the innermost filter in the
 * chain. At that point {@code ServerHttpObservationFilter} — which runs at a much earlier/outer
 * position — has already started the Brave/OTel span, so
 * {@link io.micrometer.tracing.Tracer#currentSpan()} is non-null for sampled requests. The header
 * is written <em>before</em> delegating further so it is always present regardless of whether a
 * downstream handler commits the response early.
 *
 * <p>If the {@link io.micrometer.tracing.Tracer} bean is absent the filter delegates without
 * setting the header, making Micrometer Tracing an optional runtime dependency.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TraceIdResponseHeaderFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Trace-Id";

    private final Tracer tracer;

    public TraceIdResponseHeaderFilter(ObjectProvider<Tracer> tracerProvider) {
        this.tracer = tracerProvider.getIfAvailable();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (tracer != null) {
            var span = tracer.currentSpan();
            if (span != null) {
                response.setHeader(HEADER_NAME, span.context().traceId());
            }
        }
        filterChain.doFilter(request, response);
    }
}
