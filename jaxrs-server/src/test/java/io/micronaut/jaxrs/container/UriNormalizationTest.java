package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request URI is normalized before it is matched, and the normalized URI is the one of
 * {@code UriInfo} (JAX-RS 3.7.1, RFC 3986 6.2.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "UriNormalizationTest")
class UriNormalizationTest {

    @Inject
    EmbeddedServer server;

    @Test
    void percentEncodedUnreservedCharacterIsDecoded() throws Exception {
        String response = get("/api/norm/%7e?q=%7e");
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertTrue(response.endsWith("tilde /api/norm/~?q=%7e"), response);
    }

    @Test
    void dotSegmentsAreRemoved() throws Exception {
        String response = get("/api/norm/a/../b");
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertTrue(response.endsWith("b /api/norm/b"), response);
        assertEquals(true, get("/api/norm/./c/../b").endsWith("b /api/norm/b"));
    }

    @Test
    void normalizedPathStartingWithTwoSlashesStaysAPath() throws Exception {
        // the normalized path //evil/api/norm/b is not the authority evil and the path /api/norm/b
        String response = get("/x/..//evil/api/norm/b");
        assertTrue(response.startsWith("HTTP/1.1 404"), response);
    }

    @Test
    void pathRewrittenByPreMatchingFilterIsNormalized() throws Exception {
        String response = get("/api/rewrite");
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertTrue(response.endsWith("tilde /api/norm/~?null"), response);
    }

    /**
     * Sent raw: the clients normalize the path themselves.
     */
    private String get(String target) throws Exception {
        try (Socket socket = new Socket(server.getHost(), server.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            StringBuilder response = new StringBuilder();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line).append('\n');
            }
            return response.toString().trim();
        }
    }

    @Requires(property = "spec.name", value = "UriNormalizationTest")
    @Provider
    @PreMatching
    public static class RewriteFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            URI uri = requestContext.getUriInfo().getRequestUri();
            if (uri.getRawPath().endsWith("/rewrite")) {
                requestContext.setRequestUri(URI.create(uri.toString().replace("/rewrite", "/norm/%7e")));
            }
        }
    }

    @Requires(property = "spec.name", value = "UriNormalizationTest")
    @Path("/norm")
    public static class NormResource {
        @GET
        @Path("~")
        @Produces("text/plain")
        public String tilde(@Context UriInfo uriInfo) {
            return "tilde " + uriInfo.getRequestUri().getRawPath() + "?" + uriInfo.getRequestUri().getRawQuery();
        }

        @GET
        @Path("b")
        @Produces("text/plain")
        public String b(@Context UriInfo uriInfo) {
            return "b " + uriInfo.getRequestUri().getRawPath();
        }
    }
}
