package io.adzubla.blocks.error.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    public boolean isErrorOnly() {
        return errorOnly;
    }

    public void setErrorOnly(boolean errorOnly) {
        this.errorOnly = errorOnly;
    }
}
