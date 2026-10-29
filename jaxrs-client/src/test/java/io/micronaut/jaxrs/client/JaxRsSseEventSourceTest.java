package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.InboundSseEvent;
import jakarta.ws.rs.sse.SseEventSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event source processes the events on the executor of the client, reconnects when the
 * connection breaks and is closed with its client (JAX-RS 9.4).
 */
@Property(name = "spec.name", value = "JaxRsClient")
@MicronautTest
class JaxRsSseEventSourceTest {

    @Inject
    EmbeddedServer server;

    private WebTarget target(Client client, String path) {
        return client.target(server.getURI()).path("client").path(path);
    }

    @Test
    void eventsAreProcessedOnTheExecutorOfTheClient() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sse-test-executor"));
        Client client = ClientBuilder.newBuilder().executorService(executor).build();
        try {
            List<String> threads = new CopyOnWriteArrayList<>();
            CountDownLatch completed = new CountDownLatch(1);
            CountDownLatch received = new CountDownLatch(2);
            WebTarget target = target(client, "events")
                .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"));
            SseEventSource source = SseEventSource.target(target).build();
            source.register(event -> {
                threads.add(Thread.currentThread().getName());
                received.countDown();
            }, error -> { }, () -> {
                threads.add(Thread.currentThread().getName());
                completed.countDown();
            });
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            source.close();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            assertFalse(threads.isEmpty());
            for (String thread : threads) {
                assertEquals("sse-test-executor", thread);
            }
        } finally {
            client.close();
            executor.shutdown();
        }
    }

    @Test
    void responseFiltersRunOnTheExecutorOfTheClient() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "sse-test-executor"));
        Client client = ClientBuilder.newBuilder().executorService(executor).build();
        try {
            CompletableFuture<String> filterThread = new CompletableFuture<>();
            WebTarget target = target(client, "events")
                .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"))
                .register((ClientResponseFilter) (request, response) -> filterThread.complete(Thread.currentThread().getName()));
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> { });
                source.open();
                assertEquals("sse-test-executor", filterThread.get(10, TimeUnit.SECONDS));
            }
        } finally {
            client.close();
            executor.shutdown();
        }
    }

    @Test
    void aResponseFilterReplacesTheEventStream() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            var replacement = new ByteArrayInputStream("data: replacement\n\n".getBytes(StandardCharsets.UTF_8)) {
                volatile boolean closed;

                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
            WebTarget target = target(client, "endless")
                .register((ClientResponseFilter) (request, response) -> response.setEntityStream(replacement));
            CompletableFuture<String> received = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).reconnectingEvery(1, TimeUnit.HOURS).build()) {
                source.register(event -> received.complete(event.readData()));
                source.open();
                assertEquals("replacement", received.get(10, TimeUnit.SECONDS));
            }
            assertTrue(replacement.closed);
        } finally {
            client.close();
        }
    }

    @Test
    void anAbortedRequestIsFilteredByTheResponseFilters() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            AtomicInteger filtered = new AtomicInteger();
            WebTarget target = target(client, "events")
                .register((ClientRequestFilter) context -> context.abortWith(Response.noContent().build()))
                .register((ClientResponseFilter) (request, response) -> {
                    filtered.incrementAndGet();
                    response.setStatus(500);
                });
            try (SseEventSource source = SseEventSource.target(target).build()) {
                CompletableFuture<Throwable> ended = new CompletableFuture<>();
                source.register(event -> { }, ended::complete, () -> ended.complete(null));
                source.open();
                Throwable error = ended.get(10, TimeUnit.SECONDS);
                assertEquals(1, filtered.get());
                assertTrue(error instanceof WebApplicationException, () -> String.valueOf(error));
                assertEquals(500, ((WebApplicationException) error).getResponse().getStatus());
            }
        } finally {
            client.close();
        }
    }

    @Test
    void closingTheClientShutsDownTheEventLoopOfItsEventSources() throws Exception {
        JaxRsClient client = (JaxRsClient) ClientBuilder.newClient();
        CountDownLatch received = new CountDownLatch(1);
        WebTarget target = target(client, "events")
            .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"));
        JaxRsSseEventSource source = (JaxRsSseEventSource) SseEventSource.target(target).build();
        source.register(event -> received.countDown());
        source.open();
        assertTrue(received.await(10, TimeUnit.SECONDS));
        // the HTTP client of the event source runs on the event loop of the main HTTP client
        HttpClient sourceClient = source.httpClient();
        HttpClient mainClient = client.getHttpClient();
        assertTrue(sourceClient.isRunning());
        client.close();
        assertFalse(source.isOpen());
        assertFalse(mainClient.isRunning());
        assertFalse(sourceClient.isRunning());
    }

    @Test
    void releasingAClientForTestingKeepsItsOpenEventSources() throws Exception {
        JaxRsClient client = (JaxRsClient) ClientBuilder.newClient();
        try {
            CountDownLatch received = new CountDownLatch(1);
            WebTarget target = target(client, "events")
                .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"));
            JaxRsSseEventSource source = (JaxRsSseEventSource) SseEventSource.target(target).build();
            source.register(event -> received.countDown());
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            HttpClient sourceClient = source.httpClient();
            // the event loop of the event source is the one of the main HTTP client: both stay open
            client.closeForTesting();
            assertTrue(source.isOpen());
            assertTrue(client.getHttpClient().isRunning());
            assertTrue(sourceClient.isRunning());
            source.close();
            client.closeForTesting();
            assertFalse(client.getHttpClient().isRunning());
        } finally {
            client.close();
        }
    }

    @Test
    void reconnectsWhenTheConnectionBreaks() throws Exception {
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(target(client, "dropping")).reconnectingEvery(50, TimeUnit.MILLISECONDS).build()) {
            List<String> events = new CopyOnWriteArrayList<>();
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            CountDownLatch received = new CountDownLatch(2);
            source.register(event -> {
                events.add(event.readData());
                received.countDown();
            }, errors::add);
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS), () -> "events " + events + ", errors " + errors);
            assertTrue(errors.isEmpty(), errors::toString);
        } finally {
            client.close();
        }
    }

    @Test
    void closeDuringConnectReleasesTheConnection() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            AtomicReference<SseEventSource> sourceRef = new AtomicReference<>();
            // the source is closed while it connects, before its HTTP client is created
            WebTarget target = target(client, "endless")
                .register((ClientRequestFilter) context -> sourceRef.get().close());
            JaxRsSseEventSource source = (JaxRsSseEventSource) SseEventSource.target(target).build();
            sourceRef.set(source);
            CompletableFuture<Void> completed = new CompletableFuture<>();
            source.register((InboundSseEvent event) -> { }, completed::completeExceptionally, () -> completed.complete(null));
            source.open();
            completed.get(10, TimeUnit.SECONDS);
            assertFalse(source.isOpen());
            assertNull(source.httpClient());
        } finally {
            client.close();
        }
    }

    @Test
    void closingTheClientClosesItsEventSources() throws Exception {
        Client client = ClientBuilder.newClient();
        JaxRsSseEventSource source = (JaxRsSseEventSource) SseEventSource.target(target(client, "endless")).build();
        CountDownLatch received = new CountDownLatch(1);
        CompletableFuture<Void> completed = new CompletableFuture<>();
        source.register(event -> received.countDown(), completed::completeExceptionally, () -> completed.complete(null));
        source.open();
        assertTrue(received.await(10, TimeUnit.SECONDS));

        client.close();

        completed.get(10, TimeUnit.SECONDS);
        assertFalse(source.isOpen());
        assertNull(source.httpClient());
    }

    @Test
    void aSlowConsumerSlowsTheStream() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            JaxRsSseEventSource source = (JaxRsSseEventSource) SseEventSource.target(target(client, "flood")).build();
            CountDownLatch received = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            source.register(event -> {
                received.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            Thread.sleep(500);
            // the events are not read while the consumer processes one
            assertTrue(source.pendingCallbacks() <= 1, () -> "pending " + source.pendingCallbacks());
            release.countDown();
            source.close();
        } finally {
            client.close();
        }
    }

    @Test
    void anEndedSourceIsNotOpenedAgain() throws Exception {
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(target(client, "no-events")).build()) {
            CompletableFuture<Void> completed = new CompletableFuture<>();
            source.register(event -> { }, completed::completeExceptionally, () -> completed.complete(null));
            source.open();
            completed.get(10, TimeUnit.SECONDS);
            assertFalse(source.isOpen());
            assertThrows(IllegalStateException.class, source::open);
        } finally {
            client.close();
        }
    }

    @Test
    void anotherSuccessfulStatusIsAnError() throws Exception {
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(target(client, "created-events")).build()) {
            List<String> events = new CopyOnWriteArrayList<>();
            CompletableFuture<Throwable> failed = new CompletableFuture<>();
            source.register(event -> events.add(event.readData()), failed::complete);
            source.open();
            Throwable error = failed.get(10, TimeUnit.SECONDS);
            assertEquals(201, ((WebApplicationException) error).getResponse().getStatus());
            assertTrue(events.isEmpty());
        } finally {
            client.close();
        }
    }

    @Test
    void closingAgainWaitsForTheProcessing() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            SseEventSource source = SseEventSource.target(target(client, "endless")).build();
            CountDownLatch received = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            source.register(event -> {
                received.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            assertFalse(source.close(50, TimeUnit.MILLISECONDS));
            // the consumer still runs
            assertFalse(source.close(50, TimeUnit.MILLISECONDS));
            release.countDown();
            assertTrue(source.close(10, TimeUnit.SECONDS));
        } finally {
            client.close();
        }
    }

    @Test
    void aConsumerClosesWhileAnotherCloseWaits() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            SseEventSource source = SseEventSource.target(target(client, "endless")).build();
            CountDownLatch received = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            source.register(event -> {
                received.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // needs the source while the other close waits for this consumer
                source.close();
            });
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            assertFalse(source.close(50, TimeUnit.MILLISECONDS));
            CompletableFuture<Boolean> closedAgain = CompletableFuture.supplyAsync(() -> source.close(10, TimeUnit.SECONDS));
            Thread.sleep(200);
            release.countDown();
            assertTrue(closedAgain.get(5, TimeUnit.SECONDS));
        } finally {
            client.close();
        }
    }
}
