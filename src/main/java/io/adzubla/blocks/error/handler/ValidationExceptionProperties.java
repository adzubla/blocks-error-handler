package io.adzubla.blocks.error.handler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for {@link ValidationExceptionHandler} and {@link ConstraintViolationExceptionHandler}.
 */
@ConfigurationProperties(prefix = "blocks.error.validation")
public class ValidationExceptionProperties {

    /**
     * Words that mark a field as sensitive. A violation whose path contains one, ignoring case and
     * the characters '-' and '_', has no 'invalidValue'. Setting this replaces the defaults, and
     * leaving it empty returns every simple value.
     */
    private List<String> sensitiveFields = new ArrayList<>(List.of(
            "password", "passwd", "pwd", "secret", "token", "apikey", "privatekey", "credential"));

    public List<String> getSensitiveFields() {
        return sensitiveFields;
    }

    public void setSensitiveFields(List<String> sensitiveFields) {
        this.sensitiveFields = sensitiveFields;
    }
}
