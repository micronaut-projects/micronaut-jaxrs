package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java SE bootstrap (JAX-RS 2.3.1): the instances of the application are its resources.
 */
class SeBootstrapTest {

    private static String get(SeBootstrap.Configuration configuration) throws Exception {
        URI uri = configuration.baseUriBuilder().path("se/resource").build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    @Test
    void applicationIsServedAtTheRootPath() throws Exception {
        SeBootstrap.Configuration requested = SeBootstrap.Configuration.builder()
            .port(SeBootstrap.Configuration.FREE_PORT)
            .rootPath("/root")
            .build();
        // the routes of the resource require it, like the resource
        System.setProperty("spec.name", "SeBootstrapTest");
        SeBootstrap.Instance instance;
        try {
            instance = SeBootstrap.start(new SeApplication("the instance"), requested).toCompletableFuture().get();
        } finally {
            System.clearProperty("spec.name");
        }
        try {
            SeBootstrap.Configuration actual = instance.configuration();
            assertEquals("HTTP", actual.protocol());
            assertEquals("localhost", actual.host());
            assertTrue(actual.port() > 0);
            assertEquals("/root", actual.rootPath());
            assertEquals("the instance", get(actual));
            assertTrue(instance.unwrap(ApplicationContext.class).isRunning());
        } finally {
            instance.stop().toCompletableFuture().get();
        }
        assertTrue(!instance.unwrap(ApplicationContext.class).isRunning());
    }

    @Test
    void requestedApplicationClassIsServedOverAnotherApplicationBean() throws Exception {
        SeBootstrap.Configuration requested = SeBootstrap.Configuration.builder()
            .port(SeBootstrap.Configuration.FREE_PORT)
            .build();
        System.setProperty("spec.name", "SeBootstrapTest.class");
        SeBootstrap.Instance instance;
        try {
            instance = SeBootstrap.start(RequestedApplication.class, requested).toCompletableFuture().get();
        } finally {
            System.clearProperty("spec.name");
        }
        try {
            assertEquals(RequestedApplication.class,
                instance.unwrap(ApplicationContext.class).getBean(Application.class).getClass());
            URI uri = instance.configuration().baseUriBuilder().path("resource").build();
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("class", response.body());
            }
        } finally {
            instance.stop().toCompletableFuture().get();
        }
    }

    @Requires(property = "spec.name", value = "SeBootstrapTest.class")
    // ordered before the other applications
    @io.micronaut.core.annotation.Order(io.micronaut.core.order.Ordered.HIGHEST_PRECEDENCE)
    @ApplicationPath("other")
    public static class OtherApplication extends Application {
    }

    // not a bean: an Application without @ApplicationPath, introspected to be created without reflection
    @io.micronaut.core.annotation.Introspected
    public static class RequestedApplication extends Application {
    }

    @Requires(property = "spec.name", value = "SeBootstrapTest.class")
    @Path("resource")
    public static class ClassResource {
        @GET
        public String get() {
            return "class";
        }
    }

    @Test
    void configurationHasDefaults() {
        SeBootstrap.Configuration configuration = SeBootstrap.Configuration.builder().build();
        assertEquals("HTTP", configuration.protocol());
        assertEquals("localhost", configuration.host());
        assertEquals(SeBootstrap.Configuration.DEFAULT_PORT, configuration.port());
        assertEquals("/", configuration.rootPath());
        assertEquals(SeBootstrap.Configuration.SSLClientAuthentication.NONE, configuration.sslClientAuthentication());
    }

    @Requires(property = "spec.name", value = "SeBootstrapTest")
    @ApplicationPath("se")
    public static final class SeApplication extends Application {
        private final SeResource resource;

        SeApplication(String value) {
            this.resource = new SeResource(value);
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(resource);
        }
    }

    @Requires(property = "spec.name", value = "SeBootstrapTest")
    @Path("resource")
    public static final class SeResource {
        private final String value;

        SeResource(String value) {
            this.value = value;
        }

        @GET
        public String get() {
            return value;
        }
    }
}
