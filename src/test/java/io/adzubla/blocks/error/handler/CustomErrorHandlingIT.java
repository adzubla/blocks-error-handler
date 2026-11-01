package io.adzubla.blocks.error.handler;

import com.example.demo.DemoApplication;
import com.example.demo.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Runs the base-class handler from the README's "Custom error handling" section. Keep them in sync.
 */
@SpringBootTest(classes = DemoApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, CustomErrorHandlingIT.Config.class})
class CustomErrorHandlingIT {

    @Autowired
    MockMvc mvc;

    @Test
    void definedKeys_areUsedWithArguments() throws Exception {
        mvc.perform(get("/test/custom/closed").header("Accept-Language", "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_CLOSED"))
                .andExpect(jsonPath("$.title").value("Order closed"))
                .andExpect(jsonPath("$.detail").value("Order 1234567 is closed."))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void definedKeys_areTranslated() throws Exception {
        mvc.perform(get("/test/custom/closed").header("Accept-Language", "pt-BR"))
                .andExpect(jsonPath("$.title").value("Pedido encerrado"))
                .andExpect(jsonPath("$.detail").value("O pedido 1234567 está encerrado."));
    }

    @Test
    void undefinedKeys_fallBackToTheMessageUnchanged() throws Exception {
        mvc.perform(get("/test/custom/undefined"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_LOCKED"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Order 7 can't be changed."));
    }

    abstract static class BusinessException extends RuntimeException {

        private final String code;
        private final HttpStatus status;
        private final Object[] args;

        BusinessException(String code, HttpStatus status, String message, Object... args) {
            super(message);
            this.code = code;
            this.status = status;
            this.args = args;
        }

        String getCode() {
            return code;
        }

        HttpStatus getStatus() {
            return status;
        }

        Object[] getArgs() {
            return args;
        }
    }

    static class OrderClosedException extends BusinessException {
        OrderClosedException(long orderId) {
            super("ORDER_CLOSED", HttpStatus.CONFLICT, "Order " + orderId + " is closed.", String.valueOf(orderId));
        }
    }

    static class OrderLockedException extends BusinessException {
        OrderLockedException(long orderId) {
            super("ORDER_LOCKED", HttpStatus.CONFLICT, "Order " + orderId + " can't be changed.", String.valueOf(orderId));
        }
    }

    @RestControllerAdvice
    static class BusinessExceptionHandler {

        private final MessageSource messageSource;

        BusinessExceptionHandler(MessageSource messageSource) {
            this.messageSource = messageSource;
        }

        @ExceptionHandler(BusinessException.class)
        ResponseEntity<ProblemDetail> handle(BusinessException ex) {
            var locale = LocaleContextHolder.getLocale();
            var key = "error." + ex.getCode().toLowerCase(Locale.ROOT).replace('_', '-');
            var detail = messageSource.getMessage(key + ".detail", ex.getArgs(), null, locale);

            var problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), detail != null ? detail : ex.getMessage());
            problem.setTitle(messageSource.getMessage(key + ".title", null, ex.getStatus().getReasonPhrase(), locale));
            problem.setProperty("code", ex.getCode());
            return ResponseEntity.status(ex.getStatus()).body(problem);
        }
    }

    @RestController
    static class Controller {

        @GetMapping("/test/custom/closed")
        void closed() {
            throw new OrderClosedException(1234567);
        }

        @GetMapping("/test/custom/undefined")
        void undefined() {
            throw new OrderLockedException(7);
        }
    }

    @TestConfiguration
    static class Config {

        @Bean
        BusinessExceptionHandler businessExceptionHandler(MessageSource messageSource) {
            return new BusinessExceptionHandler(messageSource);
        }

        @Bean
        Controller customController() {
            return new Controller();
        }
    }
}
