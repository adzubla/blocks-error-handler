package io.adzubla.blocks.error.autoconfigure;

import io.adzubla.blocks.error.handler.ConstraintViolationExceptionHandler;
import io.adzubla.blocks.error.handler.DataExceptionHandler;
import io.adzubla.blocks.error.handler.DataExceptionProperties;
import io.adzubla.blocks.error.handler.GlobalExceptionHandler;
import io.adzubla.blocks.error.handler.JpaExceptionHandler;
import io.adzubla.blocks.error.handler.JsonExceptionHandler;
import io.adzubla.blocks.error.handler.SpringDataExceptionHandler;
import io.adzubla.blocks.error.handler.ValidationExceptionHandler;
import io.adzubla.blocks.error.handler.ValidationExceptionProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers the exception handlers that turn exceptions into RFC 9457 ProblemDetail responses.
 *
 * <p>Active in servlet web applications only, and can be switched off as a whole with
 * {@code blocks.error.enabled=false}. Each handler can also be:
 * <ul>
 *   <li><b>disabled</b> with its own {@code blocks.error.<handler>.enabled=false} property, letting
 *       the exceptions it covers fall through to the next handler (ultimately
 *       {@link GlobalExceptionHandler}, or Spring's defaults if that is disabled too);
 *   <li><b>replaced</b> by declaring a bean of the same type — typically a subclass that overrides
 *       one handler method — which makes the library's own bean back off.
 * </ul>
 *
 * <p>Runs before Spring Boot's {@code WebMvcAutoConfiguration} so that, when
 * {@code spring.mvc.problemdetails.enabled=true}, Boot's own {@code ResponseEntityExceptionHandler}
 * backs off in favour of {@link GlobalExceptionHandler} instead of competing with it.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBooleanProperty(name = "blocks.error.enabled", matchIfMissing = true)
@EnableConfigurationProperties(ValidationExceptionProperties.class)
public class ErrorHandlerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "blocks.error.json.enabled", matchIfMissing = true)
    JsonExceptionHandler jsonExceptionHandler(MessageSource messageSource,
                                              ObjectProvider<JsonMapper> jsonMapper) {
        return new JsonExceptionHandler(messageSource, jsonMapper.getIfUnique(JsonMapper::shared));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "blocks.error.validation.enabled", matchIfMissing = true)
    ValidationExceptionHandler validationExceptionHandler(MessageSource messageSource,
                                                          ValidationExceptionProperties properties) {
        return new ValidationExceptionHandler(messageSource, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBooleanProperty(name = "blocks.error.global.enabled", matchIfMissing = true)
    GlobalExceptionHandler globalExceptionHandler(MessageSource messageSource) {
        return new GlobalExceptionHandler(messageSource);
    }

    // Separate configuration classes, so the bean methods (whose signatures name optional types) are
    // only introspected when the optional dependency is on the classpath.

    // spring-tx (org.springframework.dao) comes with spring-jdbc / JPA / Spring Data, not with
    // spring-webmvc: an application without data access has no DataAccessException to handle.
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
    @EnableConfigurationProperties(DataExceptionProperties.class)
    static class DataAccessConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = "blocks.error.data.enabled", matchIfMissing = true)
        DataExceptionHandler dataExceptionHandler(MessageSource messageSource, DataExceptionProperties properties) {
            return new DataExceptionHandler(messageSource, properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.core.PropertyReferenceException")
    static class SpringDataConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = "blocks.error.spring-data.enabled", matchIfMissing = true)
        SpringDataExceptionHandler springDataExceptionHandler(MessageSource messageSource) {
            return new SpringDataExceptionHandler(messageSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.validation.ConstraintViolationException")
    static class BeanValidationConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = "blocks.error.constraint-violation.enabled", matchIfMissing = true)
        ConstraintViolationExceptionHandler constraintViolationExceptionHandler(
                MessageSource messageSource, ValidationExceptionProperties properties) {
            return new ConstraintViolationExceptionHandler(messageSource, properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "jakarta.persistence.EntityNotFoundException")
    static class JpaConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBooleanProperty(name = "blocks.error.jpa.enabled", matchIfMissing = true)
        JpaExceptionHandler jpaExceptionHandler(MessageSource messageSource) {
            return new JpaExceptionHandler(messageSource);
        }
    }
}
