package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Request filters with an entity larger than what the server reads ahead (64 KiB), also sent
 * after {@code 100 Continue}: the buffered copy the filters read must not wait for the resource.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterLargeEntityTest")
class FilterLargeEntityTest {

    private static final int SIZE = 200_000;

    @Inject
    EmbeddedServer server;

    @Test
    void largeEntity() throws Exception {
        assertEquals(SIZE + ":" + SIZE + ":" + SIZE, post("/api/large-entity", false));
    }

    @Test
    void largeEntityExpectContinue() throws Exception {
        assertEquals(SIZE + ":" + SIZE + ":" + SIZE, post("/api/large-entity", true));
    }

    @Test
    void largeEntityOfController() throws Exception {
        assertEquals(String.valueOf(SIZE), post("/api/large-entity-controller", false));
        assertEquals(String.valueOf(SIZE), post("/api/large-entity-controller", true));
    }

    private String post(String path, boolean expectContinue) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + path))
                .timeout(Duration.ofSeconds(10))
                .expectContinue(expectContinue)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("a".repeat(SIZE)))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            return response.body();
        }
    }

    @Requires(property = "spec.name", value = "FilterLargeEntityTest")
    @Provider
    @PreMatching
    public static class PreMatchingReadingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            if (requestContext.getUriInfo().getPath().contains("large-entity")) {
                requestContext.getHeaders().putSingle("X-Pre", String.valueOf(requestContext.getEntityStream().readAllBytes().length));
            }
        }
    }

    @Requires(property = "spec.name", value = "FilterLargeEntityTest")
    @Provider
    public static class ReadingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            requestContext.getHeaders().putSingle("X-Post", String.valueOf(requestContext.getEntityStream().readAllBytes().length));
        }
    }

    @Requires(property = "spec.name", value = "FilterLargeEntityTest")
    @Path("/large-entity")
    public static class LargeEntityResource {

        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        public String post(String body, @HeaderParam("X-Pre") String pre, @HeaderParam("X-Post") String post) {
            return pre + ":" + post + ":" + body.length();
        }
    }

    @Requires(property = "spec.name", value = "FilterLargeEntityTest")
    @Controller("/large-entity-controller")
    public static class LargeEntityController {

        @Post(consumes = "text/plain", produces = "text/plain")
        public String post(@Body String body) {
            return String.valueOf(body.length());
        }
    }
}
