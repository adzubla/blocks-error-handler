package com.example.demo.product;

import com.example.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProductControllerIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductRepository repository;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void createMinimalProduct_returnsCreatedWithAssignedId() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Laptop","price":999.99,"stock":10,"category":"ELECTRONICS"}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Laptop"))
                .andExpect(jsonPath("$.price").value(999.99))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.category").value("ELECTRONICS"));
    }

    @Test
    void createProductWithAllFields_returnsCompleteResponse() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Clean Code",
                                  "price": 49.90,
                                  "stock": 100,
                                  "category": "BOOKS",
                                  "tags": ["programming", "software"],
                                  "attributes": {"author": "Robert C. Martin", "pages": "431"},
                                  "address": {"street": "Rua das Flores, 123", "city": "São Paulo"}
                                }
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andExpect(jsonPath("$.tags", hasItems("programming", "software")))
                .andExpect(jsonPath("$.attributes.author").value("Robert C. Martin"))
                .andExpect(jsonPath("$.address.street").value("Rua das Flores, 123"))
                .andExpect(jsonPath("$.address.city").value("São Paulo"));
    }

    @Test
    void createThenGetProduct_returnsPersistedData() throws Exception {
        String createResponse = mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"T-Shirt","price":29.90,"stock":50,"category":"CLOTHING"}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn().getResponse().getContentAsString();

        String id = com.jayway.jsonpath.JsonPath.read(createResponse, "$.id").toString();

        mvc.perform(get("/products/{id}", id))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.id").value(Long.parseLong(id)))
                .andExpect(jsonPath("$.name").value("T-Shirt"))
                .andExpect(jsonPath("$.price").value(29.90))
                .andExpect(jsonPath("$.category").value("CLOTHING"));
    }

    @Test
    void createProductWithoutOptionalFields_returnsNullForMissing() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Headphones","price":199.00,"stock":5}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.category").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist());
    }

    @Test
    void createProductWithEmptyCollections_returnsEmptyCollections() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Mouse","price":59.00,"stock":20,"tags":[],"attributes":{}}
                                """))
                .andDo(print())
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.tags", hasSize(0)));
    }
}
