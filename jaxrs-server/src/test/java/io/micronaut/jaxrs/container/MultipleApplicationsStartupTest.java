package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The applications of a context with several {@code Application} beans: their paths must be
 * distinct, and the one {@code micronaut.jaxrs.application} names is the only one.
 */
class MultipleApplicationsStartupTest {

    @Test
    void duplicateApplicationPathsFailTheStartup() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> {
            try (EmbeddedServer ignored = ApplicationContext.run(EmbeddedServer.class,
                Map.of("spec.name", "MultipleApplicationsStartupTest", "micronaut.server.port", -1))) {
                // started
            }
        });
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("have the same application path: /dup"), messages.toString());
    }

    @Test
    void namedApplicationIsTheOnlyOne() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of(
                "spec.name", "MultipleApplicationsTest",
                "micronaut.server.port", -1,
                JaxRsApplicationFactory.APPLICATION, MultipleApplicationsTest.AppTwo.class.getName()));
             HttpClient client = HttpClient.create(server.getURL())) {
            assertEquals("two", client.toBlocking().retrieve(HttpRequest.GET("/two/res")));
            HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().retrieve(HttpRequest.GET("/one/res")));
            assertEquals(404, e.getStatus().getCode());
        }
    }

    @Requires(property = "spec.name", value = "MultipleApplicationsStartupTest")
    @ApplicationPath("/dup")
    public static class DupOne extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(MultipleApplicationsTest.MultiResource.class);
        }
    }

    @Requires(property = "spec.name", value = "MultipleApplicationsStartupTest")
    @ApplicationPath("dup/")
    public static class DupTwo extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(MultipleApplicationsTest.MultiResource.class);
        }
    }
}
