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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.SeBootstrap;

import javax.net.ssl.SSLContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Default {@link SeBootstrap.Configuration} implementation.
 */
@Internal
final class JaxRsSeBootstrapConfiguration implements SeBootstrap.Configuration {

    private final Map<String, Object> properties;

    private JaxRsSeBootstrapConfiguration(Map<String, Object> properties) {
        this.properties = Map.copyOf(properties);
    }

    @Override
    public Object property(String name) {
        return properties.get(name);
    }

    static final class Builder implements SeBootstrap.Configuration.Builder {

        private final Map<String, Object> properties = new LinkedHashMap<>();

        Builder() {
            properties.put(PROTOCOL, "HTTP");
            properties.put(HOST, "localhost");
            properties.put(PORT, DEFAULT_PORT);
            properties.put(ROOT_PATH, "/");
        }

        @Override
        public SeBootstrap.Configuration build() {
            return new JaxRsSeBootstrapConfiguration(properties);
        }

        @Override
        public SeBootstrap.Configuration.Builder property(String name, Object value) {
            JaxRsUtils.requireNonNull("name", name);
            if (value == null) {
                properties.remove(name);
            } else {
                properties.put(name, value);
            }
            return this;
        }

        @Override
        public <T> SeBootstrap.Configuration.Builder from(BiFunction<String, Class<T>, Optional<T>> externalConfiguration) {
            JaxRsUtils.requireNonNull("externalConfiguration", externalConfiguration);
            propertyFrom(externalConfiguration, PROTOCOL, String.class);
            propertyFrom(externalConfiguration, HOST, String.class);
            propertyFrom(externalConfiguration, PORT, Integer.class);
            propertyFrom(externalConfiguration, ROOT_PATH, String.class);
            propertyFrom(externalConfiguration, SSL_CONTEXT, SSLContext.class);
            propertyFrom(externalConfiguration, SSL_CLIENT_AUTHENTICATION, SSLClientAuthentication.class);
            return this;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void propertyFrom(BiFunction externalConfiguration,
                                  String property,
                                  Class<?> type) {
            Optional<?> value = (Optional<?>) externalConfiguration.apply(property, type);
            value.ifPresent(resolved -> property(property, resolved));
        }
    }
}
