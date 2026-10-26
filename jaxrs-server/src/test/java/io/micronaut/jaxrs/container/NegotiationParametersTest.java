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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The negotiation of JAX-RS (section 3.8) excludes the accepted types with {@code q=0}, and the
 * combined type keeps the parameters of the produced type, but {@code q} and {@code qs}.
 */
@MicronautTest
@Property(name = "spec.name", value = "NegotiationParametersTest")
class NegotiationParametersTest {

    @Inject
    @Client("/api/negotiation-parameters")
    HttpClient client;

    @Test
    void notSelectedWithQualityZero() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/plain").accept("text/plain;q=0"), String.class));
        // the method is not invoked; JAX-RS answers 406, which needs the router to exclude q=0 too
        assertEquals(4, e.code() / 100);
    }

    @Test
    void combinedTypeKeepsTheParametersOfTheProducedType() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/charset").accept("text/*"), String.class);
        assertEquals("text/plain;charset=ISO-8859-1", response.getContentType().orElseThrow().toString());
    }

    @Test
    void rangeWithParametersRejectedWithQualityZeroIsNotRevivedByTheBroaderRange() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/flowed")
                .accept("text/plain;format=flowed;q=0, text/plain;q=1"), String.class));
        assertEquals(406, e.code());
    }

    @Test
    void rangeWithOtherParametersDoesNotRejectTheType() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/flowed")
            .accept("text/plain;format=fixed;q=0, text/plain;q=1"), String.class);
        assertEquals("flowed", response.body());
    }

    @Requires(property = "spec.name", value = "NegotiationParametersTest")
    @Path("/negotiation-parameters")
    public static class NegotiationResource {

        @GET
        @Path("/plain")
        @Produces("text/plain")
        public String plain() {
            return "plain";
        }

        @GET
        @Path("/flowed")
        @Produces("text/plain;format=flowed")
        public String flowed() {
            return "flowed";
        }

        @GET
        @Path("/charset")
        @Produces("text/plain;charset=ISO-8859-1;qs=0.9")
        public String charset() {
            return "charset";
        }
    }
}
