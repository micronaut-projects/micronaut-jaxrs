package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The preconditions and the variant selection of {@link Request}.
 */
@MicronautTest
@Property(name = "spec.name", value = "RequestPreconditionsTest")
class RequestPreconditionsTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void staleUnmodifiedSinceWithEntityTagFails() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.PUT("/preconditions", "x")
                .header("If-Unmodified-Since", "Thu, 01 Jan 1970 00:00:01 GMT"), String.class));
        assertEquals(412, e.getStatus().getCode());
    }

    @Test
    void moreSpecificRejectedRangeIsNotSelected() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/preconditions/variant")
            .accept("text/plain;q=0, */*;q=1"), String.class);
        assertEquals("application/json", response.body());
    }

    @Requires(property = "spec.name", value = "RequestPreconditionsTest")
    @Path("/preconditions")
    public static class PreconditionsResource {
        @PUT
        public Response put(@Context Request request) {
            Response.ResponseBuilder builder = request.evaluatePreconditions(new Date(), new EntityTag("a"));
            return builder != null ? builder.build() : Response.ok("updated").build();
        }

        @GET
        @Path("variant")
        public String variant(@Context Request request) {
            Variant variant = request.selectVariant(Variant.mediaTypes(MediaType.TEXT_PLAIN_TYPE, MediaType.APPLICATION_JSON_TYPE).build());
            return variant == null ? "none" : variant.getMediaType().toString();
        }
    }
}
