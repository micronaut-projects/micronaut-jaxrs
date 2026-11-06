package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The features of the Java service loader are registered on the clients, unless the property
 * {@code jakarta.ws.rs.loadServices} is {@code false} (JAX-RS 4.1.2).
 */
@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientServiceFeatureTest {

    @Inject
    EmbeddedServer server;

    @Test
    void aFeatureOfTheServiceLoaderIsRegistered(@TempDir Path directory) throws Exception {
        withServiceFeature(directory, () -> {
            try (Client client = ClientBuilder.newClient()) {
                assertTrue(client.getConfiguration().isRegistered(ServiceFeature.class));
                assertEquals("discovered", client.target(server.getURI()).path("client/header/X-Feature").request().get(String.class));
            }
            return null;
        });
    }

    @Test
    void theFeaturesOfTheServiceLoaderAreNotRegisteredWhenDisabled(@TempDir Path directory) throws Exception {
        withServiceFeature(directory, () -> {
            try (Client client = ClientBuilder.newBuilder().property("jakarta.ws.rs.loadServices", false).build()) {
                assertFalse(client.getConfiguration().isRegistered(ServiceFeature.class));
                assertEquals("none", client.target(server.getURI()).path("client/header/X-Feature").request().get(String.class));
            }
            return null;
        });
    }

    private static void withServiceFeature(Path directory, Supplier<Void> test) throws Exception {
        Path services = Files.createDirectories(directory.resolve("META-INF/services"));
        Files.writeString(services.resolve(Feature.class.getName()), ServiceFeature.class.getName());
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, previous)) {
            thread.setContextClassLoader(loader);
            test.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public static final class ServiceFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register((ClientRequestFilter) request -> request.getHeaders().putSingle("X-Feature", "discovered"));
            return true;
        }
    }
}
