package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The library behind a real Spring Security filter chain (registered at Spring Boot's usual
 * order, -100): Security's own 401/403 responses, method-security exceptions thrown from
 * controllers, and the library's responses for authenticated callers.
 */
@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SpringSecurityIT.SecurityTestConfig.class})
class SpringSecurityIT {

    @Autowired
    MockMvc mvc;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String user) {
        var credentials = Base64.getEncoder().encodeToString((user + ":pass").getBytes(StandardCharsets.UTF_8));
        return request.header("Authorization", "Basic " + credentials);
    }

    // --- responses produced by Spring Security's filter chain itself ---

    @Test
    void unauthenticatedRequest_getsSecurity401WithoutTraceIdOrProblemBody() throws Exception {
        mvc.perform(get("/secured/ping"))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Trace-Id"))
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(content().string(""));
    }

    @Test
    void authenticatedButNotAuthorized_getsSecurity403WithoutTraceIdOrProblemBody() throws Exception {
        mvc.perform(as(get("/admin/ping"), "user"))
                .andDo(print())
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("X-Trace-Id"))
                .andExpect(content().string(""));
    }

    @Test
    void unknownRouteBehindAuthentication_isA401ForAnonymousCallers() throws Exception {
        mvc.perform(get("/nonexistent-route-12345"))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Trace-Id"))
                .andExpect(content().string(""));
    }

    // --- method security: exceptions thrown from inside the controller ---

    @Test
    void methodSecurityDenial_isRethrownAndAnsweredBySecurity_not500() throws Exception {
        mvc.perform(as(get("/open/admin-only"), "user"))
                .andDo(print())
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("X-Trace-Id"))
                .andExpect(content().string(""));
    }

    @Test
    void methodSecurityDenialForAnonymous_isA401FromSecurity() throws Exception {
        mvc.perform(get("/open/authenticated-only"))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Trace-Id"))
                .andExpect(content().string(""));
    }

    @Test
    void authenticationExceptionThrownByController_isRethrownAndAnsweredBySecurity() throws Exception {
        mvc.perform(get("/open/bad-credentials"))
                .andDo(print())
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("X-Trace-Id"))
                .andExpect(content().string(""));
    }

    @Test
    void securityExceptionWrappedInAnotherException_becomesA500() throws Exception {
        // Known limitation: only a top-level Security exception is rethrown (see README).
        mvc.perform(get("/open/wrapped-denial"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    // --- the library's own responses keep working for callers Security lets through ---

    @Test
    void authorizedCaller_getsNormalResponse() throws Exception {
        mvc.perform(as(get("/open/admin-only"), "admin"))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedCaller_stillGetsProblemDetailFromTheLibrary() throws Exception {
        mvc.perform(as(post("/open/json"), "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void unknownRoutePermittedBySecurity_isTheLibrarys404() throws Exception {
        mvc.perform(get("/open/nonexistent-12345"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class SecurityTestConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .httpBasic(Customizer.withDefaults())
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/open/**").permitAll()
                            .requestMatchers("/admin/**").hasRole("ADMIN")
                            .anyRequest().authenticated())
                    .build();
        }

        // Spring Boot's security auto-configuration registers the chain at order -100; do the same.
        @Bean
        FilterRegistrationBean<FilterChainProxy> securityFilterRegistration(FilterChainProxy chain) {
            var registration = new FilterRegistrationBean<>(chain);
            registration.setOrder(-100);
            return registration;
        }

        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("user").password("{noop}pass").roles("USER").build(),
                    User.withUsername("admin").password("{noop}pass").roles("ADMIN").build());
        }

        @Bean
        SecuredController securedController() {
            return new SecuredController();
        }
    }

    record Payload(String name) {
    }

    @RestController
    static class SecuredController {

        @GetMapping("/secured/ping")
        String securedPing() {
            return "pong";
        }

        @GetMapping("/admin/ping")
        String adminPing() {
            return "pong";
        }

        @GetMapping("/open/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        String adminOnly() {
            return "ok";
        }

        @GetMapping("/open/authenticated-only")
        @PreAuthorize("isAuthenticated()")
        String authenticatedOnly() {
            return "ok";
        }

        @GetMapping("/open/bad-credentials")
        String badCredentials() {
            throw new BadCredentialsException("bad credentials");
        }

        @GetMapping("/open/wrapped-denial")
        String wrappedDenial() {
            throw new IllegalStateException("wrapped",
                    new org.springframework.security.access.AccessDeniedException("denied"));
        }

        @PostMapping("/open/json")
        void json(@RequestBody Payload payload) {
        }
    }
}
