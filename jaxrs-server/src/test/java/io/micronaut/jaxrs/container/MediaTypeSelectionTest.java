package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Resource methods selected by the accepted types and the qs of the produced types (JAX-RS 3.7.2
 * and 3.8).
 */
@MicronautTest
@Property(name = "spec.name", value = "MediaTypeSelectionTest")
class MediaTypeSelectionTest {

    @Inject
    @Client("/api/media")
    HttpClient client;

    private HttpResponse<String> post(String accept) {
        MutableHttpRequest<String> request = HttpRequest.POST("/weight", "");
        if (accept != null) {
            request = request.accept(accept);
        }
        return client.toBlocking().exchange(request, String.class);
    }

    @Test
    void noAcceptSelectsTheHighestQs() {
        HttpResponse<String> response = post(null);
        assertEquals("text/plain", response.body());
        assertEquals("text/plain", response.getContentType().orElseThrow().toString());
    }

    @Test
    void wildcardSubtypeSelectsTheHighestQsOfTheType() {
        HttpResponse<String> response = post("text/*");
        assertEquals("text/plain", response.body());
        assertEquals("text/plain", response.getContentType().orElseThrow().toString());
    }

    @Test
    void concreteProducedTypeIsMoreSpecificThanAHigherQsWildcard() {
        HttpResponse<String> response = post("image/*");
        assertEquals("image/png", response.body());
        assertEquals("image/png", response.getContentType().orElseThrow().toString());
    }

    @Test
    void wildcardProducedTypeIsNotAcceptable() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/error").accept("text/*"), String.class));
        assertEquals(406, e.getStatus().getCode());
    }

    @Requires(property = "spec.name", value = "MediaTypeSelectionTest")
    @Path("/media")
    public static class WeightResource {
        @POST
        @Path("weight")
        @Produces("text/plain;qs=0.9")
        public String plain() {
            return "text/plain";
        }

        @POST
        @Path("weight")
        @Produces("text/html;qs=0.8")
        public String html() {
            return "text/html";
        }

        @POST
        @Path("weight")
        @Produces("image/png;qs=0.6")
        public String png() {
            return "image/png";
        }

        @POST
        @Path("weight")
        @Produces("image/*;qs=0.7")
        public String image() {
            return "image/any";
        }

        @GET
        @Path("error")
        @Produces("text/*")
        public String error() {
            return "error";
        }
    }
}
