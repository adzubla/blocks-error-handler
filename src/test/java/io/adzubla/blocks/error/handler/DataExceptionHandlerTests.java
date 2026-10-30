package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.sql.SQLException;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classification of PostgreSQL errors written in a language other than English
 * ({@code lc_messages}). The messages are real PostgreSQL 18 output with
 * {@code lc_messages = 'pt_BR.UTF-8'}; the English ones are covered by {@link DataExceptionHandlerIT}.
 */
class DataExceptionHandlerTests {

    private final DataExceptionHandler handler =
            new DataExceptionHandler(new StaticMessageSource(), new DataExceptionProperties());

    // The client's language is independent of the database's: responses here are in English.
    @BeforeEach
    void englishClient() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void localizedUniqueViolation_isDuplicateValue() {
        var response = handler.handleDataIntegrityViolation(violation("23505", """
                ERRO: duplicar valor da chave viola a restrição de unicidade "p_name_key"
                  Detail: Chave (name)=(Widget (large)) já existe."""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "DUPLICATE_VALUE")
                .containsEntry("invalidValue", "Widget (large)");
    }

    @Test
    void localizedMissingReference_isReferentialIntegrityViolation() {
        var response = handler.handleDataIntegrityViolation(violation("23503", """
                ERRO: inserção ou atualização em tabela "c" viola restrição de chave estrangeira "c_p_id_fkey"
                  Detail: Chave (p_id)=(99) não está presente na tabela "p"."""));

        assertThat(response.getBody().getProperties())
                .containsEntry("code", "REFERENTIAL_INTEGRITY_VIOLATION")
                .containsEntry("invalidValue", "99");
        assertThat(response.getBody().getDetail()).isEqualTo("The request references a resource that does not exist.");
    }

    @Test
    void localizedStillReferenced_isReferentialIntegrityViolationWithItsOwnDetail() {
        var response = handler.handleDataIntegrityViolation(violation("23503", """
                ERRO: atualização ou exclusão em tabela "p" viola restrição de chave estrangeira "c_p_id_fkey" em "c"
                  Detail: Chave (id)=(1) ainda é referenciada pela tabela "c"."""));

        assertThat(response.getBody().getProperties())
                .containsEntry("code", "REFERENTIAL_INTEGRITY_VIOLATION")
                .containsEntry("invalidValue", "1");
        assertThat(response.getBody().getDetail()).startsWith("The resource is still referenced");
    }

    @Test
    void valueThatCannotBeExtracted_isOmitted() {
        var response = handler.handleDataIntegrityViolation(violation("23505", "unique violation"));

        assertThat(response.getBody().getProperties())
                .containsEntry("code", "DUPLICATE_VALUE")
                .doesNotContainKey("invalidValue");
    }

    private static DataIntegrityViolationException violation(String sqlState, String message) {
        return new DataIntegrityViolationException("could not execute statement",
                new SQLException(message, sqlState));
    }
}
