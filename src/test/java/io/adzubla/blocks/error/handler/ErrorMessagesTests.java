package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessagesTests {

    private Locale originalDefault;

    @BeforeEach
    void simulatePortugueseHost() {
        originalDefault = Locale.getDefault();
        Locale.setDefault(new Locale("pt", "BR"));
    }

    @AfterEach
    void restore() {
        Locale.setDefault(originalDefault);
        LocaleContextHolder.resetLocaleContext();
    }

    private final ErrorMessages messages = new ErrorMessages(new StaticMessageSource());

    @Test
    void unmatchedRequestLocale_fallsBackToEnglishNotJvmLocale() {
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(messages.get("error.internal.title")).isEqualTo("Internal server error");
    }

    @Test
    void englishRequestLocale_onNonEnglishHost_getsEnglish() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(messages.get("error.internal.title")).isEqualTo("Internal server error");
    }

    @Test
    void matchedRequestLocale_getsTranslation() {
        LocaleContextHolder.setLocale(new Locale("pt", "BR"));
        assertThat(messages.get("error.internal.title")).isEqualTo("Erro interno do servidor");
    }

    @Test
    void applicationMessageOverridesLibraryMessage() {
        var appMessages = new StaticMessageSource();
        appMessages.addMessage("error.internal.title", Locale.ENGLISH, "Oops");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(new ErrorMessages(appMessages).get("error.internal.title")).isEqualTo("Oops");
    }
}
