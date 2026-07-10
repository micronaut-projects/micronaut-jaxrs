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

import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MicronautRuntimeDelegateTest {

    private final MicronautRuntimeDelegate delegate = new MicronautRuntimeDelegate();

    @Test
    void createEndpointRejectsNullArguments() {
        Application application = new Application();

        assertThrows(IllegalArgumentException.class, () -> delegate.createEndpoint(null, Object.class));
        assertThrows(IllegalArgumentException.class, () -> delegate.createEndpoint(application, null));
    }

    @Test
    void createEndpointRemainsUnsupportedForNonNullEndpointTypes() {
        assertThrows(
            UnsupportedOperationException.class,
            () -> delegate.createEndpoint(new Application(), Object.class)
        );
    }

    @Test
    void createsSeBootstrapConfigurationWithDefaults() {
        SeBootstrap.Configuration configuration = delegate.createConfigurationBuilder().build();

        assertEquals("HTTP", configuration.protocol());
        assertEquals("localhost", configuration.host());
        assertEquals(SeBootstrap.Configuration.DEFAULT_PORT, configuration.port());
        assertEquals("/", configuration.rootPath());
    }

    @Test
    void createsSeBootstrapConfigurationFromPropertiesAndExternalSource() {
        SeBootstrap.Configuration configuration = delegate.createConfigurationBuilder()
            .property(SeBootstrap.Configuration.PROTOCOL, "HTTP")
            .from((property, type) -> {
                if (property.equals(SeBootstrap.Configuration.HOST)) {
                    return Optional.of(type.cast("127.0.0.1"));
                }
                if (property.equals(SeBootstrap.Configuration.PORT)) {
                    return Optional.of(type.cast(8080));
                }
                if (property.equals(SeBootstrap.Configuration.ROOT_PATH)) {
                    return Optional.of(type.cast("/root/path"));
                }
                return Optional.empty();
            })
            .build();

        assertEquals("HTTP", configuration.protocol());
        assertEquals("127.0.0.1", configuration.host());
        assertEquals(8080, configuration.port());
        assertEquals("/root/path", configuration.rootPath());
    }

    @Test
    void seBootstrapFailsFastInsteadOfRecursing() {
        SeBootstrap.Configuration configuration = delegate.createConfigurationBuilder().build();

        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> delegate.bootstrap(new Application(), configuration).toCompletableFuture().get()
        );
        assertEquals(UnsupportedOperationException.class, exception.getCause().getClass());
    }

    @Test
    void seBootstrapByApplicationClassFailsFastInsteadOfReflectiveInstantiation() {
        SeBootstrap.Configuration configuration = delegate.createConfigurationBuilder().build();

        ExecutionException exception = assertThrows(
            ExecutionException.class,
            () -> delegate.bootstrap(UninstantiableApplication.class, configuration).toCompletableFuture().get()
        );
        assertEquals(UnsupportedOperationException.class, exception.getCause().getClass());
    }

    static final class UninstantiableApplication extends Application {
        private UninstantiableApplication() {
            throw new AssertionError("The common runtime delegate must not instantiate application classes reflectively");
        }
    }
}
