package example.extension;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.jaxrs.container.RestApplication;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The contracts of the components the features and the application register ({@code Configurable},
 * {@code Configuration}, JAX-RS 2.3.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "ExtensionContractsTest")
class ExtensionContractsTest {

    @Inject
    @Client("/api/extension-contracts")
    HttpClient client;

    @Test
    void featureThatIsAFilterFilters() {
        assertEquals("filtering-feature", client.toBlocking().retrieve(HttpRequest.GET("/header/X-Filtering-Feature")));
    }

    @Test
    void featureRegisteredOnlyAsAFilterFiltersWithoutBeingConfigured() {
        assertEquals("filter-only false", client.toBlocking().retrieve(HttpRequest.GET("/filter-only")));
    }

    @Test
    void anotherInstanceOfAClassOfTheApplicationIsRejected() {
        assertEquals("original", client.toBlocking().retrieve(HttpRequest.GET("/header/X-Singleton")));
    }

    @Test
    void contractsOfAnApplicationClass() {
        assertEquals("true {interface jakarta.ws.rs.core.Feature=5000}", client.toBlocking().retrieve(HttpRequest.GET("/contracts")));
    }

    @Test
    void contextOfAnApplicationInstanceIsInjected() {
        // a class the annotation processors never saw as a bean: injected with reflection
        assumeTrue(JaxRsReflection.get().isReflective());
        assertEquals("true", client.toBlocking().exchange(HttpRequest.GET("/header/X-Singleton")).header("X-Providers"));
    }

    @Requires(property = "spec.name", value = "ExtensionContractsTest")
    @Replaces(RestApplication.class)
    @Singleton
    @ApplicationPath("/api")
    public static class ContractsApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(ContractsResource.class, RegisteringFeature.class);
        }

        @Override
        public Set<Object> getSingletons() {
            return Set.of(new SingletonFilter("original"), new PlainInterceptor());
        }
    }

    @Introspected
    public static class RegisteringFeature implements Feature {
        @Override
        public boolean configure(FeatureContext context) {
            context.register(new FilteringFeature());
            context.register(new FilterOnlyFeature(), ContainerRequestFilter.class);
            // the application has an instance of the class
            context.register(new SingletonFilter("replacement"));
            return true;
        }
    }

    @Introspected
    public static class FilteringFeature implements Feature, ContainerRequestFilter {
        @Override
        public boolean configure(FeatureContext context) {
            return true;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Filtering-Feature", "filtering-feature");
        }
    }

    @Introspected
    public static class FilterOnlyFeature implements Feature, ContainerRequestFilter {
        static volatile boolean configured;

        @Override
        public boolean configure(FeatureContext context) {
            configured = true;
            return true;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Filter-Only", "filter-only");
        }
    }

    @Introspected
    public static class SingletonFilter implements ContainerRequestFilter {
        private final String value;

        public SingletonFilter(String value) {
            this.value = value;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().putSingle("X-Singleton", value);
        }
    }

    /**
     * Not a bean nor introspected.
     */
    public static class PlainInterceptor implements WriterInterceptor {
        @Context
        Providers providers;

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.getHeaders().putSingle("X-Providers", String.valueOf(providers != null));
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "ExtensionContractsTest")
    @Path("/extension-contracts")
    public static class ContractsResource {

        @Context
        Configuration configuration;

        @Context
        jakarta.ws.rs.core.HttpHeaders headers;

        @GET
        @Path("header/{name}")
        @Produces("text/plain")
        public String header(@jakarta.ws.rs.PathParam("name") String name) {
            return String.valueOf(headers.getHeaderString(name));
        }

        @GET
        @Path("filter-only")
        @Produces("text/plain")
        public String filterOnly(@HeaderParam("X-Filter-Only") String header) {
            return header + " " + FilterOnlyFeature.configured;
        }

        @GET
        @Path("contracts")
        @Produces("text/plain")
        public String contracts() {
            return configuration.isRegistered(RegisteringFeature.class) + " " + configuration.getContracts(RegisteringFeature.class);
        }
    }
}
