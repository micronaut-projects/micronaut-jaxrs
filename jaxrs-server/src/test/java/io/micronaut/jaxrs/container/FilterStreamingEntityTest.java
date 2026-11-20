package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
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
 * Request filters that do not read the entity do not buffer it: a request larger than the maximum
 * buffer size of the server, within its maximum request size, reaches a resource method that does
 * not read it. A filter that reads the entity buffers it, as the resource method reads it too.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterStreamingEntityTest")
@Property(name = "micronaut.server.max-request-buffer-size", value = "16384")
class FilterStreamingEntityTest {

    private static final int SIZE = 200_000;

    @Inject
    EmbeddedServer server;

    @Test
    void headerFilters() throws Exception {
        assertEquals("pre:post", post("/api/streaming-entity", false, 200));
    }

    @Test
    void readingFiltersBuffer() throws Exception {
        post("/api/streaming-entity", true, 413);
    }

    private String post(String path, boolean read, int status) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/octet-stream")
                .header("X-Read", String.valueOf(read))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[SIZE]))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(status, response.statusCode(), response.body());
            return response.body();
        }
    }

    private static String mark(ContainerRequestContext requestContext, String name) throws IOException {
        return Boolean.parseBoolean(requestContext.getHeaderString("X-Read"))
            ? String.valueOf(requestContext.getEntityStream().readAllBytes().length) : name;
    }

    @Requires(property = "spec.name", value = "FilterStreamingEntityTest")
    @Provider
    @PreMatching
    public static class PreMatchingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            requestContext.getHeaders().putSingle("X-Pre", mark(requestContext, "pre"));
        }
    }

    @Requires(property = "spec.name", value = "FilterStreamingEntityTest")
    @Provider
    public static class PostMatchingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) throws IOException {
            requestContext.getHeaders().putSingle("X-Post", mark(requestContext, "post"));
        }
    }

    @Requires(property = "spec.name", value = "FilterStreamingEntityTest")
    @Path("/streaming-entity")
    public static class StreamingEntityResource {

        @POST
        @Consumes("application/octet-stream")
        @Produces("text/plain")
        public String post(@HeaderParam("X-Pre") String pre, @HeaderParam("X-Post") String post) {
            return pre + ":" + post;
        }
    }
}
