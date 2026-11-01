package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.product.ProductRepository;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ValidationExceptionHandlerIT.ConstraintViolationTestConfig.class})
class ValidationExceptionHandlerIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductRepository repository;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    // --- positive ---

    @Test
    void validRequest_doesNotTriggerHandler() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"category":"ELECTRONICS"}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    @Test
    void nameAtMaxLength_isAccepted() throws Exception {
        String maxName = "A".repeat(100);
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","price":1.00,"stock":0}
                                """.formatted(maxName)))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.name").value(maxName));
    }

    @Test
    void zeroStock_isValidMinimum() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"OutOfStock","price":0.01,"stock":0}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.stock").value(0));
    }

    @Test
    void validNestedAddress_isAccepted() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Delivered","price":10.00,"stock":1,
                                 "address":{"street":"Rua A, 1","city":"SP"}}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.address.street").value("Rua A, 1"));
    }

    // --- negative: single field violations ---

    @Test
    void blankName_returnsUnprocessableWithViolation() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":1.00,"stock":0,"category":"ELECTRONICS"}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations[?(@.path=='$.name')]").exists())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void nullPrice_returnsViolationForNullField() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","stock":0}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.price')]").exists())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void nameTooLong_returnsViolationWithMessage() throws Exception {
        String longName = "A".repeat(101);
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","price":1.00,"stock":0}
                                """.formatted(longName)))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.name')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.name')].message").exists())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidNestedAddress_returnsNestedFieldViolation() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"address":{"street":"","city":"SP"}}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.address.street')]").exists())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void bothNestedAddressFields_invalid_returnsBothViolations() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"address":{"street":"","city":""}}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.address.street')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.address.city')]").exists())
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- negative: multiple violations ---

    @Test
    void negativePriceAndStock_returnsAllViolations() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":-1.00,"stock":-5,"category":"ELECTRONICS"}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.price')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.stock')]").exists())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void multipleViolations_returnsAllInList() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":-1,"stock":-1}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations.length()").value(Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- negative: response shape ---

    @Test
    void invalidValue_isPresentForRejectedField() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":1.00,"stock":0}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.name')].invalidValue",
                        hasItem("")))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void nullRejectedValue_serializedAsJsonNull() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","stock":0}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.price')].invalidValue",
                        hasItem(Matchers.nullValue())))
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- HandlerMethodValidationException (path variable constraint) ---

    @Test
    void nonPositivePathVariable_triggersHandlerMethodValidation() throws Exception {
        mvc.perform(get("/products/{id}", -1))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations[?(@.path=='$.id')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.id')].invalidValue", hasItem("-1")))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void constrainedContainerElement_reportsIndexedPathAndElementValue() throws Exception {
        mvc.perform(get("/test/constraint-violation/tags").param("tags", "ok", ""))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].path").value("$.tags[1]"))
                .andExpect(jsonPath("$.violations[0].invalidValue").value(""));
    }

    @Test
    void classLevelConstraintAlongsideParameterConstraint_reportsRootPath() throws Exception {
        mvc.perform(post("/test/constraint-violation/mixed").param("page", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min\":5,\"max\":1}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].path").value("$"))
                .andExpect(jsonPath("$.violations[0].message").value("min must not exceed max"));
    }

    @Test
    void bodyFieldAndParameterConstraint_reportBothPaths() throws Exception {
        mvc.perform(post("/test/constraint-violation/mixed-named").param("page", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[?(@.path=='$.name')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.page')].invalidValue", hasItem("0")));
    }

    @Test
    void classLevelConstraint_reportsRootPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min\":5,\"max\":1}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].path").value("$"))
                .andExpect(jsonPath("$.violations[0].message").value("min must not exceed max"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotExist());
    }

    // --- object-level constraints never echo the object ---

    @Test
    void nestedClassLevelConstraintOnBody_reportsPropertyPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/box")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"range\":{\"min\":5,\"max\":1}}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].path").value("$.range"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    @Test
    void nestedClassLevelConstraintWithMethodValidation_reportsPropertyPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/box-mixed").param("page", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"range\":{\"min\":5,\"max\":1}}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[?(@.path=='$.range')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.range')].invalidValue").isEmpty())
                .andExpect(jsonPath("$.violations[?(@.path=='$.page')].invalidValue", hasItem("0")));
    }

    @Test
    void crossParameterConstraintAlongsideParameterConstraint_reportsRootPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/cross-mixed")
                        .param("min", "5").param("max", "1").param("page", "0"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations.length()").value(2))
                .andExpect(jsonPath("$.violations[?(@.path=='$')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$')].invalidValue").isEmpty())
                .andExpect(jsonPath("$.violations[?(@.path=='$.page')]").exists());
    }

    // Spring only raises an exception when a parameter has a violation. Documented in the README.
    @Test
    void crossParameterConstraintAlone_isNotEnforcedOnController() throws Exception {
        mvc.perform(post("/test/constraint-violation/cross-mixed")
                        .param("min", "5").param("max", "1").param("page", "1"))
                .andDo(print())
                .andExpect(status().isOk());
    }

    @Test
    void classLevelConstraintOnServiceParameter_reportsRootPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/service-range"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].path").value("$"))
                .andExpect(jsonPath("$.violations[0].message").value("min must not exceed max"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    @Test
    void nestedClassLevelConstraintOnServiceParameter_reportsPropertyPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/service-box"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$.range"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    @Test
    void classLevelConstraintOnServiceContainerElement_reportsIndexedPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/service-ranges"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$[1]"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    @Test
    void crossParameterConstraintOnService_reportsRootPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/service-cross"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations.length()").value(1))
                .andExpect(jsonPath("$.violations[0].path").value("$"))
                .andExpect(jsonPath("$.violations[0].message").value("arguments out of order"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    @Test
    void classLevelConstraintFromDirectValidatorCall_reportsRootPathWithoutInvalidValue() throws Exception {
        mvc.perform(post("/test/constraint-violation/direct-range"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$"))
                .andExpect(jsonPath("$.violations[0].invalidValue").doesNotHaveJsonPath());
    }

    @Test
    void constraintMessageParameter_isResolvedFromApplicationMessageSource() throws Exception {
        mvc.perform(post("/test/constraint-violation/bundle-message"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].message").value("Widget not found"));
    }

    @Test
    void controllerReturnValueViolation_returnsInternalServerError() throws Exception {
        mvc.perform(post("/test/constraint-violation/controller-return-value"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    // --- ConstraintViolationException (@Validated on a non-controller bean) ---

    @Test
    void serviceLayerConstraintViolation_returnsUnprocessableWithViolation() throws Exception {
        mvc.perform(post("/test/constraint-violation"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.violations").isArray())
                .andExpect(jsonPath("$.violations[0].path").value("$.value"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void serviceLayerConstraintViolation_onBeanParameterProperty_reportsPropertyPath() throws Exception {
        mvc.perform(post("/test/constraint-violation/bean"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].path").value("$.name"));
    }

    @Test
    void serviceLayerConstraintViolation_onContainerElement_reportsIndexedPath() throws Exception {
        mvc.perform(post("/test/constraint-violation/list"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].path").value("$.items[1]"));
    }

    @Test
    void serviceLayerReturnValueViolation_returnsInternalServerError() throws Exception {
        mvc.perform(post("/test/constraint-violation/return-value"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.violations").doesNotExist())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void constraintViolationWithoutViolations_returnsUnprocessableWithEmptyViolations() throws Exception {
        mvc.perform(post("/test/constraint-violation/no-violations"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations").isEmpty());
    }

    // --- i18n ---

    @Test
    void blankName_withPtBrLocale_returnsPortugueseMessages() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "pt-BR")
                        .content("""
                                {"name":"","price":1.00,"stock":0}
                                """))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.title").value("Falha na validação"))
                .andExpect(jsonPath("$.detail").value("Um ou mais campos falharam na validação"));
    }

    // --- Jakarta built-in constraint catalog ---
    // Each test targets exactly one constraint; notNullField/notBlankField/notEmptyField
    // are always satisfied so they don't pollute unrelated violation lists.
    //
    // Message assertions use hasItem so the JsonPath filter result (always a List)
    // is matched correctly against the Hamcrest collection matcher.

    private static final String REQUIRED_FIELDS =
            "\"notNullField\":\"x\",\"notBlankField\":\"y\",\"notEmptyField\":\"z\"";

    @Test
    void assertFalse_violation_rejectsTrueValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"assertFalseField\":true}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.assertFalseField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.assertFalseField')].message",
                        hasItem(containsString("false"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void assertTrue_violation_rejectsFalseValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"assertTrueField\":false}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.assertTrueField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.assertTrueField')].message",
                        hasItem(containsString("true"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void decimalMax_violation_rejectsValueAboveMax() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"decimalMaxField\":100.00}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.decimalMaxField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.decimalMaxField')].message",
                        hasItem(containsString("99.99"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void decimalMin_violation_rejectsValueBelowMin() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"decimalMinField\":0.50}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.decimalMinField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.decimalMinField')].message",
                        hasItem(containsString("1.00"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void digits_violation_rejectsValueOutOfBounds() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"digitsField\":1234.567}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.digitsField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.digitsField')].message",
                        hasItem(containsString("<3 digits>.<2 digits>"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void email_violation_rejectsMalformedAddress() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"emailField\":\"not-valid\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.emailField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.emailField')].message",
                        hasItem(containsString("email"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void future_violation_rejectsPastDate() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"futureField\":[2000,1,1]}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.futureField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.futureField')].message",
                        hasItem(containsString("future"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void futureOrPresent_violation_rejectsPastDate() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"futureOrPresentField\":[2000,1,1]}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.futureOrPresentField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.futureOrPresentField')].message",
                        hasItem(containsString("future"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void max_violation_rejectsValueAboveMax() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"maxField\":101}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.maxField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.maxField')].message",
                        hasItem(containsString("100"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void min_violation_rejectsValueBelowMin() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"minField\":9}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.minField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.minField')].message",
                        hasItem(containsString("10"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void negative_violation_rejectsPositiveValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"negativeField\":1}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.negativeField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.negativeField')].message",
                        hasItem(containsString("0"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void negativeOrZero_violation_rejectsPositiveValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"negativeOrZeroField\":1}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.negativeOrZeroField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.negativeOrZeroField')].message",
                        hasItem(containsString("0"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void notBlank_violation_rejectsWhitespaceOnly() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notNullField\":\"x\",\"notEmptyField\":\"z\",\"notBlankField\":\"   \"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.notBlankField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.notBlankField')].message",
                        hasItem(containsString("blank"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void notEmpty_violation_rejectsEmptyString() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notNullField\":\"x\",\"notBlankField\":\"y\",\"notEmptyField\":\"\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.notEmptyField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.notEmptyField')].message",
                        hasItem(containsString("empty"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void notNull_violation_rejectsAbsentField() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notBlankField\":\"y\",\"notEmptyField\":\"z\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.notNullField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.notNullField')].message",
                        hasItem(containsString("null"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void null_violation_rejectsNonNullValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"nullField\":\"should-be-absent\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.nullField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.nullField')].message",
                        hasItem(containsString("null"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void past_violation_rejectsFutureDate() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"pastField\":[2099,12,31]}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.pastField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.pastField')].message",
                        hasItem(containsString("past"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void pastOrPresent_violation_rejectsFutureDate() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"pastOrPresentField\":[2099,12,31]}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.pastOrPresentField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.pastOrPresentField')].message",
                        hasItem(containsString("past"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void pattern_violation_rejectsNonMatchingValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"patternField\":\"invalid-123\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.patternField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.patternField')].message",
                        hasItem(containsString("[A-Z]{2}-"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void positive_violation_rejectsNonPositiveValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"positiveField\":-1}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.positiveField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.positiveField')].message",
                        hasItem(containsString("0"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void positiveOrZero_violation_rejectsNegativeValue() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"positiveOrZeroField\":-1}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.positiveOrZeroField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.positiveOrZeroField')].message",
                        hasItem(containsString("0"))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void size_violation_rejectsValueOutsideBounds() throws Exception {
        mvc.perform(post("/constraint-tests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + REQUIRED_FIELDS + ",\"sizeField\":\"x\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[?(@.path=='$.sizeField')]").exists())
                .andExpect(jsonPath("$.violations[?(@.path=='$.sizeField')].message",
                        hasItem(Matchers.allOf(containsString("2"), containsString("8")))))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @TestConfiguration
    static class ConstraintViolationTestConfig {

        @Bean
        ValidatedService validatedService() {
            return new ValidatedService();
        }

        @Bean
        ConstraintViolationTriggerController constraintViolationTriggerController(ValidatedService service,
                                                                                  Validator validator) {
            return new ConstraintViolationTriggerController(service, validator);
        }
    }

    @Validated
    static class ValidatedService {

        void requireNonBlank(@NotBlank String value) {
        }

        void requireValid(@Valid Named named) {
        }

        void requireNonBlankItems(List<@NotBlank String> items) {
        }

        void requireValidRange(@Valid Range range) {
        }

        void requireBundleMessage(@NotBlank(message = "{test.widget-not-found}") String value) {
        }

        void requireValidBox(@Valid Box box) {
        }

        void requireValidRanges(List<@Valid Range> ranges) {
        }

        @ArgumentsInOrder
        void requireOrdered(int min, int max) {
        }

        @NotNull
        String brokenLookup() {
            return null;
        }
    }

    record Named(@NotBlank String name) {
    }

    record Box(@Valid Range range) {
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = ArgumentsInOrderValidator.class)
    @interface ArgumentsInOrder {
        String message() default "arguments out of order";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    @SupportedValidationTarget(ValidationTarget.PARAMETERS)
    public static class ArgumentsInOrderValidator implements ConstraintValidator<ArgumentsInOrder, Object[]> {
        @Override
        public boolean isValid(Object[] arguments, ConstraintValidatorContext context) {
            return (int) arguments[0] <= (int) arguments[1];
        }
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = OrderedRangeValidator.class)
    @interface OrderedRange {
        String message() default "min must not exceed max";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class OrderedRangeValidator implements ConstraintValidator<OrderedRange, Range> {
        @Override
        public boolean isValid(Range range, ConstraintValidatorContext context) {
            return range.min() <= range.max();
        }
    }

    @OrderedRange
    record Range(int min, int max) {
    }

    @RestController
    @RequestMapping("/test/constraint-violation")
    static class ConstraintViolationTriggerController {

        private final ValidatedService service;
        private final Validator validator;

        ConstraintViolationTriggerController(ValidatedService service, Validator validator) {
            this.service = service;
            this.validator = validator;
        }

        @PostMapping("/box")
        void box(@RequestBody @Valid Box box) {
        }

        @PostMapping("/box-mixed")
        void boxMixed(@RequestBody @Valid Box box, @RequestParam @Min(1) int page) {
        }

        @ArgumentsInOrder
        @PostMapping("/cross-mixed")
        void crossMixed(@RequestParam int min, @RequestParam int max, @RequestParam @Min(1) int page) {
        }

        @PostMapping("/bundle-message")
        void bundleMessage() {
            service.requireBundleMessage("");
        }

        @PostMapping("/service-range")
        void serviceRange() {
            service.requireValidRange(new Range(5, 1));
        }

        @PostMapping("/service-box")
        void serviceBox() {
            service.requireValidBox(new Box(new Range(5, 1)));
        }

        @PostMapping("/service-ranges")
        void serviceRanges() {
            service.requireValidRanges(List.of(new Range(1, 5), new Range(5, 1)));
        }

        @PostMapping("/service-cross")
        void serviceCross() {
            service.requireOrdered(5, 1);
        }

        @PostMapping("/direct-range")
        void directRange() {
            throw new ConstraintViolationException(validator.validate(new Range(5, 1)));
        }

        // @Validated service method called with a blank arg → ConstraintViolationException →
        // handleConstraintViolation
        @PostMapping
        void trigger() {
            service.requireNonBlank("");
        }

        @PostMapping("/bean")
        void triggerBean() {
            service.requireValid(new Named(""));
        }

        @PostMapping("/return-value")
        void triggerReturnValue() {
            service.brokenLookup();
        }

        @GetMapping("/tags")
        void tags(@RequestParam List<@NotBlank String> tags) {
        }

        // A constraint on another parameter turns @Valid on the body into method validation.
        @PostMapping("/mixed")
        void mixed(@RequestBody @Valid Range range, @RequestParam @Min(1) int page) {
        }

        @PostMapping("/mixed-named")
        void mixedNamed(@RequestBody @Valid Named named, @RequestParam @Min(1) int page) {
        }

        @PostMapping("/range")
        void range(@RequestBody @Valid Range range) {
        }

        // Spring MVC's built-in method validation of the return value → HandlerMethodValidationException
        @PostMapping("/controller-return-value")
        @NotNull
        String brokenControllerLookup() {
            return null;
        }

        @PostMapping("/list")
        void triggerList() {
            service.requireNonBlankItems(List.of("ok", ""));
        }

        // Built with a message only → getConstraintViolations() returns null
        @PostMapping("/no-violations")
        void triggerNoViolations() {
            throw new ConstraintViolationException("no violations", null);
        }
    }
}
