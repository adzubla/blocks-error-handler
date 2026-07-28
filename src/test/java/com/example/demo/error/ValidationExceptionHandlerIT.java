package com.example.demo.error;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.product.ProductRepository;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
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
    void nullRejectedValue_serializedAsLiteralNullString() throws Exception {
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
                        hasItem("null")))
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
                .andExpect(jsonPath("$.traceId").isString());
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
}
