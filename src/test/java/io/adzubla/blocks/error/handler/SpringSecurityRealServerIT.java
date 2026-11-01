package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import io.adzubla.blocks.error.RealServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an anonymous caller really gets from Spring Security. It answers with {@code sendError}, so
 * the servlet container dispatches to {@code /error}, which MockMvc doesn't do.
 */
class SpringSecurityRealServerIT {

    private static final String THROWING_PATH = "/test/security-filter-exception";

    @Nested
    @SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import({TestcontainersConfiguration.class, SecurityConfig.class})
    class ErrorPathDenied {

        @LocalServerPort
        int port;

        @Test
        void securedRoute_isAnEmpty401WithoutHeader() throws Exception {
            var response = RealServer.get(port, "/products/1");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("X-Trace-Id")).isEmpty();
        }

        @Test
        void filterException_isA401WithHeader() throws Exception {
            var response = RealServer.get(port, THROWING_PATH);

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().firstValue("X-Trace-Id")).isPresent();
        }
    }

    @Nested
    @SpringBootTest(classes = DemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "test.permit-error-path=true")
    @Import({TestcontainersConfiguration.class, SecurityConfig.class})
    class ErrorPathPermitted {

        @LocalServerPort
        int port;

        @Test
        void securedRoute_isA401WithBootsBody() throws Exception {
            var response = RealServer.get(port, "/products/1");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
            assertThat(response.body()).contains("\"error\":\"Unauthorized\"").doesNotContain("\"code\"");
            assertThat(response.headers().firstValue("X-Trace-Id")).isEmpty();
        }

        @Test
        void filterException_showsTheRealError() throws Exception {
            var response = RealServer.get(port, THROWING_PATH);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("\"error\":\"Internal Server Error\"").doesNotContain("\"code\"");
        }
    }

    @TestConfiguration
    @EnableWebSecurity
    static class SecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                @Value("${test.permit-error-path:false}") boolean permitErrorPath)
                throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .httpBasic(basic -> {
                    })
                    .authorizeHttpRequests(authorize -> {
                        authorize.requestMatchers(THROWING_PATH).permitAll();
                        if (permitErrorPath) {
                            authorize.requestMatchers("/error").permitAll();
                        }
                        authorize.anyRequest().authenticated();
                    })
                    .build();
        }

        // What spring-boot-starter-security registers, including its default ERROR dispatch.
        @Bean
        FilterRegistrationBean<Filter> securityFilterRegistration(
                @Qualifier("springSecurityFilterChain") Filter securityFilter) {
            var registration = new FilterRegistrationBean<>(securityFilter);
            registration.setDispatcherTypes(DispatcherType.ASYNC, DispatcherType.ERROR, DispatcherType.FORWARD,
                    DispatcherType.INCLUDE, DispatcherType.REQUEST);
            registration.setOrder(-100);
            return registration;
        }

        @Bean
        OncePerRequestFilter throwingFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected boolean shouldNotFilter(HttpServletRequest request) {
                    return !request.getRequestURI().equals(THROWING_PATH);
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
