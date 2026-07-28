package com.example.demo.error;

import com.example.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GlobalExceptionHandlerIT {

    @Autowired
    MockMvc mvc;

    @Test
    void missingRequestParam_returnsBadRequest() throws Exception {
        mvc.perform(post("/test/global/need-param"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void routeNotFound_returnsNotFound() throws Exception {
        mvc.perform(get("/nonexistent-route-12345"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void methodNotAllowed_returnsMethodNotAllowed() throws Exception {
        mvc.perform(get("/products"))
                .andDo(print())
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void unsupportedMediaType_returnsUnsupportedMediaType() throws Exception {
        mvc.perform(post("/products")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("some text"))
                .andDo(print())
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void unexpectedException_returnsInternalServerError() throws Exception {
        mvc.perform(post("/test/global/fail"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @TestConfiguration
    static class GlobalTestConfig {
        @Bean
        GlobalTestController globalTestController() {
            return new GlobalTestController();
        }
    }

    @RestController
    @RequestMapping("/test/global")
    static class GlobalTestController {

        @PostMapping("/need-param")
        void needParam(@RequestParam String name) {
        }

        @PostMapping("/fail")
        void fail() {
            throw new RuntimeException("simulated error");
        }
    }
}
