package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import io.adzubla.blocks.error.RealServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which errors get the library's body on a real server, and which Spring Boot's error controller.
 */
@SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-file-size=1KB")
@Import({TestcontainersConfiguration.class, RealServerErrorResponsesIT.Config.class})
class RealServerErrorResponsesIT {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String MULTIPART = "multipart/form-data; boundary=B";

    @LocalServerPort
    int port;

    @Test
    void unknownRoute_isAProblem() throws Exception {
        var response = RealServer.get(port, "/test/real-server/nope");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(PROBLEM_JSON);
        assertThat(response.body()).contains("\"code\":\"ROUTE_NOT_FOUND\"");
    }

    @Test
    void uploadOverTheLimit_isAProblem() throws Exception {
        var response = RealServer.send(port, "POST", "/test/real-server/upload", MULTIPART, part("x".repeat(5000)));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type")).hasValue(PROBLEM_JSON);
        assertThat(response.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
    }

    @Test
    void truncatedMultipart_isMalformed() throws Exception {
        var response = RealServer.send(port, "POST", "/test/real-server/upload", MULTIPART,
                "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\nabc");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"MALFORMED_MULTIPART_REQUEST\"");
    }

    @Test
    void bodyWithoutAnyBoundary_hasNoPartsSoIsMissingThePart() throws Exception {
        var response = RealServer.send(port, "POST", "/test/real-server/upload", MULTIPART, "garbage");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"MISSING_REQUEST_PART\"");
    }

    @Test
    void sendError_getsBootsBody() throws Exception {
        var response = RealServer.get(port, "/test/real-server/send-error");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).contains("\"error\":\"Bad Request\"").doesNotContain("\"code\"");
    }

    @Test
    void filterException_getsBootsBody() throws Exception {
        var response = RealServer.get(port, "/test/real-server/filter-exception");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).contains("\"error\":\"Internal Server Error\"").doesNotContain("\"code\"");
    }

    private static String part(String content) {
        return "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a\"\r\n\r\n" + content + "\r\n--B--\r\n";
    }

    @RestController
    static class Controller {

        @GetMapping("/test/real-server/send-error")
        void sendError(HttpServletResponse response) throws IOException {
            response.sendError(400);
        }

        @PostMapping("/test/real-server/upload")
        void upload(@RequestPart("file") MultipartFile file) {
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        Controller realServerController() {
            return new Controller();
        }

        @Bean
        OncePerRequestFilter throwingFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected boolean shouldNotFilter(HttpServletRequest request) {
                    return !request.getRequestURI().endsWith("/filter-exception");
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
