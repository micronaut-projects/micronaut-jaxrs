package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request under an application path with a character that is percent-encoded in a request is
 * normalized like any request under the application path.
 */
@MicronautTest
@Property(name = "spec.name", value = "EncodedApplicationPathNormalizationTest")
class EncodedApplicationPathNormalizationTest {

    @Inject
    EmbeddedServer server;

    @Test
    void requestUnderAnEncodedApplicationPathIsNormalized() throws Exception {
        String response = get("/a%20b/norm/%7e");
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertTrue(response.endsWith("tilde /a%20b/norm/~"), response);
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

    @Requires(property = "spec.name", value = "EncodedApplicationPathNormalizationTest")
    @Replaces(RestApplication.class)
    @ApplicationPath("/a b")
    public static class SpaceApplication extends Application {
    }

    @Requires(property = "spec.name", value = "EncodedApplicationPathNormalizationTest")
    @Path("/norm")
    public static class NormResource {
        @GET
        @Path("~")
        @Produces("text/plain")
        public String tilde(@Context UriInfo uriInfo) {
            return "tilde " + uriInfo.getRequestUri().getRawPath();
        }
    }
}
