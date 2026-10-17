package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The header values of a {@code Response} subclass of the application are written with the
 * header delegates of their types, a date as an HTTP date.
 */
@MicronautTest
@Property(name = "spec.name", value = "ForeignResponseHeadersTest")
class ForeignResponseHeadersTest {

    @Inject
    @Client("/api/foreign-response-headers")
    HttpClient client;

    @Test
    void headerValuesOfTheirTypes() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/"), String.class);
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", response.header("X-Date"));
        assertEquals("\"v1\"", response.header("ETag"));
        assertEquals("text/plain", response.header("X-Type"));
        assertEquals("n=v;Version=1", response.header("Set-Cookie"));
    }

    @Requires(property = "spec.name", value = "ForeignResponseHeadersTest")
    @Path("/foreign-response-headers")
    public static class ForeignResponseResource {

        @GET
        public Response get() {
            return new ExceptionStatusTest.DelegatingResponse(Response.ok()
                .header("X-Date", new Date(0))
                .header("X-Type", MediaType.TEXT_PLAIN_TYPE)
                .tag(new EntityTag("v1"))
                .cookie(new NewCookie.Builder("n").value("v").build())
                .build());
        }
    }
}
