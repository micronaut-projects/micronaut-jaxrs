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

import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

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
}
