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
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * A filter that answers the request itself, standing in for Spring Security (401/403), CORS or
 * rate-limit filters, must not escape the trace ID header.
 */
@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TraceIdHeaderOrderIT.EarlyFilterConfig.class})
class TraceIdHeaderOrderIT {

    @Autowired
    MockMvc mvc;

    @Test
    void responseProducedByAnEarlyFilter_hasTraceIdHeader() throws Exception {
        mvc.perform(get("/test/early-filter/denied"))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void headerFilterRunsRightAfterTheObservationFilter() {
        org.assertj.core.api.Assertions.assertThat(TraceIdResponseHeaderFilter.ORDER)
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @TestConfiguration
    static class EarlyFilterConfig {
        @Bean
        EarlyDenyingFilter earlyDenyingFilter() {
            return new EarlyDenyingFilter();
        }
    }

    // Ahead of where the header filter used to sit (LOWEST_PRECEDENCE), like Spring Security's chain.
    @Order(0)
    static class EarlyDenyingFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            if (request.getRequestURI().startsWith("/test/early-filter/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
