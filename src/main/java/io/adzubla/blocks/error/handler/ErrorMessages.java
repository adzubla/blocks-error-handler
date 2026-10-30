package io.adzubla.blocks.error.handler;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;

/**
 * Resolves the client-facing error messages used by the handlers in this package.
 *
 * <p>The library ships its own bundle under {@value #BASENAME} — a namespaced path, so it never
 * clashes with (or is shadowed by) the consuming application's own {@code messages.properties}.
 * Each key is first looked up in the application's {@link MessageSource}, which lets an application
 * override or translate any message; when the application doesn't define the key, the library's
 * bundle is used. This works regardless of the application's {@code spring.messages.basename}.
 */
final class ErrorMessages {

    static final String BASENAME = "io/adzubla/blocks/error/messages";

    private static final ResourceBundleMessageSource DEFAULTS = new ResourceBundleMessageSource();

    static {
        DEFAULTS.setBasename(BASENAME);
        DEFAULTS.setDefaultEncoding(StandardCharsets.UTF_8.name());
    }

    private final MessageSource applicationMessages;

    ErrorMessages(MessageSource applicationMessages) {
        this.applicationMessages = applicationMessages;
    }

    String get(String key, Object... args) {
        var locale = LocaleContextHolder.getLocale();
        args = asPlainText(args);
        var message = applicationMessages.getMessage(key, args, null, locale);
        // Guard against an application MessageSource configured with useCodeAsDefaultMessage=true.
        if (message != null && !message.equals(key)) {
            return message;
        }
        return DEFAULTS.getMessage(key, args, locale);
    }

    // MessageFormat renders Number arguments with locale grouping (1234 -> "1,234" / "1.234"), which
    // garbles line numbers and echoed input values; pass them through as plain text instead.
    private static Object[] asPlainText(Object[] args) {
        if (args == null) {
            return null;
        }
        var result = args.clone();
        for (int i = 0; i < result.length; i++) {
            if (result[i] instanceof Number number) {
                result[i] = number.toString();
            }
        }
        return result;
    }
}
