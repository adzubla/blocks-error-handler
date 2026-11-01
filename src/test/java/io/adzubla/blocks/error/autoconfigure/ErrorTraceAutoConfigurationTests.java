package io.adzubla.blocks.error.autoconfigure;

import io.adzubla.blocks.error.trace.ProblemDetailTraceAdvice;
import io.adzubla.blocks.error.trace.TraceIdResponseHeaderFilter;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ErrorTraceAutoConfigurationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ErrorTraceAutoConfiguration.class));

    @Test
    void severalTracerBeans_doNotPreventStartup() {
        runner.withUserConfiguration(TwoTracersConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProblemDetailTraceAdvice.class);
            assertThat(context).hasSingleBean(TraceIdResponseHeaderFilter.class);
        });
    }

    @Test
    void noTracerBean_doesNotPreventStartup() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoTracersConfig {
        @Bean
        Tracer firstTracer() {
            return mock(Tracer.class);
        }

        @Bean
        Tracer secondTracer() {
            return mock(Tracer.class);
        }
    }
}
