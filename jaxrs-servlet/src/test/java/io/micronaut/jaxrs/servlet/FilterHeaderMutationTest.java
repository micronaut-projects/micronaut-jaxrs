package io.micronaut.jaxrs.servlet;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@MicronautTest
public class FilterHeaderMutationTest {

    @Test
    void testHeadersCanBeMutated(@Client("/") HttpClient client) {
        String result = client.toBlocking().retrieve("/headers/test");
        Assertions.assertEquals("bar", result);
    }

    @Path("/headers/test")
    public static class TestResource {

        @GET
        @Produces("text/plain")
        public String get(HttpHeaders httpHeaders) {
            String header = httpHeaders.getHeaderString("foo");
            return header != null ? header : "Unknown";
        }
    }

    @Provider
    public static class TestFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext containerRequestContext) throws IOException {
            containerRequestContext.getHeaders().add("foo", "bar");
        }
    }
}
