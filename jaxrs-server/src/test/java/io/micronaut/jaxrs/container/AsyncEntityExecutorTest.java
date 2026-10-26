package io.micronaut.jaxrs.container;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An asynchronous resource method whose entity arrives after the route is called runs on the
 * executor of its {@code @ExecuteOn}, otherwise off the event loop, like a synchronous one.
 */
@MicronautTest
@Property(name = "spec.name", value = "AsyncEntityExecutorTest")
class AsyncEntityExecutorTest {

    @Inject
    EmbeddedServer server;

    @Test
    void entityOfAnExecuteOnMethodIsReadOnItsExecutor() throws Exception {
        String thread = post("/io", "text/plain", "hello");
        assertTrue(thread.startsWith("io-executor"), thread);
    }

    @Test
    void entityOfAMethodIsReadOffTheEventLoop() throws Exception {
        String thread = post("/default", "text/plain", "hello");
        assertFalse(thread.toLowerCase(Locale.ROOT).contains("eventloop"), thread);
    }

    @Test
    void formOfAnExecuteOnMethodIsReadOnItsExecutor() throws Exception {
        String thread = post("/form", "application/x-www-form-urlencoded", "f=v");
        assertTrue(thread.startsWith("io-executor"), thread);
    }

    /**
     * Post the body once the request is sent, so it arrives after the route is called.
     */
    private String post(String path, String contentType, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(server.getURL() + "/api/async-entity-executor" + path))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.fromPublisher(new DelayedPublisher(bytes), bytes.length))
            .build();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    private record DelayedPublisher(byte[] bytes) implements Flow.Publisher<ByteBuffer> {
        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean sent;

                @Override
                public synchronized void request(long n) {
                    if (!sent) {
                        sent = true;
                        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> {
                            subscriber.onNext(ByteBuffer.wrap(bytes));
                            subscriber.onComplete();
                        });
                    }
                }

                @Override
                public void cancel() {
                }
            });
        }
    }

    @Requires(property = "spec.name", value = "AsyncEntityExecutorTest")
    @Path("/async-entity-executor")
    public static class Resource {

        @POST
        @Path("io")
        @ExecuteOn(TaskExecutors.IO)
        public CompletionStage<String> io(String entity) {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }

        @POST
        @Path("default")
        public CompletionStage<String> unannotated(String entity) {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }

        @POST
        @Path("form")
        @ExecuteOn(TaskExecutors.IO)
        public CompletionStage<String> form(@FormParam("f") String f) {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
