package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Prototype;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.core.Context;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resource created for a request is destroyed with the prototype beans injected into it, once
 * the response is done: their {@code @PreDestroy} methods run too.
 */
@MicronautTest
@Property(name = "spec.name", value = "PrototypeDependentDestroyTest")
class PrototypeDependentDestroyTest {

    static volatile CountDownLatch resourceDestroyed;
    static volatile CountDownLatch helperDestroyed;

    @Inject
    @Client("/api/prototype-dependent")
    HttpClient client;

    @Test
    void theHelperOfARootResourceIsDestroyed() throws InterruptedException {
        assertDestroyed("/", 1);
    }

    @Test
    void theHelperOfAResourceOfTheResourceContextIsDestroyed() throws InterruptedException {
        // the helpers of the root resource and of the resource of its ResourceContext
        assertDestroyed("/context", 2);
    }

    private void assertDestroyed(String path, int helpers) throws InterruptedException {
        resourceDestroyed = new CountDownLatch(1);
        helperDestroyed = new CountDownLatch(helpers);
        assertEquals("helper", client.toBlocking().retrieve(HttpRequest.GET(path)));
        assertTrue(resourceDestroyed.await(10, TimeUnit.SECONDS));
        assertTrue(helperDestroyed.await(10, TimeUnit.SECONDS));
    }

    @Requires(property = "spec.name", value = "PrototypeDependentDestroyTest")
    @Prototype
    public static class Helper {
        String name() {
            return "helper";
        }

        @PreDestroy
        void destroy() {
            helperDestroyed.countDown();
        }
    }

    @Requires(property = "spec.name", value = "PrototypeDependentDestroyTest")
    @Path("/prototype-dependent")
    public static class Resource {
        @Inject
        Helper helper;

        @Context
        ResourceContext resourceContext;

        @GET
        @Produces("text/plain")
        public String get() {
            return helper.name();
        }

        @GET
        @Path("context")
        @Produces("text/plain")
        public String context() {
            return resourceContext.getResource(Other.class).helper.name();
        }

        @PreDestroy
        void destroy() {
            resourceDestroyed.countDown();
        }
    }

    @Requires(property = "spec.name", value = "PrototypeDependentDestroyTest")
    @Prototype
    public static class Other {
        @Inject
        Helper helper;
    }
}
