package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A method without annotations inherits the name binding of the method it implements (JAX-RS
 * 3.6): the filter bound to it runs.
 */
@MicronautTest
@Property(name = "spec.name", value = "InheritedNameBindingTest")
class InheritedNameBindingTest {

    @Inject
    @Client("/api/inherited-name-binding")
    HttpClient client;

    @Test
    void nameBoundFilterOfAnInterfaceMethod() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/interface"), String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void nameBoundFilterOfASuperclassMethod() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().exchange(HttpRequest.GET("/superclass/secret"), String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void unboundMethodIsNotFiltered() {
        assertEquals("open", client.toBlocking().retrieve(HttpRequest.GET("/interface/open")));
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Secure {
    }

    @Requires(property = "spec.name", value = "InheritedNameBindingTest")
    @Provider
    @Secure
    public static class AuthenticationFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED).entity("blocked").build());
        }
    }

    public interface Api {
        @GET
        @Secure
        String secret();

        @GET
        @Path("open")
        String open();
    }

    @Requires(property = "spec.name", value = "InheritedNameBindingTest")
    @Path("/inherited-name-binding/interface")
    public static class InterfaceResource implements Api {
        @Override
        public String secret() {
            return "secret";
        }

        @Override
        public String open() {
            return "open";
        }
    }

    public abstract static class Base {
        @GET
        @Path("secret")
        @Secure
        public abstract String secret();
    }

    @Requires(property = "spec.name", value = "InheritedNameBindingTest")
    @Path("/inherited-name-binding/superclass")
    public static class SuperclassResource extends Base {
        @Override
        public String secret() {
            return "secret";
        }
    }
}
