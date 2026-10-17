package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
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
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Filters and interceptors without {@code @Priority} have the priority {@code Priorities.USER}
 * (JAX-RS 6.6).
 */
@MicronautTest
@Property(name = "spec.name", value = "DefaultPriorityTest")
class DefaultPriorityTest {

    @Inject
    @Client("/api/default-priority")
    HttpClient client;

    @Test
    void defaultPriorityIsUser() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/"), String.class);
        // request filters by ascending priority
        assertEquals("4000,user,6000,i4000,iuser,i6000", response.body());
        // response filters by descending priority
        assertEquals("6000,user,4000", response.header("X-Order"));
    }

    private static void append(ContainerRequestContext context, String value) {
        String order = context.getHeaderString("X-Order");
        context.getHeaders().putSingle("X-Order", order == null ? value : order + "," + value);
    }

    private static void append(ContainerResponseContext context, String value) {
        String order = context.getHeaderString("X-Order");
        context.getHeaders().putSingle("X-Order", order == null ? value : order + "," + value);
    }

    private static void append(WriterInterceptorContext context, String value) {
        context.setEntity(context.getEntity() + "," + value);
    }

    @Requires(property = "spec.name", value = "DefaultPriorityTest")
    @Provider
    @Priority(6000)
    public static class LateFilter implements ContainerRequestFilter, ContainerResponseFilter, WriterInterceptor {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            append(requestContext, "6000");
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            append(responseContext, "6000");
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            append(context, "i6000");
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "DefaultPriorityTest")
    @Provider
    public static class UserFilter implements ContainerRequestFilter, ContainerResponseFilter, WriterInterceptor {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            append(requestContext, "user");
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            append(responseContext, "user");
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            append(context, "iuser");
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "DefaultPriorityTest")
    @Provider
    @Priority(4000)
    public static class EarlyFilter implements ContainerRequestFilter, ContainerResponseFilter, WriterInterceptor {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            append(requestContext, "4000");
        }

        @Override
        public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
            append(responseContext, "4000");
        }

        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            append(context, "i4000");
            context.proceed();
        }
    }

    @Requires(property = "spec.name", value = "DefaultPriorityTest")
    @Path("/default-priority")
    public static class DefaultPriorityResource {

        @GET
        @Produces("text/plain")
        public String get(@HeaderParam("X-Order") String order) {
            return order;
        }
    }
}
