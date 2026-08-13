package com.example.demo.product;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ConstraintCatalogRequest(
        @AssertFalse Boolean assertFalseField,
        @AssertTrue Boolean assertTrueField,
        @DecimalMax("99.99") BigDecimal decimalMaxField,
        @DecimalMin("1.00") BigDecimal decimalMinField,
        @Digits(integer = 3, fraction = 2) BigDecimal digitsField,
        @Email String emailField,
        @Future LocalDate futureField,
        @FutureOrPresent LocalDate futureOrPresentField,
        @Max(100) Integer maxField,
        @Min(10) Integer minField,
        @Negative Integer negativeField,
        @NegativeOrZero Integer negativeOrZeroField,
        @NotBlank String notBlankField,
        @NotEmpty String notEmptyField,
        @NotNull String notNullField,
        @Null String nullField,
        @Past LocalDate pastField,
        @PastOrPresent LocalDate pastOrPresentField,
        @Pattern(regexp = "[A-Z]{2}-\\d{4}") String patternField,
        @Positive BigDecimal positiveField,
        @PositiveOrZero Integer positiveOrZeroField,
        @Size(min = 2, max = 8) String sizeField
) {
}
