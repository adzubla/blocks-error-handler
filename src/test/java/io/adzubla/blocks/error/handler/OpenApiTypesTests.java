package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiTypesTests {

    enum Color {RED}

    record Account(String name) {
    }

    static Stream<Arguments> types() {
        return Stream.of(
                Arguments.of(boolean.class, "boolean"), Arguments.of(Boolean.class, "boolean"),
                Arguments.of(int.class, "integer (int32)"), Arguments.of(Integer.class, "integer (int32)"),
                Arguments.of(long.class, "integer (int64)"), Arguments.of(Long.class, "integer (int64)"),
                Arguments.of(short.class, "integer"), Arguments.of(Byte.class, "integer"),
                Arguments.of(BigInteger.class, "integer"),
                Arguments.of(float.class, "number (float)"), Arguments.of(Double.class, "number (double)"),
                Arguments.of(BigDecimal.class, "number"),
                Arguments.of(String.class, "string"), Arguments.of(char.class, "string"),
                Arguments.of(Color.class, "string"), Arguments.of(LocalTime.class, "string"),
                Arguments.of(Duration.class, "string"),
                Arguments.of(LocalDate.class, "string (date)"),
                Arguments.of(LocalDateTime.class, "string (date-time)"), Arguments.of(Instant.class, "string (date-time)"),
                Arguments.of(OffsetDateTime.class, "string (date-time)"), Arguments.of(Date.class, "string (date-time)"),
                Arguments.of(UUID.class, "string (uuid)"), Arguments.of(URI.class, "string (uri)"),
                Arguments.of(byte[].class, "string (byte)"),
                Arguments.of(List.class, "array"), Arguments.of(Set.class, "array"), Arguments.of(String[].class, "array"),
                Arguments.of(Map.class, "object"), Arguments.of(Account.class, "object"), Arguments.of(Object.class, "object"));
    }

    @ParameterizedTest
    @MethodSource("types")
    void javaType_isNamedAsInAnOpenApiSchema(Class<?> type, String expected) {
        assertThat(OpenApiTypes.of(type)).isEqualTo(expected);
    }

    @Test
    void unknownType_hasNoName() {
        assertThat(OpenApiTypes.of(null)).isNull();
    }
}
