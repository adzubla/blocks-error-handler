package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = DemoApplication.class,
        properties = "blocks.error.problem-type.base-uri=https://api.example.com/problems/")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProblemTypeBaseUriPropertyIT {

    @Autowired
    MockMvc mvc;

    @Test
    void typeUsesTheConfiguredBaseUri() throws Exception {
        mvc.perform(post("/products").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"))
                .andExpect(jsonPath("$.type").value("https://api.example.com/problems/malformed-json"));
    }
}
