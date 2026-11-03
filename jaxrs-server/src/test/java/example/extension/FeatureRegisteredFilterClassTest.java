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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A filter class a feature registers that is neither a bean nor annotated with {@code @Provider}
 * is a provider of the application, created from the introspection of its class, also without
 * reflection.
 */
@MicronautTest
@Property(name = "spec.name", value = "FeatureRegisteredFilterClassTest")
class FeatureRegisteredFilterClassTest {

    @Inject
    @Client("/api/feature-filter")
    HttpClient client;

    @Test
    void filterRegisteredByFeatureFilters() {
        assertEquals("filtered", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "FeatureRegisteredFilterClassTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class FeatureApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(FilterResource.class, FilterFeature.class);
        }
    }

    /**
     * Not a bean, not annotated with {@code @Provider}.
     */
    public static class FilterFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(AbortingFilter.class);
            return true;
        }
    }

    /**
     * Not a bean, not annotated with {@code @Provider}.
     */
    public static class AbortingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.abortWith(Response.ok("filtered").build());
        }
    }

    @Requires(property = "spec.name", value = "FeatureRegisteredFilterClassTest")
    @Path("/feature-filter")
    public static class FilterResource {
        @GET
        @Produces("text/plain")
        public String get() {
            return "value";
        }
    }
}
