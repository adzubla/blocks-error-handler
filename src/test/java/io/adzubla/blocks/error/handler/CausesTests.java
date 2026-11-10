package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class CausesTests {

    @Test
    void exceptionWithoutCause_isDescribedByTypeAndMessage() {
        assertThat(Causes.describe(new IllegalStateException("boom")))
                .isEqualTo("java.lang.IllegalStateException: boom");
    }

    @Test
    void wrappedException_namesItsRootCause() {
        var root = new SQLException("duplicate key");
        var ex = new IllegalStateException("could not save", new RuntimeException("wrapper", root));

        assertThat(Causes.describe(ex)).isEqualTo(
                "java.lang.IllegalStateException: could not save; root cause: java.sql.SQLException: duplicate key");
    }
}
