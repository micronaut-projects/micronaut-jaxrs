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
    void otherPropertiesOfTheConfigurationAreHonoured() throws Exception {
        SeBootstrap.Configuration requested = SeBootstrap.Configuration.builder()
            .port(SeBootstrap.Configuration.FREE_PORT)
            .property("custom.value", "set")
            .build();
        System.setProperty("spec.name", "SeBootstrapTest");
        SeBootstrap.Instance instance;
        try {
            instance = SeBootstrap.start(new SeApplication("the instance"), requested).toCompletableFuture().get();
        } finally {
            System.clearProperty("spec.name");
        }
        try {
            // a property of the application context, and of the configuration of the instance
            assertEquals("set", instance.unwrap(ApplicationContext.class).getProperty("custom.value", String.class).orElse(null));
            assertEquals("set", instance.configuration().property("custom.value"));
            assertTrue(instance.configuration().port() > 0);
        } finally {
            instance.stop().toCompletableFuture().get();
        }
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
    void contextFieldsOfAProviderInstanceAreInjected() throws Exception {
        SeBootstrap.Configuration requested = SeBootstrap.Configuration.builder()
            .port(SeBootstrap.Configuration.FREE_PORT)
            .build();
        System.setProperty("spec.name", "SeBootstrapTest");
        SeBootstrap.Instance instance;
        try {
            instance = SeBootstrap.start(new SeProviderApplication(), requested).toCompletableFuture().get();
        } finally {
            System.clearProperty("spec.name");
        }
        try {
            URI uri = instance.configuration().baseUriBuilder().path("se/resource").build();
            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).header("X-In", "header").build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                assertEquals("header", response.headers().firstValue("X-Out").orElse(null));
            }
        } finally {
            instance.stop().toCompletableFuture().get();
        }
    }

    @Requires(property = "spec.name", value = "SeBootstrapTest")
    @ApplicationPath("se")
    public static final class SeProviderApplication extends Application {
        private final Set<Object> singletons = Set.of(new SeResource("value"), new HeadersFilter());

        @Override
        public Set<Object> getSingletons() {
            return singletons;
        }
    }

    /**
     * A filter instance of the application, which is not a bean.
     */
    public static final class HeadersFilter implements jakarta.ws.rs.container.ContainerResponseFilter {
        @jakarta.ws.rs.core.Context
        jakarta.ws.rs.core.HttpHeaders headers;

        @Override
        public void filter(jakarta.ws.rs.container.ContainerRequestContext requestContext,
                           jakarta.ws.rs.container.ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Out", headers.getHeaderString("X-In"));
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
