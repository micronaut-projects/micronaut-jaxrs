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

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JaxRsClientRequestContextTest {

    @Test
    void propertyNamesAreImmutable() {
        AtomicInteger counter = new AtomicInteger();
        Client client = ClientBuilder.newClient()
            .register(new ClientRequestFilter() {
                @Override
                public void filter(ClientRequestContext context) throws IOException {
                    Collection<String> propertyNames = context.getPropertyNames();
                    try {
                        propertyNames.add("new-name");
                    } catch (Exception e) {
                        // Expected: ClientRequestContext property names are immutable.
                    }
                    if (context.getPropertyNames().contains("new-name")) {
                        counter.incrementAndGet();
                    }
                    context.abortWith(Response.ok(counter.get()).build());
                }
            });

        try (client) {
            Response response = client.target("http://localhost/property-names").request().buildGet().invoke();

            assertEquals(0, counter.get());
            assertEquals("0", response.readEntity(String.class));
        }
    }
}
