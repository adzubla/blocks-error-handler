package io.adzubla.blocks.error.trace;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import io.adzubla.blocks.error.RealServer;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A redirect commits the response without going through {@code setStatus}, and a reset clears the
 * headers already written: the header has to survive both.
 */
class TraceIdRedirectAndResetIT {

    @Nested
    @SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import({TestcontainersConfiguration.class, Config.class})
    class ByDefault {

        @LocalServerPort
        int port;

        @Test
        void redirect_hasHeader() throws Exception {
            var response = RealServer.get(port, "/test/response/redirect");

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }

        @Test
        void redirectWithStatus_hasHeader() throws Exception {
            var response = RealServer.get(port, "/test/response/redirect-permanent");

            assertThat(response.statusCode()).isEqualTo(301);
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }

        @Test
        void responseWrittenAfterAReset_hasHeader() throws Exception {
            var response = RealServer.get(port, "/test/response/reset");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("after reset");
            assertThat(response.headers().firstValue("X-Discarded")).isEmpty();
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
        void redirect_hasNoHeader() throws Exception {
            var response = RealServer.get(port, "/test/response/redirect");

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("X-Trace-Id")).isEmpty();
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        ResponseController responseController() {
            return new ResponseController();
        }
    }

    @RestController
    @RequestMapping("/test/response")
    static class ResponseController {

        @GetMapping("/redirect")
        void redirect(HttpServletResponse response) throws IOException {
            response.sendRedirect("/elsewhere");
        }

        @GetMapping("/redirect-permanent")
        void redirectPermanent(HttpServletResponse response) throws IOException {
            response.sendRedirect("/elsewhere", HttpServletResponse.SC_MOVED_PERMANENTLY);
        }

        @GetMapping("/reset")
        void reset(HttpServletResponse response) throws IOException {
            response.setStatus(HttpServletResponse.SC_ACCEPTED);
            response.setHeader("X-Discarded", "yes");
            response.reset();
            response.getWriter().write("after reset");
        }
    }
}
