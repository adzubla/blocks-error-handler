package io.adzubla.blocks.error.trace;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import io.adzubla.blocks.error.RealServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An exception escaping the filter chain becomes a 500 only after it has passed through
 * {@link TraceIdResponseHeaderFilter}, so the filter has to treat it as one.
 */
class TraceIdFilterExceptionIT {

    private static final String PATH = "/test/filter-exception";

    @Nested
    @SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import({TestcontainersConfiguration.class, Config.class})
    class ByDefault {

        @LocalServerPort
        int port;

        @Test
        void filterException_hasHeader() throws Exception {
            var response = RealServer.get(port, PATH);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }
    }

    @Nested
    @SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "blocks.error.trace.header.error-only=true")
    @Import({TestcontainersConfiguration.class, Config.class})
    class ErrorOnly {

        @LocalServerPort
        int port;

        @Test
        void filterException_hasHeader() throws Exception {
            var response = RealServer.get(port, PATH);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }
    }

    @Nested
    @SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "blocks.error.trace.header.exclude-statuses=500")
    @Import({TestcontainersConfiguration.class, Config.class})
    class Status500Excluded {

        @LocalServerPort
        int port;

        @Test
        void filterException_hasNoHeader() throws Exception {
            var response = RealServer.get(port, PATH);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("X-Trace-Id")).isEmpty();
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        OncePerRequestFilter throwingFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected boolean shouldNotFilter(HttpServletRequest request) {
                    return !request.getRequestURI().equals(PATH);
                }

                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                FilterChain chain) {
                    throw new IllegalStateException("boom");
                }
            };
        }
    }
}
