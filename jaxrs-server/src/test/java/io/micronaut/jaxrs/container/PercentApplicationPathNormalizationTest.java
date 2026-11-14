package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application path whose segment has the text {@code %2F} is not the path of a request with
 * an encoded slash: the request is not under the application path, and its URI is not normalized.
 */
@MicronautTest
@Property(name = "spec.name", value = "PercentApplicationPathNormalizationTest")
class PercentApplicationPathNormalizationTest {

    @Inject
    EmbeddedServer server;

    @Test
    void encodedSlashIsNotThePercentTextOfTheApplicationPath() throws Exception {
        String response = get("/api%2Foutside/%7e");
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertTrue(response.endsWith("outside /api%2Foutside/%7e"), response);
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

    @Requires(property = "spec.name", value = "PercentApplicationPathNormalizationTest")
    @Replaces(RestApplication.class)
    @ApplicationPath("/api%252Foutside")
    public static class PercentApplication extends Application {
    }

    @Requires(property = "spec.name", value = "PercentApplicationPathNormalizationTest")
    @ServerFilter("/**")
    public static class OutsideFilter {
        @RequestFilter
        public @Nullable HttpResponse<String> filter(HttpRequest<?> request) {
            String rawPath = request.getUri().getRawPath();
            return rawPath.toLowerCase().startsWith("/api%2foutside") ? HttpResponse.ok("outside " + rawPath) : null;
        }
    }
}
