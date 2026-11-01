package io.adzubla.blocks.error.handler;

import org.junit.jupiter.api.Test;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ValidationExceptionHandler} is registered even without Bean Validation, e.g. for a custom
 * Spring {@code Validator}, so {@link Violations} must work when {@code jakarta.validation} is absent.
 */
class ViolationsClassLoadingTests {

    @Test
    @SuppressWarnings("unchecked")
    void fieldErrorKeepsItsValueWithoutBeanValidationOnTheClasspath() throws Exception {
        var violations = new WithoutBeanValidation().loadClass(Violations.class.getName());
        var of = violations.getDeclaredMethod("of", String.class, ObjectError.class);
        of.setAccessible(true);

        var violation = of.invoke(null, "$", new FieldError("request", "name", "", false, null, null, "blank"));

        assertThat((Map<Object, Object>) violation)
                .containsEntry("path", "$.name")
                .containsEntry("invalidValue", "")
                .containsEntry("message", "blank");
    }

    /** Defines {@code Violations} itself, and can't see {@code jakarta.validation}. */
    private static final class WithoutBeanValidation extends ClassLoader {

        WithoutBeanValidation() {
            super(ViolationsClassLoadingTests.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("jakarta.validation.")) {
                throw new ClassNotFoundException(name);
            }
            if (!name.startsWith(Violations.class.getName())) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                return loaded != null ? loaded : define(name);
            }
        }

        private Class<?> define(String name) throws ClassNotFoundException {
            try (var in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                var bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (java.io.IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }
}
