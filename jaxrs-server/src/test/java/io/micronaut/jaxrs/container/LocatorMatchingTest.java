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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The matching of sub-resource locators in stages (JAX-RS 3.7.2).
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatorMatchingTest")
class LocatorMatchingTest {

    @Inject
    @Client("/api")
    HttpClient client;

    @Test
    void locatorParameterIsTheValueOfItsOwnTemplate() {
        // PathParam: the latest use within the scope of the locator
        assertEquals("two/three", client.toBlocking().retrieve(HttpRequest.GET("/repeat/one/sub/two/three")));
    }

    @Test
    void routesOfTheRuntimeClassOfTheTarget() {
        // JAX-RS 3.4.1: the class of the returned object, not the declared type
        assertEquals("child", client.toBlocking().retrieve(HttpRequest.GET("/poly/sub/child")));
        assertEquals("base", client.toBlocking().retrieve(HttpRequest.GET("/poly/sub/base")));
    }

    @Test
    void matchingCommitsToTheMostSpecificLocator() {
        // JAX-RS 3.7.2, step 2: locator a is selected, its target has no x/long
        assertEquals("a", client.toBlocking().retrieve(HttpRequest.GET("/branch/a/x")));
        assertEquals("id", client.toBlocking().retrieve(HttpRequest.GET("/branch/b/x/long")));
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/branch/a/x/long")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/repeat/{id}")
    public static class RepeatResource {
        @Path("sub/{id}")
        public RepeatLeaf sub(@PathParam("id") String id) {
            return new RepeatLeaf(id);
        }
    }

    public static class RepeatLeaf {
        private final String located;

        public RepeatLeaf(String located) {
            this.located = located;
        }

        @GET
        @Path("{id}")
        @Produces("text/plain")
        public String get(@PathParam("id") String id) {
            return located + "/" + id;
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/poly")
    public static class PolyResource {
        @Path("sub")
        public PolyBase sub() {
            return new PolyChild();
        }
    }

    public static class PolyBase {
        @GET
        @Path("base")
        @Produces("text/plain")
        public String base() {
            return "base";
        }
    }

    public static class PolyChild extends PolyBase {
        @GET
        @Path("child")
        @Produces("text/plain")
        public String child() {
            return "child";
        }
    }

    @Requires(property = "spec.name", value = "LocatorMatchingTest")
    @Path("/branch")
    public static class BranchResource {
        @Path("a")
        public BranchA a() {
            return new BranchA();
        }

        @Path("{id}")
        public BranchId id() {
            return new BranchId();
        }
    }

    public static class BranchA {
        @GET
        @Path("x")
        @Produces("text/plain")
        public String x() {
            return "a";
        }
    }

    public static class BranchId {
        @GET
        @Path("x/long")
        @Produces("text/plain")
        public String x() {
            return "id";
        }
    }
}
