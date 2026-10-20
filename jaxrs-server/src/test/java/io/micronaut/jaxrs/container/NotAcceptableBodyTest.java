package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * When no acceptable type has a writer for the entity, the response is not acceptable (JAX-RS,
 * section 3.8), not a server error.
 */
@MicronautTest
@Property(name = "spec.name", value = "NotAcceptableBodyTest")
class NotAcceptableBodyTest {

    @Inject
    @Client("/api/not-acceptable-body")
    HttpClient client;

    @ParameterizedTest
    @ValueSource(strings = {"/any", "/json-or-text"})
    void noWriterForTheAcceptableTypes(String path) {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET(path).accept("application/json;q=0, text/plain"), String.class));
        assertEquals(HttpStatus.NOT_ACCEPTABLE, e.getStatus());
    }

    @Test
    void writtenInTheAcceptedType() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/any").accept("text/plain;q=0.5, application/json"), String.class);
        assertEquals("application/json", response.getContentType().orElseThrow().toString());
        assertEquals("{\"name\":\"pojo\"}", response.body());
    }

    @Serdeable
    public record Pojo(String name) {
    }

    @Requires(property = "spec.name", value = "NotAcceptableBodyTest")
    @Path("/not-acceptable-body")
    public static class NotAcceptableBodyResource {

        @GET
        @Path("/any")
        public Pojo any() {
            return new Pojo("pojo");
        }

        @GET
        @Path("/json-or-text")
        @Produces({"application/json", "text/plain"})
        public Pojo jsonOrText() {
            return new Pojo("pojo");
        }
    }
}
