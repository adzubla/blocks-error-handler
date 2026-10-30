package io.adzubla.blocks.error.autoconfigure;

import io.adzubla.blocks.error.trace.ProblemDetailTraceAdvice;
import io.adzubla.blocks.error.trace.TraceIdHeaderProperties;
import io.adzubla.blocks.error.trace.TraceIdResponseHeaderFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Propagates the Micrometer trace ID into error responses: the {@code traceId} ProblemDetail
 * property ({@link ProblemDetailTraceAdvice}) and the {@code X-Trace-Id} response header
 * ({@link TraceIdResponseHeaderFilter}).
 *
 * <p>Active only in servlet web applications with Micrometer Tracing on the classpath. Like
 * {@link ErrorHandlerAutoConfiguration}, it honours {@code blocks.error.enabled}, and each bean can
 * be disabled ({@code blocks.error.trace.body.enabled} / {@code blocks.error.trace.header.enabled})
 * or replaced by declaring a bean of the same type.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
@ConditionalOnBooleanProperty(name = "blocks.error.enabled", matchIfMissing = true)
@EnableConfigurationProperties(TraceIdHeaderProperties.class)
public class ErrorTraceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "blocks.error.trace.body.enabled", matchIfMissing = true)
    ProblemDetailTraceAdvice problemDetailTraceAdvice(ObjectProvider<Tracer> tracerProvider) {
        return new ProblemDetailTraceAdvice(tracerProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "blocks.error.trace.header.enabled", matchIfMissing = true)
    TraceIdResponseHeaderFilter traceIdResponseHeaderFilter(ObjectProvider<Tracer> tracerProvider,
                                                            TraceIdHeaderProperties properties) {
        return new TraceIdResponseHeaderFilter(tracerProvider, properties);
    }
}
