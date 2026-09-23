/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.common.bootstrap;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.SeBootstrap;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The {@link SeBootstrap.Configuration}: the properties that were set, else their defaults.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class JaxRsSeConfiguration implements SeBootstrap.Configuration {

    private final Map<String, Object> properties;

    /**
     * @param properties The properties
     */
    public JaxRsSeConfiguration(Map<String, Object> properties) {
        this.properties = Map.copyOf(properties);
    }

    @Override
    public @Nullable Object property(String name) {
        Object value = properties.get(name);
        if (value != null) {
            return value;
        }
        return switch (name) {
            case PROTOCOL -> "HTTP";
            case HOST -> "localhost";
            case PORT -> DEFAULT_PORT;
            case ROOT_PATH -> "/";
            case SSL_CLIENT_AUTHENTICATION -> SSLClientAuthentication.NONE;
            case SSL_CONTEXT -> defaultSslContext();
            default -> null;
        };
    }

    @Override
    public boolean hasProperty(String name) {
        return properties.containsKey(name);
    }

    private static @Nullable SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * The {@link SeBootstrap.Configuration.Builder}.
     */
    @Internal
    public static final class Builder implements SeBootstrap.Configuration.Builder {

        private static final Map<String, Class<?>> SUPPORTED = Map.of(
            PROTOCOL, String.class,
            HOST, String.class,
            PORT, Integer.class,
            ROOT_PATH, String.class,
            SSL_CONTEXT, SSLContext.class,
            SSL_CLIENT_AUTHENTICATION, SSLClientAuthentication.class
        );

        private final Map<String, Object> properties = new HashMap<>();

        @Override
        public SeBootstrap.Configuration build() {
            return new JaxRsSeConfiguration(properties);
        }

        @Override
        public SeBootstrap.Configuration.Builder property(String name, @Nullable Object value) {
            if (value == null) {
                properties.remove(name);
            } else {
                properties.put(name, value);
            }
            return this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> SeBootstrap.Configuration.Builder from(BiFunction<String, Class<T>, Optional<T>> propertiesProvider) {
            // the provider is asked for each supported property, with its type
            SUPPORTED.forEach((name, type) ->
                propertiesProvider.apply(name, (Class<T>) type).ifPresent(value -> property(name, value)));
            return this;
        }
    }
}
