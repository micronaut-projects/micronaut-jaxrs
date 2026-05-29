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
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jaxrs.common.HttpMessageEntityReader;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.NoContentException;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@MicronautTest
@Property(name = "spec.name", value = "StandardProviderNotNullTest")
class StandardProviderNotNullTest {

    @Inject
    @Client("/api/standard-provider")
    HttpClient client;

    @Test
    void zeroLengthFileEntityIsNotNull() {
        assertEquals("EXPECTED", client.toBlocking().retrieve(emptyPost("/file")));
    }

    @Test
    void zeroLengthPrimitiveStyleEntitiesAreBadRequest() {
        HttpClientResponseException booleanException = assertThrows(
            HttpClientResponseException.class,
            () -> client.toBlocking().exchange(emptyTextPost("/boolean"), String.class)
        );
        assertEquals(HttpStatus.BAD_REQUEST, booleanException.getStatus());

        HttpClientResponseException integerException = assertThrows(
            HttpClientResponseException.class,
            () -> client.toBlocking().exchange(emptyTextPost("/integer"), String.class)
        );
        assertEquals(HttpStatus.BAD_REQUEST, integerException.getStatus());
    }

    @Test
    void clientEntityReaderThrowsNoContentForPrimitiveStyleEmptyEntity() {
        ProcessingException exception = assertThrows(
            ProcessingException.class,
            () -> HttpMessageEntityReader.DEFAULT.readEntity(HttpResponse.ok(), Argument.of(Boolean.class))
        );

        assertInstanceOf(NoContentException.class, exception.getCause());
    }

    private static HttpRequest<byte[]> emptyPost(String path) {
        return HttpRequest.POST(path, new byte[0])
            .contentType(MediaType.APPLICATION_OCTET_STREAM_TYPE);
    }

    private static HttpRequest<byte[]> emptyTextPost(String path) {
        return HttpRequest.POST(path, new byte[0])
            .contentType(MediaType.TEXT_PLAIN_TYPE);
    }

    @Requires(property = "spec.name", value = "StandardProviderNotNullTest")
    @Path("/standard-provider")
    static class StandardProviderResource {

        @POST
        @Path("/file")
        @Produces(MediaType.TEXT_PLAIN)
        String file(File file) {
            return isNull(file);
        }

        @POST
        @Path("/boolean")
        @Produces(MediaType.TEXT_PLAIN)
        String bool(Boolean bool) {
            return isNull(bool);
        }

        @POST
        @Path("/integer")
        @Produces(MediaType.TEXT_PLAIN)
        String integer(Integer integer) {
            return isNull(integer);
        }

        private static String isNull(Object value) {
            return value == null ? "NULL" : "EXPECTED";
        }
    }
}
