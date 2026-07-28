package com.example.demo.product;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;

@Service
class ProductService {

    private final ProductRepository repository;

    ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    @Transactional
    ProductResponse create(ProductRequest request) {
        var product = new Product();
        product.setName(request.name());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategory(request.category());
        product.setTags(request.tags() != null ? new ArrayList<>(request.tags()) : new ArrayList<>());
        product.setAttributes(request.attributes() != null ? new HashMap<>(request.attributes()) : new HashMap<>());
        product.setAddress(request.address());
        product.setWeight(request.weight());
        product.setActive(request.active());
        product.setReleaseDate(request.releaseDate());
        product.setExpiresAt(request.expiresAt());
        product.setScheduledAt(request.scheduledAt());
        return toResponse(repository.save(product));
    }

    ProductResponse findById(Long id) {
        return repository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(
                p.getId(), p.getName(), p.getPrice(), p.getStock(),
                p.getCategory(), p.getTags(), p.getAttributes(), p.getAddress(), p.getWeight(), p.getActive(),
                p.getReleaseDate(), p.getExpiresAt(), p.getScheduledAt()
        );
    }
}
