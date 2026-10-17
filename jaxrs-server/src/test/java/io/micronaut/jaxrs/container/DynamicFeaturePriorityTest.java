package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The filters and interceptors a dynamic feature registers run in the order of the priorities they
 * are registered with, else of their {@code @Priority}, not in the order of registration.
 */
@MicronautTest
@Property(name = "spec.name", value = "DynamicFeaturePriorityTest")
class DynamicFeaturePriorityTest {

    @Inject
    @Client("/api/dynamic-priority")
    HttpClient client;

    @Test
    void priorities() {
        // request filters a (100) then b (200); writer interceptors p (@Priority 50), x (100), y (200)
        assertEquals("a,b-p-x-y", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "DynamicFeaturePriorityTest")
    @Path("/dynamic-priority")
    public static class PriorityResource {
        @GET
        @Produces("text/plain")
        public String get(@HeaderParam("X-Order") List<String> order) {
            return String.join(",", order);
        }
    }

    @Requires(property = "spec.name", value = "DynamicFeaturePriorityTest")
    @Provider
    public static class PriorityFeature implements DynamicFeature {
        @Override
        public void configure(ResourceInfo resourceInfo, FeatureContext context) {
            if (resourceInfo.getResourceClass() == PriorityResource.class) {
                context.register(new OrderFilter("b"), 200);
                context.register(new OrderFilter("a"), 100);
                context.register(new OrderInterceptor("y"), 200);
                context.register(new OrderInterceptor("x"), 100);
                context.register(new PriorityInterceptor());
            }
        }
    }

    static final class OrderFilter implements ContainerRequestFilter {
        private final String name;

        OrderFilter(String name) {
            this.name = name;
        }

        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.getHeaders().add("X-Order", name);
        }
    }

    static class OrderInterceptor implements WriterInterceptor {
        private final String name;

        OrderInterceptor(String name) {
            this.name = name;
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.setEntity(context.getEntity() + "-" + name);
            context.proceed();
        }
    }

    @Priority(50)
    static final class PriorityInterceptor extends OrderInterceptor {
        PriorityInterceptor() {
            super("p");
        }
    }
}
