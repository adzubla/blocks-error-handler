package com.example.demo.product;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;

@Embeddable
public record Address(
        @NotBlank String street,
        @NotBlank String city
) {
}
