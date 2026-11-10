package io.adzubla.blocks.error.handler;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link ProblemTypeAdvice}.
 */
@ConfigurationProperties(prefix = "blocks.error.problem-type")
public class ProblemTypeProperties {

    /**
     * Prefix of the 'type' URI of error responses. The code, in lower case with '-' instead of '_',
     * is appended: 'urn:problem-type:validation-failed'. Point it at your API documentation, such as
     * 'https://api.example.com/problems/', to make the types resolvable. Leave it empty to send no
     * 'type', which RFC 9457 reads as 'about:blank'.
     */
    private String baseUri = "urn:problem-type:";

    public String getBaseUri() {
        return baseUri;
    }

    public void setBaseUri(String baseUri) {
        this.baseUri = baseUri;
    }
}
