package io.adzubla.blocks.error.trace;

import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;

/**
 * Servlet filter that adds an {@value #HEADER_NAME} response header to HTTP responses.
 *
 * <p>The filter runs at {@link #ORDER}, immediately after {@code ServerHttpObservationFilter}
 * ({@code HIGHEST_PRECEDENCE + 1}), which starts the Brave/OTel span, so
 * {@link io.micrometer.tracing.Tracer#currentSpan()} is non-null. Running
 * that early, ahead of filters such as Spring Security's, lets the header reach responses those
 * filters produce themselves (a 401/403, a CORS rejection) and not only those that get as far as
 * the servlet.
 *
 * <p>By default the header is added to every response, success and error alike, except those
 * whose status is listed in {@link TraceIdHeaderProperties#getExcludeStatuses()} (401 and 403 by
 * default, so the responses of Spring Security's authentication and authorization failures don't
 * carry it). When {@link TraceIdHeaderProperties#isErrorOnly()} is {@code true} the header is only
 * added to error responses (status 400+).
 *
 * <p>Because the decision depends on the status, the response is wrapped and the header is added
 * lazily, while headers can still be written: when the status is set via {@code setStatus} or
 * {@code sendError}, when the body stream or writer is first obtained, on {@code flushBuffer}, and
 * once the chain returns. A header already added cannot be taken back, so a status changed after the
 * body has started is not reconsidered.
 *
 * <p>If there is no unique {@link io.micrometer.tracing.Tracer} bean, or no active span, the filter
 * delegates without setting the header. {@code ErrorTraceAutoConfiguration} registers it only when
 * Micrometer Tracing is on the classpath.
 */
@Order(TraceIdResponseHeaderFilter.ORDER)
public class TraceIdResponseHeaderFilter extends OncePerRequestFilter {

    /** Right after {@code ServerHttpObservationFilter}, which runs at {@code HIGHEST_PRECEDENCE + 1}. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    static final String HEADER_NAME = "X-Trace-Id";

    private final ObjectProvider<Tracer> tracerProvider;
    private final boolean errorOnly;
    private final Set<Integer> excludedStatuses;

    public TraceIdResponseHeaderFilter(ObjectProvider<Tracer> tracerProvider, TraceIdHeaderProperties properties) {
        this.tracerProvider = tracerProvider;
        this.errorOnly = properties.isErrorOnly();
        this.excludedStatuses = Set.copyOf(properties.getExcludeStatuses());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Looked up per request, and only when unique: resolving it at construction would fail
        // startup with several Tracer beans, or miss one that is registered later.
        var tracer = tracerProvider.getIfUnique();
        var span = tracer != null ? tracer.currentSpan() : null;
        if (span == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var wrapper = new TraceIdResponse(response, span.context().traceId());
        try {
            filterChain.doFilter(request, wrapper);
        } finally {
            // Covers responses that set no status and write no body (an implicit, empty 200), and
            // requests that went async, whose later writes bypass this wrapper.
            wrapper.addIfApplicable(wrapper.getStatus());
        }
    }

    private boolean applies(int status) {
        return (!errorOnly || status >= 400) && !excludedStatuses.contains(status);
    }

    // Adds the trace ID header once the status is known and before the response commits.
    private final class TraceIdResponse extends HttpServletResponseWrapper {

        private final String traceId;
        private boolean added;

        TraceIdResponse(HttpServletResponse response, String traceId) {
            super(response);
            this.traceId = traceId;
        }

        @Override
        public void setStatus(int sc) {
            addIfApplicable(sc);
            super.setStatus(sc);
        }

        @Override
        public void sendError(int sc) throws IOException {
            addIfApplicable(sc);
            super.sendError(sc);
        }

        @Override
        public void sendError(int sc, String msg) throws IOException {
            addIfApplicable(sc);
            super.sendError(sc, msg);
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            addIfApplicable(getStatus());
            return super.getOutputStream();
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            addIfApplicable(getStatus());
            return super.getWriter();
        }

        @Override
        public void flushBuffer() throws IOException {
            addIfApplicable(getStatus());
            super.flushBuffer();
        }

        void addIfApplicable(int status) {
            if (!added && !isCommitted() && applies(status)) {
                setHeader(HEADER_NAME, traceId);
                added = true;
            }
        }
    }
}
