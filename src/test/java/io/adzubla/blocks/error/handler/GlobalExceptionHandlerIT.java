package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GlobalExceptionHandlerIT.GlobalTestConfig.class})
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
                .andExpect(jsonPath("$.path").value("$.name"))
                .andExpect(jsonPath("$.expectedType").value("string"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void missingTypedRequestParam_namesTheOpenApiType() throws Exception {
        mvc.perform(get("/test/global/page"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_PARAMETER"))
                .andExpect(jsonPath("$.path").value("$.page_size"))
                .andExpect(jsonPath("$.expectedType").value("integer (int32)"));
    }

    @Test
    void queryParamOfTheWrongType_reportsPathValueAndExpectedType() throws Exception {
        mvc.perform(get("/test/global/page").param("page_size", "abc"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETER_TYPE_MISMATCH"))
                .andExpect(jsonPath("$.detail").value("Invalid value 'abc' for parameter 'page_size' — expected integer (int32)"))
                .andExpect(jsonPath("$.path").value("$.page_size"))
                .andExpect(jsonPath("$.invalidValue").value("abc"))
                .andExpect(jsonPath("$.expectedType").value("integer (int32)"));
    }

    @Test
    void queryParamOfTheWrongType_isTranslated() throws Exception {
        mvc.perform(get("/test/global/page").param("page_size", "abc").header("Accept-Language", "pt-BR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Valor inválido 'abc' para o parâmetro 'page_size' — esperado integer (int32)"));
    }

    @Test
    void pathVariableOfTheWrongType_reportsPathValueAndExpectedType() throws Exception {
        mvc.perform(get("/test/global/items/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETER_TYPE_MISMATCH"))
                .andExpect(jsonPath("$.path").value("$.id"))
                .andExpect(jsonPath("$.invalidValue").value("not-a-uuid"))
                .andExpect(jsonPath("$.expectedType").value("string (uuid)"));
    }

    @Test
    void sensitiveParamOfTheWrongType_doesNotEchoTheValue() throws Exception {
        mvc.perform(get("/test/global/reset").param("token", "hunter2"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAMETER_TYPE_MISMATCH"))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'token' — expected string (uuid)"))
                .andExpect(jsonPath("$.path").value("$.token"))
                .andExpect(jsonPath("$.invalidValue").doesNotExist())
                .andExpect(jsonPath("$.expectedType").value("string (uuid)"));
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

    @Test
    void unexpectedException_applicationMessagesOverrideLibraryMessages() throws Exception {
        // error.internal.title is overridden by the test application's messages.properties;
        // error.internal.detail is not, so it still comes from the library bundle.
        mvc.perform(post("/test/global/fail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Something went wrong"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @Test
    void responseStatusAnnotatedException_keepsDeclaredStatus() throws Exception {
        mvc.perform(post("/test/global/annotated"))
                .andDo(print())
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("ERROR_RESPONSE"))
                .andExpect(jsonPath("$.detail").value("Widget not found"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void responseStatusAnnotatedSuperclass_keepsDeclaredStatus() throws Exception {
        mvc.perform(post("/test/global/annotated-subclass"))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ERROR_RESPONSE"))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }

    @Test
    void accessDenied_isRethrownForSpringSecurity() {
        assertThatThrownBy(() -> mvc.perform(post("/test/global/access-denied")))
                .isInstanceOf(ServletException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    void authenticationException_isRethrownForSpringSecurity() {
        assertThatThrownBy(() -> mvc.perform(post("/test/global/unauthenticated")))
                .isInstanceOf(ServletException.class)
                .hasCauseInstanceOf(BadCredentialsException.class);
    }

    @Test
    void nonMultipartRequestToMultipartEndpoint_returnsBadRequest() throws Exception {
        mvc.perform(post("/test/global/upload").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("MALFORMED_MULTIPART_REQUEST"))
                .andExpect(jsonPath("$.title").value("Malformed multipart request"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void multipartError_withPtBrLocale_returnsPortugueseMessages() throws Exception {
        mvc.perform(post("/test/global/upload").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Accept-Language", "pt-BR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requisição multipart malformada"));
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

        @GetMapping("/page")
        String page(@RequestParam("page_size") int pageSize) {
            return "ok";
        }

        @GetMapping("/items/{id}")
        String item(@PathVariable UUID id) {
            return "ok";
        }

        @GetMapping("/reset")
        String reset(@RequestParam UUID token) {
            return "ok";
        }

        @PostMapping("/need-param")
        void needParam(@RequestParam String name) {
        }

        @PostMapping("/upload")
        void upload(@RequestParam MultipartFile file) {
        }

        @PostMapping("/fail")
        void fail() {
            throw new RuntimeException("simulated error");
        }

        @PostMapping("/annotated")
        void annotated() {
            throw new WidgetNotFoundException();
        }

        @PostMapping("/annotated-subclass")
        void annotatedSubclass() {
            throw new SpecificConflictException();
        }

        @PostMapping("/access-denied")
        void accessDenied() {
            throw new AccessDeniedException("denied");
        }

        @PostMapping("/unauthenticated")
        void unauthenticated() {
            throw new BadCredentialsException("bad credentials");
        }
    }

    // The reason is a message code, resolved against the application's messages.properties.
    @ResponseStatus(code = HttpStatus.NOT_FOUND, reason = "test.widget-not-found")
    static class WidgetNotFoundException extends RuntimeException {
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class ConflictException extends RuntimeException {
    }

    static class SpecificConflictException extends ConflictException {
    }
}
