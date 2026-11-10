package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ViolationsTests {

    private static final ErrorMessages MESSAGES =
            new ErrorMessages(new org.springframework.context.support.StaticMessageSource());

    private final Violations violations = new Violations(MESSAGES, new ValidationExceptionProperties().getSensitiveFields());

    enum Color {RED}

    record Account(String password) {
    }

    static Stream<Object> simpleValues() {
        return Stream.of("text", "", 1, 2.5, new BigDecimal("1.50"), true, 'c', Color.RED,
                LocalDate.of(2026, 1, 2), UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @ParameterizedTest
    @MethodSource("simpleValues")
    void simpleValue_isReturnedAsString(Object value) {
        assertThat(violations.of("$.x", value, "bad")).containsEntry("invalidValue", String.valueOf(value));
    }

    // Wrapped, because JUnit would otherwise spread the String[] into separate arguments.
    static Stream<Arguments> complexValues() {
        return Stream.<Object>of(new Account("hunter2"), List.of("hunter2"), Set.of("hunter2"), Map.of("k", "hunter2"),
                        new String[]{"hunter2"}, new int[]{1}, new Object())
                .map(value -> Arguments.of(value));
    }

    @ParameterizedTest
    @MethodSource("complexValues")
    void complexValue_isOmitted(Object value) {
        assertThat(violations.of("$.x", value, "bad"))
                .containsOnlyKeys("path", "detail")
                .containsEntry("path", "$.x")
                .containsEntry("detail", "bad");
    }

    @Test
    void nullValue_isReturnedAsJsonNull() {
        assertThat(violations.of("$.x", null, "bad")).containsEntry("invalidValue", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.password", "$.PASSWORD", "$.newPassword", "$.confirm_password", "$.user-passwd",
            "$.pwd", "$.clientSecret", "$.accessToken", "$.api_key", "$.api-key", "$.apiKey", "$.private_key",
            "$.credentials.pin", "$.user.password", "$.attributes[token]", "$.passwords[1]"})
    void sensitivePath_omitsTheValue(String path) {
        assertThat(violations.of(path, "hunter2", "bad")).containsOnlyKeys("path", "detail");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.name", "$.email", "$.key", "$.pass", "$.tags[1]", "$"})
    void otherPath_keepsTheValue(String path) {
        assertThat(violations.of(path, "value", "bad")).containsEntry("invalidValue", "value");
    }

    @Test
    void sensitiveNullValue_isOmittedToo() {
        assertThat(violations.of("$.password", null, "bad")).containsOnlyKeys("path", "detail");
    }

    @Test
    void configuredWords_areNormalizedLikeThePath() {
        var custom = new Violations(MESSAGES, List.of("Credit-Card"));

        assertThat(custom.of("$.creditCard", "4111", "bad")).containsOnlyKeys("path", "detail");
        assertThat(custom.of("$.credit_card_number", "4111", "bad")).containsOnlyKeys("path", "detail");
        assertThat(custom.of("$.password", "x", "bad")).containsEntry("invalidValue", "x");
    }

    @Test
    void blankWord_doesNotHideEveryValue() {
        var custom = new Violations(MESSAGES, List.of("", " ", "-"));

        assertThat(custom.of("$.name", "x", "bad")).containsEntry("invalidValue", "x");
    }

    @Test
    void bindingFailure_namesTheExpectedOpenApiType() {
        var error = new org.springframework.validation.FieldError("form", "age", "abc", true, null, null,
                "Failed to convert property value of type 'java.lang.String' to required type 'int'");
        error.wrap(new org.springframework.beans.TypeMismatchException("abc", int.class));

        assertThat(violations.of("$", error))
                .containsEntry("invalidValue", "abc")
                .containsEntry("expectedType", "integer (int32)")
                .containsEntry("detail", "Invalid value — expected integer (int32)");
    }

    @Test
    void bindingFailure_withoutAKnownType_getsAGenericMessage() {
        var error = new org.springframework.validation.FieldError("form", "age", "abc", true, null, null,
                "Failed to convert property value of type 'java.lang.String' to required type 'int'");

        assertThat(violations.of("$", error))
                .containsEntry("path", "$.age")
                .containsEntry("invalidValue", "abc")
                .containsEntry("detail", "Invalid value");
    }

    @Test
    void summary_listsPathsMessagesAndRejectedValues() {
        var summary = Violations.summary(List.of(
                violations.of("$.name", "", "must not be blank"),
                violations.of("$.price", -1, "must be greater than 0"),
                violations.of("$.stock", null, "must not be null")));

        assertThat(summary).isEqualTo("$.name: must not be blank (rejected value: ''); "
                + "$.price: must be greater than 0 (rejected value: '-1'); "
                + "$.stock: must not be null (rejected value: null)");
    }

    @Test
    void summary_leavesOutSensitiveAndComplexValues() {
        var summary = Violations.summary(List.of(
                violations.of("$.password", "hunter2", "too short"),
                violations.of("$.items", List.of("hunter2"), "too many")));

        assertThat(summary).isEqualTo("$.password: too short; $.items: too many");
    }
}
