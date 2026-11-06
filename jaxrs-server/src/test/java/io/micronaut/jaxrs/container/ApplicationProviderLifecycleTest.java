package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The provider classes and instances of an application (JAX-RS 2.3, 4.1): created with their
 * {@code @Context} constructor parameters, injected with their {@code @Context} members, and
 * singletons by default.
 */
@MicronautTest
@Property(name = "spec.name", value = "ApplicationProviderLifecycleTest")
class ApplicationProviderLifecycleTest {

    @Inject
    @Client("/api/provider-lifecycle")
    HttpClient client;

    @Test
    void componentClassesAreCreatedWithTheirContextConstructorParameters() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/"), String.class);
        assertEquals("ok", response.body());
        assertEquals("value", response.header("X-Constructor-Feature"));
        assertEquals("value", response.header("X-Constructor-Filter"));
    }

    @Requires(property = "spec.name", value = "ApplicationProviderLifecycleTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class ProviderApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Resource.class, ConstructorFeature.class, ConstructorFilter.class);
        }

        @Override
        public Map<String, Object> getProperties() {
            return Map.of("name", "value");
        }
    }

    @Requires(property = "spec.name", value = "ApplicationProviderLifecycleTest")
    @Path("/provider-lifecycle")
    public static class Resource {
        @GET
        public String get() {
            return "ok";
        }
    }

    /**
     * A feature that is not a bean, with only a {@code @Context} constructor.
     */
    @Introspected
    public static class ConstructorFeature implements Feature {
        private final Configuration configuration;

        public ConstructorFeature(@Context Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public boolean configure(FeatureContext context) {
            Object value = configuration.getProperty("name");
            context.register(new HeaderFilter("X-Constructor-Feature", value));
            return true;
        }
    }

    /**
     * A filter instance a feature registers.
     */
    public static final class HeaderFilter implements ContainerResponseFilter {
        private final String name;
        private final Object value;

        HeaderFilter(String name, Object value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle(name, value);
        }
    }

    /**
     * A filter that is not a bean, with only a {@code @Context} constructor.
     */
    @Introspected
    public static class ConstructorFilter implements ContainerResponseFilter {
        private final Configuration configuration;

        public ConstructorFilter(@Context Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().putSingle("X-Constructor-Filter", configuration.getProperty("name"));
        }
    }
}
