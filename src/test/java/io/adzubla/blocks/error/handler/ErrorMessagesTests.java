package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
    void portugueseWithoutBrazil_getsEnglish() {
        LocaleContextHolder.setLocale(new Locale("pt"));
        assertThat(messages.get("error.internal.title")).isEqualTo("Internal server error");
        LocaleContextHolder.setLocale(new Locale("pt", "PT"));
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

    // The next tests run Spring Boot's real MessageSource (a JVM on pt_BR, see simulatePortugueseHost).

    private final ApplicationContextRunner bootMessages = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessageSourceAutoConfiguration.class));

    @Test
    void applicationLocaleFile_isIgnoredWithoutBaseFile() {
        bootMessages.withPropertyValues("spring.messages.basename=i18n/locale-only").run(context -> {
            LocaleContextHolder.setLocale(new Locale("es"));
            assertThat(new ErrorMessages(context).get("error.internal.title")).isEqualTo("Internal server error");
        });
    }

    @Test
    void applicationLocaleFile_isUsedWithEmptyBaseFile() {
        bootMessages.withPropertyValues("spring.messages.basename=i18n/with-base").run(context -> {
            LocaleContextHolder.setLocale(new Locale("es"));
            assertThat(new ErrorMessages(context).get("error.internal.title")).isEqualTo("Error interno");
        });
    }

    @Test
    void unmatchedRequestLocale_usesApplicationBundleForJvmLocale() {
        bootMessages.withPropertyValues("spring.messages.basename=i18n/with-base").run(context -> {
            LocaleContextHolder.setLocale(Locale.FRENCH);
            assertThat(new ErrorMessages(context).get("error.internal.title")).isEqualTo("Erro da aplicação");
        });
    }

    @Test
    void unmatchedRequestLocale_getsEnglishWithSystemLocaleFallbackOff() {
        bootMessages.withPropertyValues("spring.messages.basename=i18n/with-base",
                "spring.messages.fallback-to-system-locale=false").run(context -> {
            LocaleContextHolder.setLocale(Locale.FRENCH);
            assertThat(new ErrorMessages(context).get("error.internal.title")).isEqualTo("Internal server error");
        });
    }

    @Test
    void applicationMessageOverridesLibraryMessage() {
        var appMessages = new StaticMessageSource();
        appMessages.addMessage("error.internal.title", Locale.ENGLISH, "Oops");
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(new ErrorMessages(appMessages).get("error.internal.title")).isEqualTo("Oops");
    }
}
