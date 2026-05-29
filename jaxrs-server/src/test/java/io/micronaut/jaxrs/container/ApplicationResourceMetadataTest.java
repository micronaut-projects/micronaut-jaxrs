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
package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.HttpClient;
import io.micronaut.jaxrs.common.JaxRsApplicationResources;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationResourceMetadataTest {

    @Test
    void emptyApplicationResourceMetadataPreventsRuntimeGetClassesFallback() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
            "spec.name", "ApplicationResourceMetadataTest"
        ));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("ok", client.toBlocking().retrieve("/metadata/resource"));
        }
    }

    @Requires(property = "spec.name", value = "ApplicationResourceMetadataTest")
    @Primary
    @Singleton
    @JaxRsApplicationResources({})
    static final class MetadataApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            throw new AssertionError("Empty generated application resource metadata should be used");
        }
    }

    @Requires(property = "spec.name", value = "ApplicationResourceMetadataTest")
    @Path("/metadata/resource")
    static final class MetadataResource {

        @GET
        String get() {
            return "ok";
        }
    }
}
