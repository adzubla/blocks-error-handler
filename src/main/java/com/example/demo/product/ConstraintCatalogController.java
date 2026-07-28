package com.example.demo.product;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/constraint-tests")
class ConstraintCatalogController {

    @PostMapping
    void validate(@RequestBody @Valid ConstraintCatalogRequest request) {
    }
}
