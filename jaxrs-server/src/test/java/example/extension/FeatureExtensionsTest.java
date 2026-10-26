package example.extension;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.jaxrs.container.RestApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The extensions of an {@link Application} that lists its classes and singletons: the dynamic
 * features its features register, its feature instances, its properties and the configuration
 * the features see (JAX-RS 2.3, 4.1, {@code Configurable}, {@code Configuration}).
 */
@MicronautTest
@Property(name = "spec.name", value = "FeatureExtensionsTest")
class FeatureExtensionsTest {

    @Inject
    @Client("/api/feature-extensions")
    HttpClient client;

    @Test
    void dynamicFeatureRegisteredByAFeatureConfiguresTheResourceMethods() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/guarded")));
        assertEquals(403, e.code());
    }

    @Test
    void dynamicFeatureSeesTheConfigurationOfTheResourceMethod() {
        client.toBlocking().retrieve(HttpRequest.GET("/configuration"));
        assertEquals(Map.of("local", 42, "registered", true), RegisteredDynamicFeature.seen);
    }

    @Test
    void applicationFeatureInstanceIsConfiguredInjected() {
        assertEquals("instance true", client.toBlocking().retrieve(HttpRequest.GET("/instance")));
    }

    @Test
    void unlistedInterceptorIsNotApplied() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/instance"), String.class);
        assertNull(response.header("X-Unlisted"));
    }

    @Test
    void serviceLoadingIsDisabledByTheApplication() {
        client.toBlocking().retrieve(HttpRequest.GET("/instance"));
        assertFalse(ServiceFeature.configured, "jakarta.ws.rs.loadServices=false");
    }

    @Test
    void configuration() {
        assertEquals("removed=null registered=true instances=true classes=true enabled=true disabled=false",
            client.toBlocking().retrieve(HttpRequest.GET("/configuration")));
    }

    @Requires(property = "spec.name", value = "FeatureExtensionsTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class ExtensionsApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(ExtensionsResource.class, ListedFeature.class, DisabledFeature.class);
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(new InstanceFeature("instance"));
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("removed", "value", "jakarta.ws.rs.loadServices", false, ServiceFeature.PROBE, true);
        }
    }

    /**
     * Registers a dynamic feature, an instance, and removes a property of the application.
     */
    @Requires(property = "spec.name", value = "FeatureExtensionsTest")
    @Provider
    public static class ListedFeature implements Feature {
        static final RegisteredInstance INSTANCE = new RegisteredInstance();

        @Override
        public boolean configure(FeatureContext context) {
            context.register(new RegisteredDynamicFeature());
            context.register(INSTANCE);
            context.property("removed", null);
            return true;
        }
    }

    @Introspected
    public static class RegisteredInstance implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
        }
    }

    @Requires(property = "spec.name", value = "FeatureExtensionsTest")
    @Provider
    public static class DisabledFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            return false;
        }
    }

    @Introspected
    public static class RegisteredDynamicFeature implements DynamicFeature {
        static volatile Map<String, Object> seen;

        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            String name = resourceInfo.getResourceMethod().getName();
            if (name.equals("guarded")) {
                context.register(new RejectingFilter());
            } else if (name.equals("configuration")) {
                context.property("local", 42);
                RejectingFilter filter = new RejectingFilter();
                context.register(filter, 1);
                Configuration configuration = context.getConfiguration();
                seen = Map.of("local", configuration.getProperty("local"), "registered", configuration.isRegistered(filter));
            }
        }
    }

    @Introspected
    public static class RejectingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (!"/api/feature-extensions/configuration".equals(requestContext.getUriInfo().getRequestUri().getPath())) {
                requestContext.abortWith(Response.status(403).build());
            }
        }
    }

    /**
     * The bean of the class: the instance of the application replaces it.
     */
    @Requires(property = "spec.name", value = "FeatureExtensionsTest")
    @Provider
    @Introspected
    public static class InstanceFeature implements Feature {
        private final String value;
        @Context
        private Providers providers;

        public InstanceFeature() {
            this("bean");
        }

        public InstanceFeature(String value) {
            this.value = value;
        }

        public Providers getProviders() {
            return providers;
        }

        public void setProviders(Providers providers) {
            this.providers = providers;
        }

        @Override
        public boolean configure(FeatureContext context) {
            context.property("instance.feature", value + " " + (providers != null));
            return true;
        }
    }

    /**
     * Not listed by the application: not applied.
     */
    @Requires(property = "spec.name", value = "FeatureExtensionsTest")
    @Provider
    public static class UnlistedInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws java.io.IOException {
            context.getHeaders().putSingle("X-Unlisted", "applied");
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "FeatureExtensionsTest")
    @Path("/feature-extensions")
    public static class ExtensionsResource {

        @Context
        Configuration configuration;

        @GET
        @Path("guarded")
        @Produces("text/plain")
        public String guarded() {
            return "guarded";
        }

        @GET
        @Path("instance")
        @Produces("text/plain")
        public String instance() {
            return String.valueOf(configuration.getProperty("instance.feature"));
        }

        @GET
        @Path("configuration")
        @Produces("text/plain")
        public String configuration() {
            return "removed=" + configuration.getProperty("removed")
                + " registered=" + configuration.isRegistered(ListedFeature.INSTANCE)
                + " instances=" + configuration.getInstances().contains(ListedFeature.INSTANCE)
                + " classes=" + configuration.getClasses().contains(ListedFeature.class)
                + " enabled=" + configuration.isEnabled(ListedFeature.class)
                + " disabled=" + configuration.isEnabled(DisabledFeature.class);
        }
    }
}
