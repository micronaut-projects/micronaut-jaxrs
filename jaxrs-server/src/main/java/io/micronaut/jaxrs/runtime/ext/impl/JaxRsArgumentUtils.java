package io.micronaut.jaxrs.runtime.ext.impl;

import io.micronaut.core.annotation.Internal;

@Internal
final class JaxRsArgumentUtils {
    public static <T> T requireNonNull(String name, T value) {
        if (value == null) {
            throw new IllegalArgumentException("Argument [" + name + "] cannot be null");
        }
        return value;
    }
}
