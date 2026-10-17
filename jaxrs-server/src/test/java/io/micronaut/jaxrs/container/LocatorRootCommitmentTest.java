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
 * A sub-resource locator known only at runtime is invoked only when its root resource class is
 * the one JAX-RS commits to (JAX-RS 3.7.2, step 1).
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatorRootCommitmentTest")
class LocatorRootCommitmentTest {

    @Inject
    @Client("/api/commitment")
    HttpClient client;

    private String get(String path) {
        return client.toBlocking().retrieve(HttpRequest.GET(path), String.class);
    }

    @Test
    void locatorOfTheCommittedRootClassIsInvoked() {
        assertEquals("other", get("/other/next"));
    }

    @Test
    void locatorOfALessSpecificRootClassIsNotInvoked() {
        assertEquals("known", get("/fixed/known"));
        // the root class /fixed matches the path, but none of its methods does
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> get("/fixed/next"));
        assertEquals(404, e.getStatus().getCode());
    }

    @Requires(property = "spec.name", value = "LocatorRootCommitmentTest")
    @Path("/commitment/fixed")
    public static class FixedResource {
        @GET
        @Path("known")
        public String known() {
            return "known";
        }
    }

    @Requires(property = "spec.name", value = "LocatorRootCommitmentTest")
    @Path("/commitment/{id}")
    public static class VariableResource {
        @Path("next")
        public Object next(@PathParam("id") String id) {
            return new Next(id);
        }
    }

    public static class Next {
        private final String id;

        Next(String id) {
            this.id = id;
        }

        @GET
        public String get() {
            return id;
        }
    }
}
