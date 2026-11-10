package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A form object whose field can't be converted from the request reports a binding failure, whose
 * default message is the conversion exception's text and must not reach the client.
 */
@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, BindingFailureIT.Config.class})
class BindingFailureIT {

    @Autowired
    MockMvc mvc;

    @Test
    void bindingFailure_hasGenericMessageWithoutJavaTypes() throws Exception {
        mvc.perform(get("/test/binding/form").param("age", "abc"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations", hasSize(1)))
                .andExpect(jsonPath("$.violations[0].path").value("$.age"))
                .andExpect(jsonPath("$.violations[0].invalidValue").value("abc"))
                .andExpect(jsonPath("$.violations[0].expectedType").value("integer (int32)"))
                .andExpect(jsonPath("$.violations[0].message").value("Invalid value — expected integer (int32)"));
    }

    @Test
    void bindingFailure_onADate_namesTheOpenApiFormat() throws Exception {
        mvc.perform(get("/test/binding/form").param("birthday", "yesterday"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$.birthday"))
                .andExpect(jsonPath("$.violations[0].expectedType").value("string (date)"))
                .andExpect(jsonPath("$.violations[0].message").value("Invalid value — expected string (date)"));
    }

    @Test
    void bindingFailure_messageIsTranslated() throws Exception {
        mvc.perform(get("/test/binding/form").param("age", "abc").header("Accept-Language", "pt-BR"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].message").value("Valor inválido — esperado integer (int32)"));
    }

    @Test
    void constraintViolation_keepsItsOwnMessage() throws Exception {
        mvc.perform(get("/test/binding/form").param("age", "0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.violations[0].path").value("$.age"))
                .andExpect(jsonPath("$.violations[0].expectedType").doesNotExist())
                .andExpect(jsonPath("$.violations[0].message").value("must be greater than or equal to 1"));
    }

    public static class Form {

        @Min(1)
        private int age = 1;

        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private java.time.LocalDate birthday;

        public java.time.LocalDate getBirthday() {
            return birthday;
        }

        public void setBirthday(java.time.LocalDate birthday) {
            this.birthday = birthday;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        FormController formController() {
            return new FormController();
        }
    }

    @RestController
    static class FormController {

        @GetMapping("/test/binding/form")
        String form(@Valid @ModelAttribute Form form) {
            return "ok";
        }
    }
}
