package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
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
