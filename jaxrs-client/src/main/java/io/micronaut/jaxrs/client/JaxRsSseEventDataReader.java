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
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Internal;
import jakarta.ws.rs.core.MediaType;

import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Internal hook for optional SSE event data conversions.
 */
@Internal
public interface JaxRsSseEventDataReader {

    /**
     * Reads SSE event data as the requested type when the optional reader supports it.
     *
     * @param type The raw target type
     * @param genericType The generic target type
     * @param mediaType The requested media type
     * @param data The event data
     * @param <T> The result type
     * @return The converted value if supported
     */
    <T> Optional<T> readData(Class<T> type, Type genericType, MediaType mediaType, String data);
}
