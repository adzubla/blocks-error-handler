package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * What the exceptions of a disabled handler fall through to in {@link GlobalExceptionHandler}.
 */
@SpringBootTest(classes = DemoApplication.class, properties = {
        "blocks.error.validation.enabled=false",
        "blocks.error.spring-data.enabled=false",
        "spring.validation.method.adapt-constraint-violations=true"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DisabledHandlersIT.Config.class})
class DisabledHandlersIT {

    @Autowired
    MockMvc mvc;

    @Test
    void invalidBody_returns422WithoutViolations() throws Exception {
        mvc.perform(post("/test/disabled/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andDo(print())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void adaptedMethodValidation_returns500MethodValidationError() throws Exception {
        mvc.perform(post("/test/disabled/service"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("METHOD_VALIDATION_ERROR"));
    }

    @Test
    void unknownSortProperty_returns500() throws Exception {
        mvc.perform(post("/test/disabled/sort"))
                .andDo(print())
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    record Named(@NotBlank String name) {
    }

    @Validated
    static class Service {

        void requireNonBlank(@NotBlank String value) {
        }
    }

    @RestController
    @RequestMapping("/test/disabled")
    static class Controller {

        private final Service service;

        Controller(Service service) {
            this.service = service;
        }

        @PostMapping("/body")
        void body(@RequestBody @Valid Named named) {
        }

        @PostMapping("/service")
        void service() {
            service.requireNonBlank("");
        }

        @PostMapping("/sort")
        void sort() {
            throw new PropertyReferenceException("nope", TypeInformation.of(Named.class), List.of());
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        Service service() {
            return new Service();
        }

        @Bean
        Controller controller(Service service) {
            return new Controller(service);
        }
    }
}
