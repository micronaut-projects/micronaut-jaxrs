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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code @Path} of a class is not inherited (JAX-RS 3.6): a subclass of a root resource
 * without its own is not a root resource, it is reachable only from a sub-resource locator. The
 * one of an abstract class, which cannot be a root resource itself, is kept for its subclass.
 */
@MicronautTest
@Property(name = "spec.name", value = "InheritedPathTest")
class InheritedPathTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void subclassOfARootResourceIsNotARootResource() {
        assertEquals("base", client.toBlocking().retrieve(HttpRequest.GET("/inherited-path/value")));
    }

    @Test
    void subclassIsReachableFromALocator() {
        assertEquals("checked", client.toBlocking().retrieve(HttpRequest.GET("/inherited-path-locator/checked/value")));
    }

    @Test
    void subclassOfAnAbstractClassIsRoutedWithItsPath() {
        assertEquals("implemented", client.toBlocking().retrieve(HttpRequest.GET("/abstract-path/value")));
    }

    @Requires(property = "spec.name", value = "InheritedPathTest")
    @Path("/inherited-path")
    public static class Base {
        @GET
        @Path("/value")
        @Produces("text/plain")
        public String value() {
            return "base";
        }
    }

    @Requires(property = "spec.name", value = "InheritedPathTest")
    public static class Checked extends Base {
        @Override
        public String value() {
            return "checked";
        }
    }

    @Requires(property = "spec.name", value = "InheritedPathTest")
    @Path("/inherited-path-locator")
    public static class Locator {
        @Path("/checked")
        public Checked checked() {
            return new Checked();
        }
    }

    @Path("/abstract-path")
    public abstract static class AbstractBase {
        @GET
        @Path("/value")
        @Produces("text/plain")
        public String value() {
            return "implemented";
        }
    }

    @Requires(property = "spec.name", value = "InheritedPathTest")
    public static class Implemented extends AbstractBase {
    }
}
