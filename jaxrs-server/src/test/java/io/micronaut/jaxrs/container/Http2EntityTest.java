package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A streamed HTTP/2 request body has neither a length nor a transfer encoding: the request still
 * has an entity.
 */
@MicronautTest
@Property(name = "spec.name", value = "Http2EntityTest")
@Property(name = "micronaut.server.http-version", value = "2.0")
class Http2EntityTest {

    @Inject
    EmbeddedServer server;

    @Test
    void streamedBodyIsAnEntity() throws Exception {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setPlaintextMode(io.micronaut.http.client.HttpVersionSelection.PlaintextMode.H2C);
        try (HttpClient client = HttpClient.create(server.getURL(), configuration)) {
            HttpRequest<?> request = HttpRequest.POST("/api/http2-entity",
                    Publishers.just("hello".getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.TEXT_PLAIN_TYPE);
            assertEquals("true hello", client.toBlocking().retrieve(request));
        }
    }

    @Requires(property = "spec.name", value = "Http2EntityTest")
    @Provider
    public static class EntityFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getUriInfo().getPath().endsWith("http2-entity")) {
                if (!requestContext.hasEntity()) {
                    requestContext.abortWith(Response.status(400).entity("no entity").build());
                    return;
                }
                requestContext.getHeaders().putSingle("X-Entity", "true");
            }
        }
    }

    @Requires(property = "spec.name", value = "Http2EntityTest")
    @Path("/http2-entity")
    public static class Resource {

        @POST
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.TEXT_PLAIN)
        public String post(@jakarta.ws.rs.HeaderParam("X-Entity") String entity, String body) {
            return entity + " " + body;
        }
    }
}
