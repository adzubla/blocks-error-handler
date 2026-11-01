package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ViolationsTests {

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
        assertThat(Violations.of("$.x", value, "bad")).containsEntry("invalidValue", String.valueOf(value));
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
        assertThat(Violations.of("$.x", value, "bad"))
                .containsOnlyKeys("path", "message")
                .containsEntry("path", "$.x")
                .containsEntry("message", "bad");
    }

    @Test
    void nullValue_isReturnedAsJsonNull() {
        assertThat(Violations.of("$.x", null, "bad")).containsEntry("invalidValue", null);
    }
}
