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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The root resource class of a sub-resource locator is mounted under the context path once: the
 * mounted template of one class can be the template of another class, which is mounted too.
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatorMountedRootTest")
@Property(name = "micronaut.server.context-path", value = "/api")
class LocatorMountedRootTest {

    @Inject
    @Client("/api/api")
    HttpClient client;

    @Test
    void locatorOfALessSpecificMountedRootClassIsNotInvoked() {
        assertEquals("y", client.toBlocking().retrieve(HttpRequest.GET("/mount/a/y/next")));
        assertEquals("known", client.toBlocking().retrieve(HttpRequest.GET("/mount/a/x/known")));
        // the root class /mount/a/x matches the path, but none of its methods does
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> client.toBlocking().retrieve(HttpRequest.GET("/mount/a/x/next")));
        assertEquals(404, e.getStatus().getCode());
    }

    @Requires(property = "spec.name", value = "LocatorMountedRootTest")
    @Path("/mount/a")
    public static class LocatorResource {
        @Path("{x}/next")
        public Object next(@PathParam("x") String x) {
            return new Next(x);
        }
    }

    @Requires(property = "spec.name", value = "LocatorMountedRootTest")
    @Path("/mount/a/x")
    public static class FixedResource {
        @GET
        @Path("known")
        public String known() {
            return "known";
        }
    }

    /**
     * Its template is the one {@link LocatorResource} is mounted as.
     */
    @Requires(property = "spec.name", value = "LocatorMountedRootTest")
    @Path("/api/mount/a")
    public static class MountedNameResource {
        @GET
        public String get() {
            return "mounted";
        }
    }

    public static class Next {
        private final String x;

        Next(String x) {
            this.x = x;
        }

        @GET
        public String get() {
            return x;
        }
    }
}
