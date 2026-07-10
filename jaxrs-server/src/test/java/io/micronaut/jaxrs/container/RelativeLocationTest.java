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

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
@Property(name = "spec.name", value = "RelativeLocationTest")
class RelativeLocationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmbeddedServer server;

    @Test
    void createdResponseResolvesRelativeLocationAgainstApplicationBaseUri() {
        HttpResponse<?> response = client.toBlocking()
            .exchange(HttpRequest.GET("/api/relative-location/created"), String.class);

        assertEquals(
            server.getURI().resolve("/api/created").toString(),
            response.getHeaders().get(HttpHeaders.LOCATION)
        );
    }

    @Requires(property = "spec.name", value = "RelativeLocationTest")
    @Path("/relative-location")
    static final class RelativeLocationResource {

        @GET
        @Path("/created")
        Response created() {
            return Response.created(URI.create("created"))
                .status(Response.Status.OK)
                .build();
        }
    }
}
