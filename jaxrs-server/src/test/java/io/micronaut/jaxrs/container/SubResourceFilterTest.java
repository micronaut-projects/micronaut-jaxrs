package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The routes of sub-resources that are not beans have the annotations of their own methods: the
 * name bound filters of the methods, and the response filters, run for them.
 */
@MicronautTest
@Property(name = "spec.name", value = "SubResourceFilterTest")
class SubResourceFilterTest {

    @Inject
    @Client("/api/sub-resource-filter")
    HttpClient client;

    @Test
    void nameBoundFilterOfAFollowedSubResourceMethod() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/followed/bound"), String.class);
        assertEquals("bound", response.body());
        assertEquals("true", response.header("X-Bound"));
        assertEquals("true", response.header("X-Filtered"));
    }

    @Test
    void nameBoundFilterIsNotAppliedToAnUnboundMethod() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/followed/unbound"), String.class);
        assertEquals("unbound", response.body());
        assertNull(response.header("X-Bound"));
    }

    @Test
    void filtersOfASubResourceLocatedAtRuntime() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/located/bound"), String.class);
        assertEquals("bound", response.body());
        assertEquals("true", response.header("X-Bound"));
        assertEquals("true", response.header("X-Filtered"));
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Bound {
    }

    @Requires(property = "spec.name", value = "SubResourceFilterTest")
    @Provider
    @Bound
    public static class BoundFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().add("X-Bound", "true");
        }
    }

    @Requires(property = "spec.name", value = "SubResourceFilterTest")
    @Provider
    public static class GlobalFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            responseContext.getHeaders().add("X-Filtered", "true");
        }
    }

    @Requires(property = "spec.name", value = "SubResourceFilterTest")
    @Path("/sub-resource-filter")
    public static class Root {
        @Path("followed")
        public Sub followed() {
            return new Sub();
        }

        @Path("located")
        public Object located() {
            return new Sub();
        }
    }

    // not a bean of the context
    @Requires(property = "spec.name", value = "SubResourceFilterTest.Sub")
    public static final class Sub {
        @GET
        @Path("bound")
        @Bound
        public String bound() {
            return "bound";
        }

        @GET
        @Path("unbound")
        public String unbound() {
            return "unbound";
        }
    }
}
