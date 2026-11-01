package io.adzubla.blocks.error.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Configuration for {@link TraceIdResponseHeaderFilter}.
 */
@ConfigurationProperties(prefix = "blocks.error.trace.header")
public class TraceIdHeaderProperties {

    /**
     * Whether to add the X-Trace-Id response header only to error responses (HTTP status 400 and
     * above) instead of to every response.
     */
    private boolean errorOnly = false;

    /**
     * HTTP statuses whose responses do not get the X-Trace-Id header. Defaults to 401 and 403, so
     * the responses produced by Spring Security's authentication and authorization failures don't
     * expose a trace ID to callers that are not (or not sufficiently) authenticated. Set it empty
     * to add the header regardless of status.
     */
    private Set<Integer> excludeStatuses = new LinkedHashSet<>(Set.of(401, 403));

    public Set<Integer> getExcludeStatuses() {
        return excludeStatuses;
    }

    public void setExcludeStatuses(Set<Integer> excludeStatuses) {
        this.excludeStatuses = excludeStatuses;
    }

    public boolean isErrorOnly() {
        return errorOnly;
    }

    public void setErrorOnly(boolean errorOnly) {
        this.errorOnly = errorOnly;
    }
}
