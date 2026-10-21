package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The request filters read the entity, buffered before they run, without consuming it for the
 * resource method.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterEntityStreamTest")
class FilterEntityStreamTest {

    @Inject
    @Client("/api/filter-entity-stream")
    HttpClient client;

    @Test
    void filtersReadTheEntity() {
        String body = "hello".repeat(10_000);
        String result = client.toBlocking().retrieve(HttpRequest.POST("/", body).contentType(MediaType.TEXT_PLAIN_TYPE));
        assertEquals("pre " + body.length() + " post " + body.length() + " entity " + body.length(), result);
    }

    private static String read(ContainerRequestContext context) throws IOException {
        try (InputStream in = context.getEntityStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Requires(property = "spec.name", value = "FilterEntityStreamTest")
    @Provider
    @PreMatching
    public static class PreMatchingReadingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            if (requestContext.getUriInfo().getPath().endsWith("filter-entity-stream")) {
                requestContext.getHeaders().putSingle("X-Pre", String.valueOf(read(requestContext).length()));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterEntityStreamTest")
    @Provider
    public static class ReadingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            if (requestContext.getUriInfo().getPath().endsWith("filter-entity-stream")) {
                // read twice: each stream is a copy
                read(requestContext);
                requestContext.getHeaders().putSingle("X-Post", String.valueOf(read(requestContext).length()));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterEntityStreamTest")
    @Path("/filter-entity-stream")
    public static class EntityResource {
        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        public String post(@jakarta.ws.rs.HeaderParam("X-Pre") String pre, @jakarta.ws.rs.HeaderParam("X-Post") String post, String entity) {
            return "pre " + pre + " post " + post + " entity " + entity.length();
        }
    }
}
