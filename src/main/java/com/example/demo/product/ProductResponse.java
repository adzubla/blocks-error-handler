package com.example.demo.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record ProductResponse(
        Long id,
        String name,
        BigDecimal price,
        Integer stock,
        Category category,
        List<String> tags,
        Map<String, String> attributes,
        Address address,
        Long weight,
        Boolean active,
        LocalDate releaseDate,
        LocalDateTime expiresAt,
        Instant scheduledAt
) {
}
