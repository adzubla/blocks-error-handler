package io.adzubla.blocks.error.trace;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** An empty {@code exclude-statuses} restores the header on 401/403 responses too. */
@SpringBootTest(classes = DemoApplication.class, properties = "blocks.error.trace.header.exclude-statuses=")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TraceIdNoExclusionIT.DenyingFilterConfig.class})
class TraceIdNoExclusionIT {

    @Autowired
    MockMvc mvc;

    @Test
    void unauthorizedResponse_hasHeaderWhenNothingIsExcluded() throws Exception {
        mvc.perform(get("/test/denied/401"))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void forbiddenResponse_hasHeaderWhenNothingIsExcluded() throws Exception {
        mvc.perform(get("/test/denied/403"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @TestConfiguration
    static class DenyingFilterConfig {
        @Bean
        DenyingFilter denyingFilter() {
            return new DenyingFilter();
        }
    }

    @Order(0)
    static class DenyingFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (request.getRequestURI().equals("/test/denied/401")) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            } else if (request.getRequestURI().equals("/test/denied/403")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            } else {
                chain.doFilter(request, response);
            }
        }
    }
}
