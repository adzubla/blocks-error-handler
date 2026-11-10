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

    @Test
    void chain_listsTheExceptionAndItsCausesOutermostFirst() {
        var root = new SQLException("duplicate key");
        var middle = new RuntimeException("wrapper", root);
        var ex = new IllegalStateException("could not save", middle);

        assertThat(Causes.chain(ex)).containsExactly(ex, middle, root);
    }

    @Test
    void chain_endsOnACauseLoop() {
        var first = new RuntimeException("first");
        var second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(Causes.chain(first)).containsExactly(first, second);
    }
}
