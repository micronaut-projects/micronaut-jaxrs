package example.extension;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.container.RestApplication;
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
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The providers and features an application that lists its classes excludes (JAX-RS 2.3) are
 * not created: the server starts although they cannot be.
 */
@MicronautTest
@Property(name = "spec.name", value = "ExcludedProviderTest")
class ExcludedProviderTest {

    @Inject
    @Client("/api/excluded-provider")
    HttpClient client;

    @Test
    void excludedProvidersAreNotCreated() {
        assertEquals("ok", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "ExcludedProviderTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class ListingApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(ListedResource.class);
        }
    }

    @Requires(property = "spec.name", value = "ExcludedProviderTest")
    @Path("/excluded-provider")
    public static class ListedResource {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Requires(property = "spec.name", value = "ExcludedProviderTest")
    @Provider
    public static class ExcludedFilter implements ContainerResponseFilter {
        public ExcludedFilter() {
            throw new IllegalStateException("excluded");
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        }
    }

    @Requires(property = "spec.name", value = "ExcludedProviderTest")
    @Provider
    public static class ExcludedFeature implements Feature {
        public ExcludedFeature() {
            throw new IllegalStateException("excluded");
        }

        @Override
        public boolean configure(FeatureContext context) {
            return false;
        }
    }
}
