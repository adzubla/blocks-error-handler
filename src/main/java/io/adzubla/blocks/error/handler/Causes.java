package io.adzubla.blocks.error.handler;

import org.springframework.core.NestedExceptionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Describes a handled exception for the log, so the reason behind a response can be reviewed later.
 */
final class Causes {

    private Causes() {
    }

    /**
     * The exception followed by its causes, outermost first.
     */
    static List<Throwable> chain(Throwable ex) {
        // Identity-based, so a cause loop ends instead of running forever.
        var chain = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var ordered = new ArrayList<Throwable>();
        for (var t = ex; t != null && chain.add(t); t = t.getCause()) {
            ordered.add(t);
        }
        return ordered;
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
