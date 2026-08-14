package io.adzubla.blocks.error.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link TraceIdResponseHeaderFilter}.
 */
@ConfigurationProperties(prefix = "blocks.error.trace.header")
public class TraceIdHeaderProperties {

    /**
     * When {@code true}, the {@code X-Trace-Id} header is only added to error responses
     * (HTTP status 400 and above). When {@code false} (the default), it is added to every
     * response, success and error alike.
     */
    private boolean errorOnly = false;

    public boolean isErrorOnly() {
        return errorOnly;
    }

    public void setErrorOnly(boolean errorOnly) {
        this.errorOnly = errorOnly;
    }
}
