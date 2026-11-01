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

    private final Violations violations = new Violations(new ValidationExceptionProperties().getSensitiveFields());

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
                .containsOnlyKeys("path", "message")
                .containsEntry("path", "$.x")
                .containsEntry("message", "bad");
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
        assertThat(violations.of(path, "hunter2", "bad")).containsOnlyKeys("path", "message");
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.name", "$.email", "$.key", "$.pass", "$.tags[1]", "$"})
    void otherPath_keepsTheValue(String path) {
        assertThat(violations.of(path, "value", "bad")).containsEntry("invalidValue", "value");
    }

    @Test
    void sensitiveNullValue_isOmittedToo() {
        assertThat(violations.of("$.password", null, "bad")).containsOnlyKeys("path", "message");
    }

    @Test
    void configuredWords_areNormalizedLikeThePath() {
        var custom = new Violations(List.of("Credit-Card"));

        assertThat(custom.of("$.creditCard", "4111", "bad")).containsOnlyKeys("path", "message");
        assertThat(custom.of("$.credit_card_number", "4111", "bad")).containsOnlyKeys("path", "message");
        assertThat(custom.of("$.password", "x", "bad")).containsEntry("invalidValue", "x");
    }

    @Test
    void blankWord_doesNotHideEveryValue() {
        var custom = new Violations(List.of("", " ", "-"));

        assertThat(custom.of("$.name", "x", "bad")).containsEntry("invalidValue", "x");
    }
}
