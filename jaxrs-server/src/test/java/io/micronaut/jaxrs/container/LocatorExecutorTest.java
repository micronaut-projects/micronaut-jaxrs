package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sub-resource locators whose target is known only at runtime run off the event loop, as they
 * may block: on the executor of their {@code @ExecuteOn}, otherwise on the blocking one.
 */
@MicronautTest
@Property(name = "spec.name", value = "LocatorExecutorTest")
class LocatorExecutorTest {

    @Inject
    @Client("/api/locator-executor")
    HttpClient client;

    @Test
    void locatorReturningObjectRunsOffTheEventLoop() {
        String thread = client.toBlocking().retrieve(HttpRequest.GET("/blocking/thread"));
        assertFalse(thread.toLowerCase(Locale.ROOT).contains("eventloop"), thread);
    }

    @Test
    void locatedLocatorReturningObjectRunsOffTheEventLoop() {
        String thread = client.toBlocking().retrieve(HttpRequest.GET("/blocking/nested/thread"));
        assertFalse(thread.toLowerCase(Locale.ROOT).contains("eventloop"), thread);
    }

    @Test
    void locatorRunsOnTheExecutorOfItsExecuteOn() {
        String thread = client.toBlocking().retrieve(HttpRequest.GET("/io/thread"));
        assertTrue(thread.startsWith("io-executor"), thread);
    }

    @Test
    void formLocatorRunsOffTheEventLoop() {
        String thread = client.toBlocking().retrieve(HttpRequest.POST("/form/thread", Map.of("f", "f"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE));
        assertFalse(thread.toLowerCase(Locale.ROOT).contains("eventloop"), thread);
    }

    @Test
    void formLocatorRunsOnTheExecutorOfItsExecuteOn() {
        String thread = client.toBlocking().retrieve(HttpRequest.POST("/io-form/thread", Map.of("f", "f"))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE));
        assertTrue(thread.startsWith("io-executor"), thread);
    }

    @Requires(property = "spec.name", value = "LocatorExecutorTest")
    @Path("/locator-executor")
    public static class Root {

        @Path("blocking")
        public Object blocking() {
            return new Target(Thread.currentThread().getName());
        }

        @Path("io")
        @ExecuteOn(TaskExecutors.IO)
        public Object io() {
            return new Target(Thread.currentThread().getName());
        }

        @Path("form")
        public Object form(@FormParam("f") String f) {
            return new Target(Thread.currentThread().getName());
        }

        @Path("io-form")
        @ExecuteOn(TaskExecutors.IO)
        public Object ioForm(@FormParam("f") String f) {
            return new Target(Thread.currentThread().getName());
        }
    }

    public static class Target {
        private final String thread;

        public Target(String thread) {
            this.thread = thread;
        }

        @GET
        @Path("thread")
        public String thread() {
            return thread;
        }

        @POST
        @Path("thread")
        public String postedThread() {
            return thread;
        }

        @Path("nested")
        public Object nested() {
            return new Target(Thread.currentThread().getName());
        }
    }
}
