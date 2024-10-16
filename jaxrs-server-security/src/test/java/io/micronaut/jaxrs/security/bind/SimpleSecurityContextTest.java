package io.micronaut.jaxrs.security.bind;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.provider.HttpRequestReactiveAuthenticationProvider;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * @author graemerocher
 */
@Property(name = "spec.name", value = "SimpleSecurityContextTest")
@MicronautTest
class SimpleSecurityContextTest {

    @Test
    void testSecurityContext(@Client("/") HttpClient client) {
        MutableHttpRequest<Object> request = HttpRequest.GET("/secured")
            .basicAuth("fred", "ok");
        Map<String, String> result = client.toBlocking().retrieve(request,
            Argument.mapOf(String.class, String.class)
        );

        assertFalse(result.isEmpty());
        assertEquals("fred", result.get("principal"));
        assertEquals("false", result.get("secure"));
        assertEquals("true", result.get("hasRole"));
        assertEquals("fred", result.get("context2.principal"));
        assertEquals("false", result.get("context2.secure"));
        assertEquals("true", result.get("context2.hasRole"));
    }

    @Requires(property = "spec.name", value = "SimpleSecurityContextTest")
    @Singleton
    <B> HttpRequestReactiveAuthenticationProvider<B> authenticationProvider() {
        return new HttpRequestReactiveAuthenticationProvider<>() {

            @Override
            public @NonNull Publisher<AuthenticationResponse> authenticate(
                HttpRequest<B> requestContext,
                @NonNull AuthenticationRequest<String, String> authenticationRequest
            ) {
                return Publishers.just(AuthenticationResponse.success("fred", List.of("admin")));
            }
        };
    }
}

@Requires(property = "spec.name", value = "SimpleSecurityContextTest")
@Path("/secured")
@RolesAllowed("admin")
class SecureResource {

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON) // TODO: implement backward compatibility
    public Map<String, String> test(
        @Context SecurityContext context,
        SecurityContext context2 // test with and without @Context
    ) {
        return Map.of(
            "secure", String.valueOf(context.isSecure()),
            "hasRole", String.valueOf(context.isUserInRole("admin")),
            "principal", context.getUserPrincipal().getName(),
            "context2.secure", String.valueOf(context2.isSecure()),
            "context2.hasRole", String.valueOf(context2.isUserInRole("admin")),
            "context2.principal", context2.getUserPrincipal().getName()
        );
    }
}
