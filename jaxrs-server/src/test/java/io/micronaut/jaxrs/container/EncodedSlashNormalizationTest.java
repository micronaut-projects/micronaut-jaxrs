package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An encoded slash after the application path is not a segment separator: the request is not
 * under the application path, and its URI is not normalized.
 */
@MicronautTest
@Property(name = "spec.name", value = "EncodedSlashNormalizationTest")
class EncodedSlashNormalizationTest {

    @Inject
    EmbeddedServer server;

    @Test
    void encodedSlashAfterTheApplicationPathIsNotUnderIt() throws Exception {
        String response = get("/api%2foutside/%7e");
        assertTrue(response.startsWith("HTTP/1.1 200"), response);
        assertTrue(response.endsWith("outside /api%2foutside/%7e"), response);
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

    @Requires(property = "spec.name", value = "EncodedSlashNormalizationTest")
    @ServerFilter("/**")
    public static class OutsideFilter {
        @RequestFilter
        public @Nullable HttpResponse<String> filter(HttpRequest<?> request) {
            String rawPath = request.getUri().getRawPath();
            return rawPath.toLowerCase().startsWith("/api%2f") ? HttpResponse.ok("outside " + rawPath) : null;
        }
    }
}
