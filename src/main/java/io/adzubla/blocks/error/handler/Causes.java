package io.adzubla.blocks.error.handler;

import org.springframework.core.NestedExceptionUtils;

/**
 * Describes a handled exception for the log, so the reason behind a response can be reviewed later.
 */
final class Causes {

    private Causes() {
    }

    /**
     * The exception's type and message, followed by those of its root cause when it has one. The
     * root cause is where the actual reason usually is (the driver's {@code SQLException}, the
     * {@code IOException} behind a multipart failure), and wrappers often don't repeat it.
     */
    static String describe(Throwable ex) {
        var root = NestedExceptionUtils.getMostSpecificCause(ex);
        return root == ex ? ex.toString() : ex + "; root cause: " + root;
    }
}
