package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A {@link Feature} constrained to the client ({@code @ConstrainedTo(CLIENT)}) does not configure
 * the server: neither one of the application, nor one a feature or a dynamic feature registers.
 */
@MicronautTest
@Property(name = "spec.name", value = "ClientFeatureTest")
class ClientFeatureTest {

    @Inject
    @Client("/api/client-feature")
    HttpClient client;

    @Test
    void clientFeaturesDoNotConfigureTheServer() {
        String marks = client.toBlocking().retrieve(HttpRequest.GET("/"));
        assertEquals(List.of(), CONFIGURED);
        assertEquals("server", marks);
    }

    // the client features that were configured
    static final List<String> CONFIGURED = new CopyOnWriteArrayList<>();

    // introspected: its annotations are known without reflection
    @Introspected
    public static class MarkFilter implements ContainerRequestFilter {
        private final String mark;

        public MarkFilter(String mark) {
            this.mark = mark;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            String marks = requestContext.getHeaderString("X-Marks");
            requestContext.getHeaders().putSingle("X-Marks", marks == null ? mark : marks + "," + mark);
        }
    }

    @Introspected
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class NestedClassClientFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            CONFIGURED.add("nested-class");
            context.register(new MarkFilter("nested-class"));
            return true;
        }
    }

    @Introspected
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class NestedInstanceClientFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            CONFIGURED.add("nested-instance");
            context.register(new MarkFilter("nested-instance"));
            return true;
        }
    }

    @Introspected
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class DynamicClientFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            CONFIGURED.add("dynamic");
            context.register(new MarkFilter("dynamic"));
            return true;
        }
    }

    @Requires(property = "spec.name", value = "ClientFeatureTest")
    @Provider
    @ConstrainedTo(RuntimeType.CLIENT)
    public static class ClientFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            CONFIGURED.add("client");
            context.register(new MarkFilter("client"));
            return true;
        }
    }

    @Requires(property = "spec.name", value = "ClientFeatureTest")
    @Provider
    public static class ServerFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new MarkFilter("server"));
            context.register(NestedClassClientFeature.class);
            context.register(new NestedInstanceClientFeature());
            return true;
        }
    }

    @Requires(property = "spec.name", value = "ClientFeatureTest")
    @Provider
    public static class RegisteringDynamicFeature implements DynamicFeature {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceClass() == ClientFeatureResource.class) {
                context.register(new DynamicClientFeature());
            }
        }
    }

    @Requires(property = "spec.name", value = "ClientFeatureTest")
    @Path("/client-feature")
    public static class ClientFeatureResource {

        @GET
        @Produces("text/plain")
        public String get(@HeaderParam("X-Marks") String marks) {
            return String.valueOf(marks);
        }
    }
}
