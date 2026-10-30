package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of {@link GlobalExceptionHandler} for exceptions normally claimed by a more specific
 * handler — what an application gets when it disables that handler.
 */
class GlobalExceptionHandlerTests {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());
    private final ServletWebRequest request = new ServletWebRequest(
            new MockHttpServletRequest("POST", "/x"), new MockHttpServletResponse());

    @Test
    void unreadableBody_withJsonHandlerDisabled_getsMalformedRequestBodyCode() throws Exception {
        var response = handle(new HttpMessageNotReadableException("bad", new MockHttpInputMessage(new byte[0])));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(response).getProperties()).containsEntry("code", "MALFORMED_REQUEST_BODY");
    }

    @Test
    void invalidRequestBody_withValidationHandlerDisabled_gets422() throws Exception {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "name", "must not be blank"));
        var ex = new MethodArgumentNotValidException(parameter(0), bindingResult);

        var response = handle(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(body(response).getStatus()).isEqualTo(422);
        assertThat(body(response).getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void invalidParameter_withValidationHandlerDisabled_gets422() throws Exception {
        var response = handle(methodValidation(parameter(0)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(body(response).getStatus()).isEqualTo(422);
        assertThat(body(response).getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    @Test
    void invalidReturnValue_withValidationHandlerDisabled_isServerError() throws Exception {
        var response = handle(methodValidation(parameter(-1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(response).getProperties()).containsEntry("code", "INTERNAL_SERVER_ERROR");
    }

    private ResponseEntity<Object> handle(Exception ex) throws Exception {
        return handler.handleException(ex, request);
    }

    private static ProblemDetail body(ResponseEntity<Object> response) {
        assertThat(response).isNotNull();
        return (ProblemDetail) response.getBody();
    }

    private static HandlerMethodValidationException methodValidation(MethodParameter parameter) {
        var error = new FieldError("value", "value", "must not be null");
        var result = new ParameterValidationResult(parameter, null, List.of(error), null, null, null,
                (resolvable, type) -> null);
        return new HandlerMethodValidationException(
                MethodValidationResult.create(new Endpoint(), parameter.getMethod(), List.of(result)));
    }

    private static MethodParameter parameter(int index) throws NoSuchMethodException {
        return new MethodParameter(Endpoint.class.getMethod("handle", String.class), index);
    }

    static class Endpoint {
        public String handle(String value) {
            return value;
        }
    }
}
