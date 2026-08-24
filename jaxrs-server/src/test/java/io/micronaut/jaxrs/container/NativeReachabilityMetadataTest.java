/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.jaxrs.container;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeReachabilityMetadataTest {

    @Test
    void registersQueryParamBinderConstructors() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "META-INF/native-image/io.micronaut.jaxrs/micronaut-jaxrs-server/reachability-metadata.json")) {
            Assertions.assertNotNull(stream);
            String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("io.micronaut.jaxrs.container.QueryParamArgumentBinder"));
            assertTrue(metadata.contains("io.micronaut.core.bind.annotation.AbstractArgumentBinder"));
            assertTrue(metadata.contains("io.micronaut.core.convert.ConversionService"));
        }
    }
}
