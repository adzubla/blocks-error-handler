package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.product.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.fasterxml.jackson.annotation.JsonProperty;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, JsonExceptionHandlerIT.JsonTestConfig.class})
class JsonExceptionHandlerIT {

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
    void validJson_doesNotTriggerHandler() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":999.00,"stock":5,"category":"ELECTRONICS"}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void validJsonWithNestedObject_doesNotTriggerHandler() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Book",
                                  "price": 49.90,
                                  "stock": 10,
                                  "address": {"street": "Av. Paulista, 1000", "city": "São Paulo"}
                                }
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.address.city").value("São Paulo"));
    }

    @Test
    void validJsonWithArrayAndMap_doesNotTriggerHandler() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Keyboard",
                                  "price": 299.00,
                                  "stock": 3,
                                  "tags": ["mechanical", "rgb"],
                                  "attributes": {"layout": "ABNT2"}
                                }
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.tags[0]").value("mechanical"))
                .andExpect(jsonPath("$.attributes.layout").value("ABNT2"));
    }

    @Test
    void validDate_doesNotTriggerHandler() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dated","price":1.00,"stock":0,"releaseDate":"2024-06-15"}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.releaseDate").value("2024-06-15"));
    }

    @Test
    void validDateTime_doesNotTriggerHandler() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Timestamped","price":1.00,"stock":0,"expiresAt":"2024-06-15T10:30:00"}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.expiresAt").value("2024-06-15T10:30:00"));
    }

    @Test
    void validInstant_doesNotTriggerHandler() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Scheduled","price":1.00,"stock":0,"scheduledAt":"2024-06-15T10:30:00Z"}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.scheduledAt").value("2024-06-15T10:30:00Z"));
    }

    // --- negative: body ---

    @Test
    void emptyBody_returnsBadRequest() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void nullRootBody_returnsBadRequest() throws Exception {
        // Jackson does not wrap null-root deserialization in a typed exception,
        // so the cause chain doesn't match any specific branch — falls through
        // to the generic handler.
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.title").value("Malformed request body"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void malformedJson_returnsBadRequestWithLocation() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid json}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.title").value("Malformed JSON"))
                .andExpect(jsonPath("$.line").isNumber())
                .andExpect(jsonPath("$.column").isNumber())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void malformedJson_largeLineNumber_isNotLocaleGrouped() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\n".repeat(1233) + "{ \"name\": }"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.line").value(1234))
                .andExpect(jsonPath("$.detail").value("Malformed JSON at line 1234, column 11"));
    }

    // --- negative: unknown field ---

    @Test
    void unknownField_returnsBadRequestWithFieldName() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"unknownField":"value"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("UNKNOWN_JSON_FIELD"))
                .andExpect(jsonPath("$.title").value("Unknown JSON field"))
                .andExpect(jsonPath("$.path").value("$.unknownField"))
                .andExpect(jsonPath("$.validValues").isArray())
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- negative: invalid enum ---

    @Test
    void invalidEnum_returnsBadRequestWithValidValues() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"category":"INVALID"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid enum value"))
                .andExpect(jsonPath("$.path").value("$.category"))
                .andExpect(jsonPath("$.invalidValue").value("INVALID"))
                .andExpect(jsonPath("$.validValues").isArray())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidEnum_numericValue_isNotLocaleGroupedInDetail() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"category":12345}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"))
                .andExpect(jsonPath("$.invalidValue").value("12345"))
                .andExpect(jsonPath("$.detail").value(containsString("value '12345' as Category")));
    }

    // --- negative: invalid field value ---

    @Test
    void invalidDecimalFormat_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":"abc","stock":0}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid field value"))
                .andExpect(jsonPath("$.path").value("$.price"))
                .andExpect(jsonPath("$.invalidValue").value("abc"))
                .andExpect(jsonPath("$.expectedType").value("BigDecimal"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidNumberFormat_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":"abc","category":"ELECTRONICS"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid field value"))
                .andExpect(jsonPath("$.path").value("$.stock"))
                .andExpect(jsonPath("$.invalidValue").value("abc"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidLongFormat_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"weight":"abc"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid field value"))
                .andExpect(jsonPath("$.path").value("$.weight"))
                .andExpect(jsonPath("$.invalidValue").value("abc"))
                .andExpect(jsonPath("$.expectedType").value("Long"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidBooleanFormat_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"active":"abc"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid field value"))
                .andExpect(jsonPath("$.path").value("$.active"))
                .andExpect(jsonPath("$.invalidValue").value("abc"))
                .andExpect(jsonPath("$.expectedType").value("Boolean"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidDateFormat_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"releaseDate":"not-a-date"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid field value"))
                .andExpect(jsonPath("$.path").value("$.releaseDate"))
                .andExpect(jsonPath("$.invalidValue").value("not-a-date"))
                .andExpect(jsonPath("$.expectedType").value("LocalDate"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidDateTimeFormat_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"expiresAt":"not-a-datetime"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid field value"))
                .andExpect(jsonPath("$.path").value("$.expiresAt"))
                .andExpect(jsonPath("$.invalidValue").value("not-a-datetime"))
                .andExpect(jsonPath("$.expectedType").value("LocalDateTime"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void invalidInstantFormat_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"scheduledAt":"not-an-instant"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.title").value("Invalid field value"))
                .andExpect(jsonPath("$.path").value("$.scheduledAt"))
                .andExpect(jsonPath("$.invalidValue").value("not-an-instant"))
                .andExpect(jsonPath("$.expectedType").value("Instant"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- negative: integer overflow ---

    @Test
    void integerOverflow_returnsBadRequestWithLocation() throws Exception {
        // Jackson 3 throws InputCoercionException (a StreamReadException subtype),
        // which carries location but not field path — expected limitation.
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":9999999999}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INTEGER_OVERFLOW"))
                .andExpect(jsonPath("$.title").value("Integer overflow"))
                .andExpect(jsonPath("$.detail", containsString("[-2147483648, 2147483647]")))
                .andExpect(jsonPath("$.validRange").value("[-2147483648, 2147483647]"))
                .andExpect(jsonPath("$.line").isNumber())
                .andExpect(jsonPath("$.column").isNumber())
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void longOverflow_returnsBadRequestWithRange() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"weight":99999999999999999999}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INTEGER_OVERFLOW"))
                .andExpect(jsonPath("$.title").value("Integer overflow"))
                .andExpect(jsonPath("$.detail", containsString("[-9223372036854775808, 9223372036854775807]")))
                .andExpect(jsonPath("$.validRange").value("[-9223372036854775808, 9223372036854775807]"))
                .andExpect(jsonPath("$.line").isNumber())
                .andExpect(jsonPath("$.column").isNumber())
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- negative: type mismatch ---

    @Test
    void arrayTypeMismatch_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"tags":"not-an-array"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.title").value("Type mismatch"))
                .andExpect(jsonPath("$.path").value("$.tags"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void arrayElementTypeMismatch_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"tags":[{"key":"val"}]}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.title").value("Type mismatch"))
                .andExpect(jsonPath("$.path").value("$.tags[0]"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void mapTypeMismatch_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"attributes":"not-an-object"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.title").value("Type mismatch"))
                .andExpect(jsonPath("$.path").value("$.attributes"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void mapValueTypeMismatch_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"attributes":{"key":{"nested":"val"}}}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.title").value("Type mismatch"))
                .andExpect(jsonPath("$.path").value("$.attributes.key"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void nestedObjectTypeMismatch_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"address":"not-an-object"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.title").value("Type mismatch"))
                .andExpect(jsonPath("$.path").value("$.address"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void nestedFieldTypeMismatch_returnsBadRequestWithPath() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"address":{"street":{"nested":"val"},"city":"SP"}}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.title").value("Type mismatch"))
                .andExpect(jsonPath("$.path").value("$.address.street"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- i18n ---

    @Test
    void unknownField_withPtBrLocale_returnsPortugueseMessages() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept-Language", "pt-BR")
                        .content("""
                                {"name":"Laptop","price":1.00,"stock":0,"unknownField":"value"}
                                """))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_JSON_FIELD"))
                .andExpect(jsonPath("$.title").value("Campo JSON desconhecido"))
                .andExpect(jsonPath("$.path").value("$.unknownField"));
    }

    @Test
    void invalidEnum_listsJsonNamesNotJavaNames() throws Exception {
        mvc.perform(post("/test/json/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_STOCK\"}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENUM_VALUE"))
                .andExpect(jsonPath("$.validValues[0]").value("in_stock"))
                .andExpect(jsonPath("$.validValues[1]").value("sold_out"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("[in_stock, sold_out]")));
    }

    @Test
    void nullForNonNullableField_returnsNullValueNotAllowed() throws Exception {
        mvc.perform(post("/test/json/non-null")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":null}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NULL_VALUE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.path").value("$.name"));
    }

    @Test
    void nullForPrimitive_returnsTypeMismatch() throws Exception {
        mvc.perform(post("/test/json/primitive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":null}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.path").value("$.count"))
                .andExpect(jsonPath("$.expectedType").value("int"));
    }

    @Test
    void absentPrimitive_returnsTypeMismatch() throws Exception {
        mvc.perform(post("/test/json/primitive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.path").value("$.count"));
    }

    @Test
    void blankBody_returnsTypeMismatchAtRoot() throws Exception {
        mvc.perform(post("/test/json/primitive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("   "))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.path").value("$"));
    }

    @Test
    void severalJsonValues_returnTypeMismatchAtRoot() throws Exception {
        mvc.perform(post("/test/json/primitive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1} {\"count\":2}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
                .andExpect(jsonPath("$.path").value("$"));
    }

    @Test
    void emptyBody_returnsMalformedRequestBody() throws Exception {
        mvc.perform(post("/test/json/primitive")
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"));
    }

    @Test
    void unknownPolymorphicTypeId_returnsInvalidTypeId() throws Exception {
        mvc.perform(post("/test/json/animal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"fish\"}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TYPE_ID"))
                .andExpect(jsonPath("$.invalidValue").value("fish"))
                .andExpect(jsonPath("$.expectedType").value("Animal"));
    }

    @Test
    void constructorRejectingValue_returnsInvalidFieldValue() throws Exception {
        mvc.perform(post("/test/json/picky")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD_VALUE"))
                .andExpect(jsonPath("$.expectedType").value("PickyRequest"));
    }

    @Test
    void undeserializableTargetType_returns500() throws Exception {
        mvc.perform(post("/test/json/abstract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":1}"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void unprovidedInjectableValue_returns500() throws Exception {
        mvc.perform(post("/test/json/injectable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"a\":1}"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    enum StockStatus {
        @JsonProperty("in_stock") IN_STOCK,
        @JsonProperty("sold_out") SOLD_OUT
    }

    record StockRequest(StockStatus status) {
    }

    record NonNullRequest(@com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.FAIL)
                          String name) {
    }

    record PrimitiveRequest(int count) {
    }

    @com.fasterxml.jackson.annotation.JsonTypeInfo(use = com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME, property = "type")
    @com.fasterxml.jackson.annotation.JsonSubTypes(@com.fasterxml.jackson.annotation.JsonSubTypes.Type(value = Dog.class, name = "dog"))
    interface Animal {
    }

    record Dog() implements Animal {
    }

    record PickyRequest(String name) {
        PickyRequest {
            throw new IllegalArgumentException("rejected");
        }
    }

    interface Unmappable {
    }

    record InjectableRequest(@com.fasterxml.jackson.annotation.JacksonInject String dep) {
    }

    @TestConfiguration
    static class JsonTestConfig {
        @Bean
        StockController stockController() {
            return new StockController();
        }
    }

    @RestController
    static class StockController {
        @PostMapping("/test/json/stock")
        void stock(@RequestBody StockRequest request) {
        }

        @PostMapping("/test/json/non-null")
        void nonNull(@RequestBody NonNullRequest request) {
        }

        @PostMapping("/test/json/primitive")
        void primitive(@RequestBody PrimitiveRequest request) {
        }

        @PostMapping("/test/json/animal")
        void animal(@RequestBody Animal request) {
        }

        @PostMapping("/test/json/picky")
        void picky(@RequestBody PickyRequest request) {
        }

        @PostMapping("/test/json/injectable")
        void injectable(@RequestBody InjectableRequest request) {
        }

        @PostMapping("/test/json/abstract")
        void unmappable(@RequestBody Unmappable request) {
        }
    }
}
