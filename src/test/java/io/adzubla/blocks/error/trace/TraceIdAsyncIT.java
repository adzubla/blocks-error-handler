package io.adzubla.blocks.error.trace;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import io.adzubla.blocks.error.RealServer;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An async request (a controller returning a {@code Callable}) has no final status when the filter
 * chain returns, so the header must be decided when the request completes, not before.
 */
class TraceIdAsyncIT {

    @Nested
    @SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "blocks.error.trace.header.exclude-statuses=404")
    @Import({TestcontainersConfiguration.class, Config.class})
    class WithExcludedStatus {

        @LocalServerPort
        int port;

        @Test
        void asyncExcludedStatus_hasNoHeader() throws Exception {
            var response = RealServer.get(port, "/test/async/not-found");

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("X-Trace-Id")).isEmpty();
        }

        @Test
        void asyncSuccess_hasHeader() throws Exception {
            var response = RealServer.get(port, "/test/async/ok");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }

        @Test
        void asyncOtherError_hasHeader() throws Exception {
            var response = RealServer.get(port, "/test/async/failure");

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
        void asyncSuccess_hasNoHeader() throws Exception {
            var response = RealServer.get(port, "/test/async/ok");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Trace-Id")).isEmpty();
        }

        @Test
        void asyncError_hasHeader() throws Exception {
            var response = RealServer.get(port, "/test/async/not-found");

            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        AsyncController asyncController() {
            return new AsyncController();
        }
    }

    @RestController
    @RequestMapping("/test/async")
    static class AsyncController {

        @GetMapping("/ok")
        Callable<String> ok() {
            return () -> "ok";
        }

        @GetMapping("/not-found")
        Callable<String> notFound() {
            return () -> {
                throw new EntityNotFoundException("missing");
            };
        }

        @GetMapping("/failure")
        Callable<String> failure() {
            return () -> {
                throw new IllegalStateException("boom");
            };
        }
    }
}
