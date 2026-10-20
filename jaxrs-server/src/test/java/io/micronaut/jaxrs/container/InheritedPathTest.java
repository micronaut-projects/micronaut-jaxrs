package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The {@code @Path} of a superclass is not inherited, the annotations of a method are (JAX-RS
 * 3.6).
 */
@MicronautTest
@Property(name = "spec.name", value = "InheritedPathTest")
class InheritedPathTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void subclassWithoutPathIsNotARootResource() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/inherited-base"), String.class));
        assertEquals(404, e.getStatus().getCode());
    }

    @Test
    void subclassWithPathInheritsTheMethodAnnotations() {
        assertEquals("sub", client.toBlocking().retrieve(HttpRequest.GET("/inherited-sub"), String.class));
    }

    /**
     * A resource whose subclasses are not root resources without their own {@code @Path}.
     */
    @Path("/inherited-base")
    public abstract static class BaseResource {
        @GET
        @Produces("text/plain")
        public abstract String get();
    }

    @Requires(property = "spec.name", value = "InheritedPathTest")
    public static class UnannotatedResource extends BaseResource {
        @Override
        public String get() {
            return "unannotated";
        }
    }

    @Requires(property = "spec.name", value = "InheritedPathTest")
    @Path("/inherited-sub")
    public static class AnnotatedResource extends BaseResource {
        @Override
        public String get() {
            return "sub";
        }
    }
}
