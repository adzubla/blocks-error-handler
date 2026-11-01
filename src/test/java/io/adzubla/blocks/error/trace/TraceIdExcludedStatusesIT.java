package io.adzubla.blocks.error.trace;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.product.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** {@code blocks.error.trace.header.exclude-statuses} replaces the 401/403 default. */
@SpringBootTest(classes = DemoApplication.class, properties = "blocks.error.trace.header.exclude-statuses=404")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TraceIdExcludedStatusesIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductRepository repository;

    @Test
    void excludedStatus_hasNoHeaderButKeepsTraceIdInBody() throws Exception {
        mvc.perform(get("/nonexistent-route-12345"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void otherErrorStatus_hasHeader() throws Exception {
        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void successResponse_hasHeader() throws Exception {
        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Trace header\",\"price\":1.00,\"stock\":1,\"category\":\"BOOKS\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }
}
