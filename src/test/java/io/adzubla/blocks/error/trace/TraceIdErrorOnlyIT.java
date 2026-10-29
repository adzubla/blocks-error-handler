package io.adzubla.blocks.error.trace;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DemoApplication.class, properties = "blocks.error.trace.header.error-only=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TraceIdErrorOnlyIT.ErrorOnlyTestConfig.class})
class TraceIdErrorOnlyIT {

    @Autowired
    MockMvc mvc;

    @Test
    void errorResponseWithProblemDetailBody_hasTraceIdHeader() throws Exception {
        mvc.perform(post("/test/error-only/fail"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void clientErrorResponse_hasTraceIdHeader() throws Exception {
        mvc.perform(get("/nonexistent-route-12345"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void successResponse_hasNoTraceIdHeader() throws Exception {
        mvc.perform(get("/test/error-only/ok"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Trace-Id"));
    }

    @TestConfiguration
    static class ErrorOnlyTestConfig {
        @Bean
        ErrorOnlyTestController errorOnlyTestController() {
            return new ErrorOnlyTestController();
        }
    }

    @RestController
    @RequestMapping("/test/error-only")
    static class ErrorOnlyTestController {

        @GetMapping("/ok")
        String ok() {
            return "ok";
        }

        @PostMapping("/fail")
        void fail() {
            throw new RuntimeException("simulated error");
        }
    }
}
