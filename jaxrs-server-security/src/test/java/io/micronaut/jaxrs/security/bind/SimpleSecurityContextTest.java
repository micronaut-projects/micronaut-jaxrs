package io.micronaut.jaxrs.security.bind;

import io.micronaut.context.annotation.Bean;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.security.authentication.AuthenticationProvider;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.Map;
import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
 
/**
 *
 * @author graemerocher
 */
@MicronautTest
public class SimpleSecurityContextTest implements TestPropertyProvider {

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
    }


    @Override
    public Map<String, String> getProperties() {
        return CollectionUtils.mapOf(
            // "micronaut.security.authentication", "bearer",
            "micronaut.security.basic-auth.enabled", "true"
        );
    }      


    @Singleton
    AuthenticationProvider authenticationProvider() {
        return (HttpRequest<?> httpRequest, AuthenticationRequest<?, ?> authenticationRequest) -> {
            AuthenticationResponse response = AuthenticationResponse
                .success("fred", Collections.singleton("admin"));
            return Publishers.just(response);
        };
    }


}

@Path("/secured")
@RolesAllowed("admin")
class SecureResource {
    @GET
    @Path("/")
    Map<String, String> test(
            @Context SecurityContext context,
            SecurityContext context2 // test with and without @Context
            ) {
        return CollectionUtils.mapOf(
                "secure", context.isSecure(),
                "hasRole", context.isUserInRole("admin"),
                "principal", context.getUserPrincipal().getName()
        );
    }
}