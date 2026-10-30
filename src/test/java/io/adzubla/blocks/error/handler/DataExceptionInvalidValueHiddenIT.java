package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DemoApplication.class, properties = "blocks.error.data.expose-invalid-value=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, DataExceptionHandlerIT.ExceptionHandlerTestConfig.class})
class DataExceptionInvalidValueHiddenIT {

    @Autowired
    MockMvc mvc;

    @Test
    void duplicateKey_omitsInvalidValue() throws Exception {
        mvc.perform(post("/test/exceptions/duplicate-key"))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_VALUE"))
                .andExpect(jsonPath("$.invalidValue").doesNotExist());
    }

    @Test
    void foreignKeyViolation_omitsInvalidValue() throws Exception {
        mvc.perform(post("/test/exceptions/fk-violation"))
                .andDo(print())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENTIAL_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.invalidValue").doesNotExist());
    }
}
