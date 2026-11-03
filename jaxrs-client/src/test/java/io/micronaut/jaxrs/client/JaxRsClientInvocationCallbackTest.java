package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The entity type an asynchronous invocation reads is the type argument of the callback, which
 * the generic signature of the callback class gives, also without the micronaut-reflection module.
 */
@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsClientInvocationCallbackTest {

    @Inject
    EmbeddedServer server;

    Client client;

    @BeforeEach
    void setUp() {
        client = ClientBuilder.newClient();
    }

    @AfterEach
    void tearDown() {
        client.close();
    }

    @Test
    void anonymousCallbackReadsTheEntityAsItsTypeArgument() throws Exception {
        CompletableFuture<Object> completed = new CompletableFuture<>();
        String entity = hello().request().async().get(new InvocationCallback<String>() {
            @Override
            public void completed(String response) {
                completed.complete(response);
            }

            @Override
            public void failed(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }
        }).get(10, TimeUnit.SECONDS);

        assertEquals("hello", entity);
        assertEquals("hello", completed.get(10, TimeUnit.SECONDS));
    }

    @Test
    void callbackOfASubclassReadsTheEntityAsTheTypeArgumentOfItsSuperclass() throws Exception {
        CompletableFuture<Object> completed = new CompletableFuture<>();
        String entity = hello().request().async().get(new Recording<String>(completed) {
        }).get(10, TimeUnit.SECONDS);

        assertEquals("hello", entity);
        assertEquals("hello", completed.get(10, TimeUnit.SECONDS));
    }

    @Test
    void responseCallbackReadsTheResponse() throws Exception {
        CompletableFuture<Object> completed = new CompletableFuture<>();
        try (Response response = hello().request().async().get(new Recording<Response>(completed) {
        }).get(10, TimeUnit.SECONDS)) {
            assertEquals(200, response.getStatus());
            assertEquals("hello", response.readEntity(String.class));
        }
    }

    @Test
    void headCallbackReadsNoEntity() throws Exception {
        CompletableFuture<Object> completed = new CompletableFuture<>();
        try (Response response = hello().request().async().head(new Recording<Response>(completed) {
        }).get(10, TimeUnit.SECONDS)) {
            assertEquals(200, response.getStatus());
        }
    }

    private WebTarget hello() {
        return client.target(server.getURI()).path("client/hello");
    }

    private abstract static class Recording<T> implements InvocationCallback<T> {

        private final CompletableFuture<Object> completed;

        Recording(CompletableFuture<Object> completed) {
            this.completed = completed;
        }

        @Override
        public void completed(T response) {
            completed.complete(response);
        }

        @Override
        public void failed(Throwable throwable) {
            completed.completeExceptionally(throwable);
        }
    }
}
