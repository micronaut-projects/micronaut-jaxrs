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
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The security context a request filter sets is the one the resource sees.
 */
@MicronautTest
@Property(name = "spec.name", value = "FilterSecurityContextTest")
class FilterSecurityContextTest {

    @Inject
    @Client("/api/filter-security")
    HttpClient client;

    @Test
    void parameterSeesTheSecurityContextOfTheFilter() {
        assertEquals("alice true", client.toBlocking().retrieve(HttpRequest.GET("/parameter").header("X-User", "alice")));
    }

    @Test
    void fieldSeesTheSecurityContextOfTheFilter() {
        assertEquals("bob", client.toBlocking().retrieve(HttpRequest.GET("/field").header("X-User", "bob")));
    }

    @Requires(property = "spec.name", value = "FilterSecurityContextTest")
    @Provider
    public static class UserFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext context) {
            String user = context.getHeaderString("X-User");
            if (user == null) {
                return;
            }
            context.setSecurityContext(new SecurityContext() {
                @Override
                public Principal getUserPrincipal() {
                    return () -> user;
                }

                @Override
                public boolean isUserInRole(String role) {
                    return "admin".equals(role);
                }

                @Override
                public boolean isSecure() {
                    return false;
                }

                @Override
                public String getAuthenticationScheme() {
                    return "test";
                }
            });
        }
    }

    @Requires(property = "spec.name", value = "FilterSecurityContextTest")
    @Path("/filter-security")
    public static class Resource {

        @Context
        SecurityContext field;

        @GET
        @Path("parameter")
        public String parameter(@Context SecurityContext securityContext) {
            return securityContext.getUserPrincipal().getName() + " " + securityContext.isUserInRole("admin");
        }

        @GET
        @Path("field")
        public String field() {
            return field.getUserPrincipal().getName();
        }
    }
}
