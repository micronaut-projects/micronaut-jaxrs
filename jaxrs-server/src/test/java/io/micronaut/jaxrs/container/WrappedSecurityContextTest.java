package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A request filter that wraps the security context of the request and sets the wrapper.
 */
@MicronautTest
@Property(name = "spec.name", value = "WrappedSecurityContextTest")
class WrappedSecurityContextTest {

    @Inject
    @Client("/api/wrapped-security")
    HttpClient client;

    @Test
    void wrapsTheSecurityContext() {
        assertEquals("true false", client.toBlocking().retrieve(HttpRequest.GET("/")));
    }

    @Requires(property = "spec.name", value = "WrappedSecurityContextTest")
    @Provider
    public static class WrappingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            SecurityContext delegate = requestContext.getSecurityContext();
            requestContext.setSecurityContext(new SecurityContext() {
                @Override
                public Principal getUserPrincipal() {
                    return delegate.getUserPrincipal();
                }

                @Override
                public boolean isUserInRole(String role) {
                    return "wrapped".equals(role) || delegate.isUserInRole(role);
                }

                @Override
                public boolean isSecure() {
                    return delegate.isSecure();
                }

                @Override
                public String getAuthenticationScheme() {
                    return delegate.getAuthenticationScheme();
                }
            });
        }
    }

    @Requires(property = "spec.name", value = "WrappedSecurityContextTest")
    @Path("/wrapped-security")
    public static class WrappedSecurityResource {

        @GET
        @Produces("text/plain")
        public String get(@Context SecurityContext securityContext) {
            return securityContext.isUserInRole("wrapped") + " " + securityContext.isUserInRole("admin");
        }
    }
}
