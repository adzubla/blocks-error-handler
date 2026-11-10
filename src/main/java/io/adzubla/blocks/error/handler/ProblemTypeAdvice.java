package io.adzubla.blocks.error.handler;

import io.adzubla.blocks.error.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link ResponseBodyAdvice} that gives every error response carrying one of the library's
 * {@link ErrorCode}s a {@code type} URI identifying that code, as RFC 9457 intends: the configured
 * base URI followed by the code in lower case, with {@code -} instead of {@code _}.
 *
 * <p>Without a {@code type}, RFC 9457 reads it as {@code about:blank}, for which the {@code title}
 * should be the HTTP status phrase; the library's own titles, such as "Validation failed", need a
 * {@code type} of their own.
 *
 * <p>A {@code type} already set, for example on an {@code ErrorResponseException}, is kept. So is
 * {@code about:blank} for {@link ErrorCode#ERROR_RESPONSE}: that code only says that the application
 * declared the status, and its {@code title} is the status phrase.
 */
@RestControllerAdvice
public class ProblemTypeAdvice implements ResponseBodyAdvice<Object> {

    private static final URI BLANK = URI.create("about:blank");

    private final Map<String, URI> types;

    public ProblemTypeAdvice(ProblemTypeProperties properties) {
        var baseUri = properties.getBaseUri();
        this.types = !StringUtils.hasText(baseUri) ? Map.of() : Arrays.stream(ErrorCode.values())
                .filter(code -> code != ErrorCode.ERROR_RESPONSE)
                .collect(Collectors.toUnmodifiableMap(ErrorCode::name,
                        code -> URI.create(baseUri + code.name().toLowerCase(Locale.ROOT).replace('_', '-'))));
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.hasMethodAnnotation(ExceptionHandler.class);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail pd && (pd.getType() == null || BLANK.equals(pd.getType()))
                && pd.getProperties() != null && pd.getProperties().get("code") instanceof String code) {
            var type = types.get(code);
            if (type != null) {
                pd.setType(type);
            }
        }
        return body;
    }
}
