package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class SensitiveFieldsPropertyIT {

    private static final String PASSWORD_VALUE = "$.errors[?(@.path=='$.password')].invalidValue";
    private static final String PIN_VALUE = "$.errors[?(@.path=='$.pin')].invalidValue";

    private static ResultActions postShortValues(MockMvc mvc) throws Exception {
        return mvc.perform(post("/test/sensitive")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"short\",\"pin\":\"1234\"}"));
    }

    @Nested
    @SpringBootTest(classes = DemoApplication.class, properties = "blocks.error.validation.sensitive-fields=pin")
    @AutoConfigureMockMvc
    @Import({TestcontainersConfiguration.class, Config.class})
    class Replaced {

        @Autowired
        MockMvc mvc;

        @Test
        void listedFieldIsHiddenAndTheDefaultsNoLongerApply() throws Exception {
            postShortValues(mvc)
                    .andExpect(jsonPath(PIN_VALUE).isEmpty())
                    .andExpect(jsonPath(PASSWORD_VALUE, hasItem("short")));
        }
    }

    @Nested
    @SpringBootTest(classes = DemoApplication.class, properties = "blocks.error.validation.sensitive-fields=")
    @AutoConfigureMockMvc
    @Import({TestcontainersConfiguration.class, Config.class})
    class Empty {

        @Autowired
        MockMvc mvc;

        @Test
        void nothingIsHidden() throws Exception {
            postShortValues(mvc)
                    .andExpect(jsonPath(PIN_VALUE, hasItem("1234")))
                    .andExpect(jsonPath(PASSWORD_VALUE, hasItem("short")));
        }
    }

    record Credentials(@Size(min = 8) String password, @Size(min = 8) String pin) {
    }

    @RestController
    static class Controller {

        @PostMapping("/test/sensitive")
        void credentials(@RequestBody @Valid Credentials credentials) {
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        Controller sensitiveController() {
            return new Controller();
        }
    }
}
