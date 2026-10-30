package io.adzubla.blocks.error.handler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link DataExceptionHandler}.
 */
@ConfigurationProperties(prefix = "blocks.error.data")
public class DataExceptionProperties {

    /**
     * Whether duplicate-key and foreign-key responses include the offending value extracted from
     * the database error as 'invalidValue'. Turn off when unique or foreign-key constraints cover
     * derived or server-generated columns, whose stored values the client never sent.
     */
    private boolean exposeInvalidValue = true;

    public boolean isExposeInvalidValue() {
        return exposeInvalidValue;
    }

    public void setExposeInvalidValue(boolean exposeInvalidValue) {
        this.exposeInvalidValue = exposeInvalidValue;
    }
}
