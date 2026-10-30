package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request filter sees the name of a custom HTTP method (ContainerRequestContext#getMethod).
 */
@MicronautTest
@Property(name = "spec.name", value = "CustomMethodFilterTest")
class CustomMethodFilterTest {

    @Inject
    @Client("/api/custom-method-filter")
    HttpClient client;

    @Test
    @Disabled("Needs Micronaut core: the mutable view of a Netty server request (NettyHttpRequest.NettyMutableHttpRequest) does not delegate getMethodName")
    void theFilterSeesTheNameOfACustomMethod() {
        assertEquals("PURGE", client.toBlocking().retrieve(HttpRequest.create(HttpMethod.CUSTOM, "/", "PURGE"), String.class));
    }

    @Requires(property = "spec.name", value = "CustomMethodFilterTest")
    @Provider
    @PreMatching
    public static class MethodFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            if (requestContext.getUriInfo().getPath().contains("custom-method-filter")) {
                requestContext.abortWith(Response.ok(requestContext.getMethod(), MediaType.TEXT_PLAIN_TYPE).build());
            }
        }
    }

    @Requires(property = "spec.name", value = "CustomMethodFilterTest")
    @Path("/custom-method-filter")
    public static class MethodResource {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "GET";
        }
    }
}
