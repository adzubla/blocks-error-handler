package com.example.demo.product;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ProductRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive BigDecimal price,
        @NotNull @Min(0) Integer stock,
        Category category,
        List<String> tags,
        Map<String, String> attributes,
        @Valid Address address,
        Long weight,
        Boolean active,
        LocalDate releaseDate,
        LocalDateTime expiresAt,
        Instant scheduledAt
) {
}
