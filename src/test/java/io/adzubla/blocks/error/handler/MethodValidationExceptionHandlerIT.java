package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * With {@code adapt-constraint-violations=true}, {@code @Validated} beans throw Spring's
 * {@code MethodValidationException} instead of {@code ConstraintViolationException}.
 */
@SpringBootTest(classes = DemoApplication.class,
        properties = "spring.validation.method.adapt-constraint-violations=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MethodValidationExceptionHandlerIT.Config.class})
class MethodValidationExceptionHandlerIT {

    @Autowired
    MockMvc mvc;

    @Test
    void blankArgument_returns422WithParameterPathAndValue() throws Exception {
        mvc.perform(post("/test/method-validation/scalar"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].path").value("$.value"))
                .andExpect(jsonPath("$.violations[0].invalidValue").value(""));
    }

    @Test
    void invalidBeanArgument_returns422WithFieldPath() throws Exception {
        mvc.perform(post("/test/method-validation/bean"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].path").value("$.name"));
    }

    @Test
    void blankContainerElement_returns422WithIndexedPath() throws Exception {
        mvc.perform(post("/test/method-validation/list"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$.items[1]"));
    }

    @Test
    void returnValueViolation_returns500() throws Exception {
        mvc.perform(post("/test/method-validation/return-value"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    @Test
    void nestedClassLevelConstraint_reportsPropertyPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/method-validation/box"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$.locked"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    @Test
    void sensitiveParameter_omitsInvalidValue() throws Exception {
        mvc.perform(post("/test/method-validation/secret"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$.clientSecret"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    // Spring only raises an exception when a parameter has a violation. Documented in the README.
    @Test
    void crossParameterConstraintAlone_isNotEnforced() throws Exception {
        mvc.perform(post("/test/method-validation/cross"))
                .andDo(print())
                .andExpect(status().isOk());
    }

    record Named(@NotBlank String name) {
    }

    record Box(@Valid Locked locked) {
    }

    @AlwaysInvalid
    record Locked(String secret) {
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AlwaysInvalidValidator.class)
    @interface AlwaysInvalid {
        String message() default "always invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AlwaysInvalidParametersValidator.class)
    @interface AlwaysInvalidParameters {
        String message() default "always invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class AlwaysInvalidValidator implements ConstraintValidator<AlwaysInvalid, Locked> {
        @Override
        public boolean isValid(Locked value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    public static class AlwaysInvalidParametersValidator implements ConstraintValidator<AlwaysInvalidParameters, Object[]> {
        @Override
        public boolean isValid(Object[] value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Validated
    static class MethodValidatedService {

        void requireValidBox(@Valid Box box) {
        }

        void requireLongSecret(@Size(min = 8) String clientSecret) {
        }

        @AlwaysInvalidParameters
        void crossParameter(String first, String second) {
        }

        void requireNonBlank(@NotBlank String value) {
        }

        void requireValid(@Valid Named named) {
        }

        void requireNonBlankItems(List<@NotBlank String> items) {
        }

        @NotNull
        String brokenLookup() {
            return null;
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        MethodValidatedService methodValidatedService() {
            return new MethodValidatedService();
        }

        @Bean
        MethodValidationController methodValidationController(MethodValidatedService service) {
            return new MethodValidationController(service);
        }
    }

    @RestController
    @RequestMapping("/test/method-validation")
    static class MethodValidationController {

        private final MethodValidatedService service;

        MethodValidationController(MethodValidatedService service) {
            this.service = service;
        }

        @PostMapping("/scalar")
        void scalar() {
            service.requireNonBlank("");
        }

        @PostMapping("/bean")
        void bean() {
            service.requireValid(new Named(""));
        }

        @PostMapping("/list")
        void list() {
            service.requireNonBlankItems(List.of("ok", ""));
        }

        @PostMapping("/return-value")
        void returnValue() {
            service.brokenLookup();
        }

        @PostMapping("/secret")
        void secret() {
            service.requireLongSecret("hunter2");
        }

        @PostMapping("/box")
        void box() {
            service.requireValidBox(new Box(new Locked("hunter2")));
        }

        @PostMapping("/cross")
        void cross() {
            service.crossParameter("a", "b");
        }
    }
}
