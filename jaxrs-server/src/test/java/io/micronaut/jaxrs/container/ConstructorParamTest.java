package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A resource whose constructor reads values of the request is created for every request, with
 * those values, and gets its other constructor parameters injected.
 */
@MicronautTest
@Property(name = "spec.name", value = "ConstructorParamTest")
class ConstructorParamTest {

    @Inject
    @Client("/api/constructor-param")
    HttpClient client;

    @Test
    void resourceIsCreatedForEveryRequestWithItsValues() {
        assertEquals("1 7 Fred header /constructor-param/7/abc abc",
            client.toBlocking().retrieve(HttpRequest.GET("/7/abc?name=Fred").header("X-Value", "header")));
        assertEquals("2 8 unknown none /constructor-param/8/xyz xyz",
            client.toBlocking().retrieve(HttpRequest.GET("/8/xyz")));
    }

    @Singleton
    @Requires(property = "spec.name", value = "ConstructorParamTest")
    static class Counter {
        private final AtomicInteger count = new AtomicInteger();

        int next() {
            return count.incrementAndGet();
        }
    }

    @Requires(property = "spec.name", value = "ConstructorParamTest")
    @Path("/constructor-param/{id}")
    public static class ConstructorResource {

        private final int instance;
        private final long id;
        private final String name;
        private final String header;
        private final UriInfo uriInfo;

        public ConstructorResource(@PathParam("id") long id,
                                   @QueryParam("name") @DefaultValue("unknown") String name,
                                   @HeaderParam("X-Value") @DefaultValue("none") String header,
                                   @Context UriInfo uriInfo,
                                   Counter counter) {
            this.id = id;
            this.name = name;
            this.header = header;
            this.uriInfo = uriInfo;
            this.instance = counter.next();
        }

        @GET
        @Path("/{value}")
        @Produces("text/plain")
        public String get(@PathParam("value") String value) {
            return instance + " " + id + " " + name + " " + header + " " + uriInfo.getPath() + " " + value;
        }
    }
}
