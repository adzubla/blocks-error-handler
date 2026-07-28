package com.example.demo.error;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.product.Product;
import com.example.demo.product.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DataExceptionHandlerIT {

    @Autowired
    MockMvc mvc;

    // --- 404 not found ---

    @Test
    void entityNotFound_returnsNotFound() throws Exception {
        mvc.perform(post("/test/exceptions/not-found"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void emptyResult_returnsNotFound() throws Exception {
        mvc.perform(post("/test/exceptions/empty-result"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("The requested resource was not found."))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- 409 conflict ---

    @Test
    void jpaUniqueConstraintViolation_returnsConflict() throws Exception {
        mvc.perform(post("/test/exceptions/unique-violation"))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.title").value("Duplicate value"))
                .andExpect(jsonPath("$.detail").value("A resource with the same value already exists."))
                .andExpect(jsonPath("$.code").value("DUPLICATE_VALUE"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void jdbcDuplicateKey_returnsConflict() throws Exception {
        mvc.perform(post("/test/exceptions/duplicate-key"))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.title").value("Duplicate value"))
                .andExpect(jsonPath("$.detail").value("A resource with the same value already exists."))
                .andExpect(jsonPath("$.code").value("DUPLICATE_VALUE"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void foreignKeyViolation_returnsConflict() throws Exception {
        mvc.perform(post("/test/exceptions/fk-violation"))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.title").value("Referential integrity violation"))
                .andExpect(jsonPath("$.detail").value("The request references a resource that does not exist."))
                .andExpect(jsonPath("$.code").value("REFERENTIAL_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void dataIntegrityFallback_returnsConflict() throws Exception {
        mvc.perform(post("/test/exceptions/data-integrity"))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.title").value("Data integrity violation"))
                .andExpect(jsonPath("$.detail").value("Data integrity constraint violated."))
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    // --- i18n ---

    @Test
    void entityNotFound_withPtBrLocale_returnsPortugueseMessages() throws Exception {
        mvc.perform(post("/test/exceptions/not-found")
                        .header("Accept-Language", "pt-BR"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Recurso não encontrado"))
                .andExpect(jsonPath("$.detail").value("O recurso solicitado não foi encontrado."))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @TestConfiguration
    static class ExceptionHandlerTestConfig {

        @Bean
        ExceptionTriggerController exceptionTriggerController(
                ProductRepository productRepository, JdbcTemplate jdbcTemplate) {
            return new ExceptionTriggerController(productRepository, jdbcTemplate);
        }
    }

    @RestController
    @RequestMapping("/test/exceptions")
    static class ExceptionTriggerController {

        private final ProductRepository productRepository;
        private final JdbcTemplate jdbc;

        ExceptionTriggerController(ProductRepository productRepository, JdbcTemplate jdbc) {
            this.productRepository = productRepository;
            this.jdbc = jdbc;
        }

        // JPA findById miss → EntityNotFoundException → handleEntityNotFound
        @PostMapping("/not-found")
        void notFound() {
            productRepository.findById(-1L)
                    .orElseThrow(() -> new EntityNotFoundException("not found"));
        }

        // JPA path → DataIntegrityViolationException → handleDataIntegrityViolation (unique branch)
        @PostMapping("/unique-violation")
        @Transactional
        void uniqueViolation() {
            var p1 = new Product();
            p1.setName("dup-test");
            var p2 = new Product();
            p2.setName("dup-test");
            productRepository.saveAndFlush(p1);
            productRepository.saveAndFlush(p2);
        }

        // JDBC path → DuplicateKeyException → handleDuplicateKey
        @PostMapping("/duplicate-key")
        @Transactional
        void duplicateKey() {
            jdbc.update("INSERT INTO products (name) VALUES (?)", "dup-key-test");
            jdbc.update("INSERT INTO products (name) VALUES (?)", "dup-key-test");
        }

        // FK constraint on product_tags.product_id → DataIntegrityViolationException → handleDataIntegrityViolation (FK branch)
        @PostMapping("/fk-violation")
        @Transactional
        void fkViolation() {
            jdbc.update("INSERT INTO product_tags (product_id, tag) VALUES (?, ?)", -999L, "tag");
        }

        // JdbcTemplate queryForObject miss → EmptyResultDataAccessException → handleEmptyResult
        @PostMapping("/empty-result")
        void emptyResult() {
            jdbc.queryForObject("SELECT id FROM products WHERE id = -1", Long.class);
        }

        // NOT NULL constraint on products.name → DataIntegrityViolationException → handleDataIntegrityViolation (fallback)
        @PostMapping("/data-integrity")
        @Transactional
        void dataIntegrity() {
            jdbc.update("INSERT INTO products (price, stock) VALUES (1.0, 0)");
        }
    }
}
