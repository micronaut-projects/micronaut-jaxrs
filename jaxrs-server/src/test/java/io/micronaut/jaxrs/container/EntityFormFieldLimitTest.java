package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Form;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The form of an url-encoded entity that a resource method reads as its entity and its form
 * parameters has the form field limit of the server, like the forms the server parses.
 */
@MicronautTest
@Property(name = "spec.name", value = "EntityFormFieldLimitTest")
@Property(name = "micronaut.server.netty.form-max-fields", value = "10")
class EntityFormFieldLimitTest {

    @Inject
    @Client("/api/entity-form-limit")
    HttpClient client;

    @Test
    void withinTheLimit() {
        assertEquals("v0:10", client.toBlocking().retrieve(post(10)));
    }

    @Test
    void overTheLimit() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().retrieve(post(11)));
        assertEquals(413, e.getStatus().getCode());
    }

    private static HttpRequest<String> post(int fields) {
        String body = IntStream.range(0, fields).mapToObj(i -> (i == 0 ? "a" : "f" + i) + "=v" + i).collect(Collectors.joining("&"));
        return HttpRequest.POST("/", body).contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    }

    @Requires(property = "spec.name", value = "EntityFormFieldLimitTest")
    @Path("/entity-form-limit")
    public static class FormResource {
        @POST
        @Consumes("application/x-www-form-urlencoded")
        @Produces("text/plain")
        public String post(@Encoded @FormParam("a") String a, Form form) {
            return a + ":" + form.asMap().size();
        }
    }
}
