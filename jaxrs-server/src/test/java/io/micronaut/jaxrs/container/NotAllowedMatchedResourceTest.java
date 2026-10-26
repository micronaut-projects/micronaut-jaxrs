package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code Allow} header of a {@code 405} lists the methods of the matched resource only, not
 * the ones of a less specific root resource class that also matches the path (JAX-RS 3.7.2), even
 * when the request accepts no type the resource produces.
 */
@MicronautTest
@Property(name = "spec.name", value = "NotAllowedMatchedResourceTest")
class NotAllowedMatchedResourceTest {

    @Inject
    EmbeddedServer server;

    private final HttpClient client = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "text/plain", "text/plain;q=0"})
    void allowListsTheMethodsOfTheMatchedResource(String accept) throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(server.getURL() + "/api/allow/lit"))
                .header("Accept", accept)
                .header("Content-Type", "text/plain")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("b"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(405, response.statusCode());
        Set<String> allowed = response.headers().allValues("Allow").stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .map(String::trim)
            // OPTIONS is allowed for any path
            .filter(method -> !method.equals("OPTIONS"))
            .collect(Collectors.toSet());
        assertEquals(Set.of("GET", "HEAD"), allowed);
    }

    @Requires(property = "spec.name", value = "NotAllowedMatchedResourceTest")
    @Path("/allow/{a}")
    public static class TemplateResource {
        @GET
        @Produces("text/plain")
        public String get(@PathParam("a") String a) {
            return "template:" + a;
        }

        @DELETE
        public void delete() {
        }
    }

    @Requires(property = "spec.name", value = "NotAllowedMatchedResourceTest")
    @Path("/allow/lit")
    public static class LiteralResource {
        @GET
        @Produces("text/plain")
        public String get() {
            return "literal";
        }
    }
}
