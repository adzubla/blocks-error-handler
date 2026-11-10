package io.adzubla.blocks.error.autoconfigure;

import io.adzubla.blocks.error.handler.ConstraintViolationExceptionHandler;
import io.adzubla.blocks.error.handler.DataExceptionHandler;
import io.adzubla.blocks.error.handler.DataExceptionProperties;
import io.adzubla.blocks.error.handler.GlobalExceptionHandler;
import io.adzubla.blocks.error.handler.JpaExceptionHandler;
import io.adzubla.blocks.error.handler.JsonExceptionHandler;
import io.adzubla.blocks.error.handler.ProblemTypeAdvice;
import io.adzubla.blocks.error.handler.SpringDataExceptionHandler;
import io.adzubla.blocks.error.handler.ValidationExceptionHandler;
import io.adzubla.blocks.error.trace.ProblemDetailTraceAdvice;
import io.adzubla.blocks.error.trace.TraceIdResponseHeaderFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerAutoConfigurationTests {

    private static final List<Class<?>> HANDLERS = List.of(
            JsonExceptionHandler.class, ValidationExceptionHandler.class,
            ConstraintViolationExceptionHandler.class, DataExceptionHandler.class,
            JpaExceptionHandler.class, SpringDataExceptionHandler.class,
            GlobalExceptionHandler.class,
            ProblemDetailTraceAdvice.class, TraceIdResponseHeaderFilter.class);

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ErrorHandlerAutoConfiguration.class, ErrorTraceAutoConfiguration.class));

    @Test
    void registersAllHandlersByDefault() {
        runner.run(context -> HANDLERS.forEach(type -> assertThat(context).hasSingleBean(type)));
    }

    @Test
    void masterSwitchDisablesEverything() {
        runner.withPropertyValues("blocks.error.enabled=false")
                .run(context -> {
                    HANDLERS.forEach(type -> assertThat(context).doesNotHaveBean(type));
                    assertThat(context).doesNotHaveBean(ProblemTypeAdvice.class);
                });
    }

    @Test
    void registersProblemTypeAdviceByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(ProblemTypeAdvice.class));
    }

    @Test
    void eachHandlerCanBeDisabledIndividually() {
        runner.withPropertyValues(
                        "blocks.error.json.enabled=false",
                        "blocks.error.validation.enabled=false",
                        "blocks.error.constraint-violation.enabled=false",
                        "blocks.error.data.enabled=false",
                        "blocks.error.jpa.enabled=false",
                        "blocks.error.spring-data.enabled=false",
                        "blocks.error.global.enabled=false",
                        "blocks.error.trace.body.enabled=false",
                        "blocks.error.trace.header.enabled=false")
                .run(context -> HANDLERS.forEach(type -> assertThat(context).doesNotHaveBean(type)));
    }

    @Test
    void disablingOneHandlerLeavesTheOthers() {
        runner.withPropertyValues("blocks.error.data.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DataExceptionHandler.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(JsonExceptionHandler.class);
                });
    }

    @Test
    void applicationBeanReplacesLibraryHandler() {
        runner.withUserConfiguration(CustomDataHandlerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DataExceptionHandler.class);
                    assertThat(context.getBean(DataExceptionHandler.class))
                            .isInstanceOf(CustomDataExceptionHandler.class);
                });
    }

    @Test
    void backsOffOutsideServletWebApplications() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ErrorHandlerAutoConfiguration.class, ErrorTraceAutoConfiguration.class))
                .run(context -> HANDLERS.forEach(type -> assertThat(context).doesNotHaveBean(type)));
    }

    @Test
    void jsonHandlerBacksOffWithoutJackson() {
        runner.withClassLoader(new FilteredClassLoader("tools.jackson."))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JsonExceptionHandler.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                });
    }

    @Test
    void optionalHandlersBackOffWithoutTheirDependency() {
        runner.withClassLoader(new FilteredClassLoader(
                        "jakarta.validation.", "jakarta.persistence.", "io.micrometer.tracing.",
                        "org.springframework.dao."))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ConstraintViolationExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(DataExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(DataExceptionProperties.class);
                    assertThat(context).doesNotHaveBean(JpaExceptionHandler.class);
                    assertThat(context).doesNotHaveBean(ProblemDetailTraceAdvice.class);
                    assertThat(context).doesNotHaveBean(TraceIdResponseHeaderFilter.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(JsonExceptionHandler.class);
                    assertThat(context).hasSingleBean(ValidationExceptionHandler.class);
                });
    }

    @Test
    void bootProblemDetailsHandlerBacksOffInFavourOfGlobalExceptionHandler() {
        runner.withConfiguration(AutoConfigurations.of(WebMvcAutoConfiguration.class))
                .withPropertyValues("spring.mvc.problemdetails.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ResponseEntityExceptionHandler.class);
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDataHandlerConfig {
        @Bean
        CustomDataExceptionHandler customDataExceptionHandler(MessageSource messageSource) {
            return new CustomDataExceptionHandler(messageSource);
        }
    }

    static class CustomDataExceptionHandler extends DataExceptionHandler {
        CustomDataExceptionHandler(MessageSource messageSource) {
            super(messageSource, new DataExceptionProperties());
        }
    }
}
