package io.micronaut.jaxrs.client;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.sse.SseEventSource;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing an event source whose connection waits for the response closes the connection: a
 * server that never answers sees the end of the connection.
 */
class JaxRsSseEventSourceCloseTest {

    @Test
    void closeReleasesAConnectionWaitingForTheResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10_000);
            Client client = ClientBuilder.newClient();
            try {
                SseEventSource source = SseEventSource.target(client.target("http://localhost:" + server.getLocalPort() + "/events")).build();
                source.register(event -> { });
                source.open();
                try (Socket connection = server.accept()) {
                    connection.setSoTimeout(10_000);
                    InputStream in = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
                    // the request, never answered
                    for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                        // its headers
                    }
                    assertTrue(source.close(5, TimeUnit.SECONDS));
                    // the end of the connection, before the read timeout of this server
                    assertEquals(-1, reader.read());
                }
            } finally {
                client.close();
            }
        }
    }
}
