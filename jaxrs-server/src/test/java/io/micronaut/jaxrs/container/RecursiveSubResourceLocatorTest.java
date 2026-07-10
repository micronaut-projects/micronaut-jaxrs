package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecursiveSubResourceLocatorTest {

    @Test
    void invokesRecursiveSubResourceLocatorForRemainingPathSegments() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "RecursiveSubResourceLocatorTest"));
             HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertEquals("10", client.toBlocking().retrieve("/api/recursive-locator/recursive/lvl/lvl/lvl/lvl/lvl/lvl/lvl/lvl/lvl/lvl"));
        }
    }

    @Requires(property = "spec.name", value = "RecursiveSubResourceLocatorTest")
    @Path("/recursive-locator")
    static class RootResource {

        @Path("recursive")
        RecursiveResource recursive() {
            return new RecursiveResource();
        }

        @Path("{id}")
        OtherResource byId(@PathParam("id") int id) {
            return new OtherResource(id);
        }
    }

    static class RecursiveResource {
        private int level;

        @Path("{id}")
        RecursiveResource recursive() {
            level++;
            return this;
        }

        @GET
        String getLevel() {
            return String.valueOf(level);
        }
    }

    static class OtherResource {
        private final int id;

        OtherResource(int id) {
            this.id = id;
        }

        @GET
        String get() {
            return String.valueOf(id);
        }
    }
}
