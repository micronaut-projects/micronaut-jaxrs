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

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void setMethodReplacesMutableRequestMethod() {
        try (Client client = ClientBuilder.newClient()) {
            MutableHttpRequest<String> request = HttpRequest.POST("http://localhost/request-method", "body")
                .contentType(MediaType.TEXT_PLAIN_TYPE)
                .header("X-Test", "value");
            JaxRsClientRequestContext context = new JaxRsClientRequestContext(
                client,
                client.getConfiguration(),
                request,
                Argument.STRING
            );

            context.setMethod("PUT");

            assertEquals("PUT", context.getMethod());
            assertEquals(HttpMethod.PUT, context.getMutableHttpRequest().getMethod());
            assertEquals("value", context.getHeaderString("X-Test"));
            assertEquals("body", context.getEntity());
        }
    }

    @Test
    void wildcardEntityMediaTypeDoesNotCreateContentTypeHeader() {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<jakarta.ws.rs.core.MediaType> mediaType = new AtomicReference<>();
        try (Client client = ClientBuilder.newClient()
            .register((ClientRequestFilter) context -> {
                contentType.set(context.getHeaderString("Content-Type"));
                mediaType.set(context.getMediaType());
                context.abortWith(Response.ok("ok").build());
            })) {
            String response = client.target("http://localhost/wildcard")
                .request()
                .post(Entity.entity("body", jakarta.ws.rs.core.MediaType.WILDCARD_TYPE), String.class);

            assertEquals("ok", response);
            assertNull(contentType.get());
            assertEquals(jakarta.ws.rs.core.MediaType.WILDCARD_TYPE, mediaType.get());
        }
    }

    @Test
    void setEntityWithWildcardMediaTypeClearsContentTypeHeader() {
        try (Client client = ClientBuilder.newClient()) {
            MutableHttpRequest<String> request = HttpRequest.POST("http://localhost/request-entity", "body")
                .contentType(MediaType.TEXT_PLAIN_TYPE);
            JaxRsClientRequestContext context = new JaxRsClientRequestContext(
                client,
                client.getConfiguration(),
                request,
                Argument.STRING
            );

            context.setEntity("updated", new Annotation[0], jakarta.ws.rs.core.MediaType.WILDCARD_TYPE);

            assertTrue(context.getMutableHttpRequest().getContentType().isEmpty());
            assertEquals(jakarta.ws.rs.core.MediaType.WILDCARD_TYPE, context.getMediaType());
        }
    }

    @Test
    void entityStreamWrapperMutatesSerializedBody() throws IOException {
        try (Client client = ClientBuilder.newClient()) {
            MutableHttpRequest<byte[]> request = HttpRequest.POST(
                "http://localhost/entity-stream",
                "ENXIXY_STREAM_WORKS".getBytes(StandardCharsets.UTF_8)
            );
            JaxRsClientRequestContext context = new JaxRsClientRequestContext(
                client,
                client.getConfiguration(),
                request,
                Argument.of(byte[].class)
            );

            OutputStream wrapper = new ReplacingOutputStream(context.getEntityStream(), 'X', 'T');
            context.setEntityStream(wrapper);

            byte[] body = context.getMutableHttpRequest().getBody(byte[].class).orElseThrow();
            assertEquals("ENTITY_STREAM_WORKS", new String(body, StandardCharsets.UTF_8));
        }
    }

    private static final class ReplacingOutputStream extends OutputStream {
        private final OutputStream target;
        private final char source;
        private final char replacement;

        private ReplacingOutputStream(OutputStream target, char source, char replacement) {
            this.target = target;
            this.source = source;
            this.replacement = replacement;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b });
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(new String(b, off, len, StandardCharsets.UTF_8)
                .replace(source, replacement)
                .getBytes(StandardCharsets.UTF_8));
        }
    }
}
