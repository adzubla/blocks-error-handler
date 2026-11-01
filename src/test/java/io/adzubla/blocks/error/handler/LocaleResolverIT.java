package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * DispatcherServlet looks up the {@link LocaleResolver} by name, so the name matters.
 */
class LocaleResolverIT {

    private static final String PORTUGUESE_TITLE = "Recurso não encontrado";
    private static final String ENGLISH_TITLE = "Resource not found";

    @Nested
    @SpringBootTest(classes = DemoApplication.class)
    @AutoConfigureMockMvc
    @Import({TestcontainersConfiguration.class, ControllerConfig.class, NamedResolverConfig.class})
    class BeanNamedLocaleResolver {

        @Autowired
        MockMvc mvc;

        @Test
        void isUsed() throws Exception {
            mvc.perform(get("/test/locale").header("Accept-Language", "en"))
                    .andExpect(jsonPath("$.title").value(PORTUGUESE_TITLE));
        }
    }

    @Nested
    @SpringBootTest(classes = DemoApplication.class)
    @AutoConfigureMockMvc
    @Import({TestcontainersConfiguration.class, ControllerConfig.class, OtherNameResolverConfig.class})
    class BeanWithAnotherName {

        @Autowired
        MockMvc mvc;

        @Test
        void isIgnored() throws Exception {
            mvc.perform(get("/test/locale").header("Accept-Language", "en"))
                    .andExpect(jsonPath("$.title").value(ENGLISH_TITLE));
        }
    }

    @RestController
    static class Controller {

        @GetMapping("/test/locale")
        void notFound() {
            throw new EntityNotFoundException("missing");
        }
    }

    @TestConfiguration
    static class ControllerConfig {

        @Bean
        Controller localeController() {
            return new Controller();
        }
    }

    @TestConfiguration
    static class NamedResolverConfig {

        @Bean
        LocaleResolver localeResolver() {
            return new FixedLocaleResolver(new Locale("pt", "BR"));
        }
    }

    @TestConfiguration
    static class OtherNameResolverConfig {

        @Bean
        LocaleResolver portugueseResolver() {
            return new FixedLocaleResolver(new Locale("pt", "BR"));
        }
    }
}
