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
            // the block without data after the event sets the id the reconnection sends
            var replacement = new ByteArrayInputStream("id: 3\ndata: replacement\n\nid: 7\n\n".getBytes(StandardCharsets.UTF_8)) {
                volatile boolean closed;

                @Override
                public void close() throws IOException {
                    closed = true;
                    super.close();
                }
            };
            AtomicInteger requests = new AtomicInteger();
            CompletableFuture<String> reconnectedWith = new CompletableFuture<>();
            WebTarget target = target(client, "endless")
                .register((ClientRequestFilter) request -> {
                    if (requests.incrementAndGet() == 2) {
                        reconnectedWith.complete(String.valueOf(request.getHeaderString("Last-Event-ID")));
                    }
                })
                .register((ClientResponseFilter) (request, response) -> response.setEntityStream(
                    requests.get() == 1 ? replacement : new ByteArrayInputStream(new byte[0])));
            CompletableFuture<String> received = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).reconnectingEvery(10, TimeUnit.MILLISECONDS).build()) {
                source.register(event -> received.complete(event.readData()));
                source.open();
                assertEquals("replacement", received.get(10, TimeUnit.SECONDS));
                assertEquals("7", reconnectedWith.get(10, TimeUnit.SECONDS));
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
    void aClientTracksItsOpenEventSources() throws Exception {
        JaxRsClient client = (JaxRsClient) ClientBuilder.newClient();
        try {
            CountDownLatch received = new CountDownLatch(1);
            WebTarget target = target(client, "events")
                .register((ClientRequestFilter) context -> context.getHeaders().putSingle("Authorization", "Bearer token"));
            JaxRsSseEventSource source = (JaxRsSseEventSource) SseEventSource.target(target).build();
            source.register(event -> received.countDown());
            source.open();
            assertTrue(received.await(10, TimeUnit.SECONDS));
            // closing the client would shut down the event loop of the event source
            assertTrue(client.hasOpenEventSources());
            source.close();
            assertFalse(client.hasOpenEventSources());
        } finally {
            client.close();
        }
    }

    @Test
    void reconnectsWhenTheConnectionBreaks() throws Exception {
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(target(client, "dropping")).reconnectingEvery(50, TimeUnit.MILLISECONDS).build()) {
            var recorder = new SseRecorder(2);
            recorder.register(source);
            source.open();
            recorder.await();
            assertTrue(recorder.errors.isEmpty(), recorder.errors::toString);
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

    @Test
    void aRequestFilterChangesTheMethod() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            WebTarget target = target(client, "method-events")
                .register((ClientRequestFilter) context -> context.setMethod("POST"));
            CompletableFuture<String> received = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> received.complete(event.readData()));
                source.open();
                assertEquals("POST", received.get(10, TimeUnit.SECONDS));
            }
        } finally {
            client.close();
        }
    }

    @Test
    void aResponseFilterWrapsTheEventStream() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            WebTarget target = target(client, "endless")
                .register((ClientResponseFilter) (request, response) -> response.setEntityStream(
                    new java.io.BufferedInputStream(response.getEntityStream())));
            CompletableFuture<String> received = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> received.complete(event.readData()));
                source.open();
                assertEquals("tick 0", received.get(10, TimeUnit.SECONDS));
            }
        } finally {
            client.close();
        }
    }

    @Test
    void aTimedCloseDoesNotWaitForABlockedRequestFilter() throws Exception {
        Client client = ClientBuilder.newClient();
        CountDownLatch filtering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            WebTarget target = target(client, "endless")
                .register((ClientRequestFilter) context -> {
                    filtering.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            SseEventSource source = SseEventSource.target(target).build();
            CompletableFuture<Void> opened = CompletableFuture.runAsync(source::open);
            assertTrue(filtering.await(10, TimeUnit.SECONDS));
            CompletableFuture<Boolean> closed = CompletableFuture.supplyAsync(() -> source.close(10, TimeUnit.MILLISECONDS));
            closed.get(5, TimeUnit.SECONDS);
            assertFalse(source.isOpen());
            release.countDown();
            opened.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            client.close();
        }
    }

    @Test
    void anotherSuccessfulStatusIsAnErrorWithTheFilteredResponse() throws Exception {
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(target(client, "created-events")).build()) {
            CompletableFuture<Throwable> failed = new CompletableFuture<>();
            source.register(event -> { }, failed::complete);
            source.open();
            Response response = ((WebApplicationException) failed.get(10, TimeUnit.SECONDS)).getResponse();
            assertEquals(201, response.getStatus());
            assertEquals("kept", response.getHeaderString("X-Proof"));
        } finally {
            client.close();
        }
    }

    @Test
    void aResponseThatIsNotAnEventStreamIsAnErrorWithTheResponse() throws Exception {
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(target(client, "json-events")).build()) {
            CompletableFuture<Throwable> failed = new CompletableFuture<>();
            source.register(event -> { }, failed::complete);
            source.open();
            Throwable error = failed.get(10, TimeUnit.SECONDS);
            // a WebApplicationException with the response (JAX-RS, SseEventSource)
            assertTrue(error instanceof WebApplicationException, error.toString());
            Response response = ((WebApplicationException) error).getResponse();
            assertEquals(200, response.getStatus());
            assertEquals("kept", response.getHeaderString("X-Proof"));
        } finally {
            client.close();
        }
    }

    @Test
    void aWrappedEventStreamKeepsTheLeadingSpaceOfAValue() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            WebTarget target = target(client, "spaced-events")
                .register((ClientResponseFilter) (request, response) -> response.setEntityStream(
                    new java.io.BufferedInputStream(response.getEntityStream())));
            CompletableFuture<String> received = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> received.complete("[" + event.getId() + "][" + event.getName() + "][" + event.readData() + "]"));
                source.open();
                assertEquals("[ a][ b][ hello]", received.get(10, TimeUnit.SECONDS));
            }
        } finally {
            client.close();
        }
    }

    @Test
    void aServiceUnavailableStatusSetByAFilterReconnects() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            AtomicInteger connections = new AtomicInteger();
            CountDownLatch reconnected = new CountDownLatch(2);
            WebTarget target = target(client, "endless")
                .register((ClientResponseFilter) (request, response) -> {
                    connections.incrementAndGet();
                    reconnected.countDown();
                    response.setStatus(503);
                    response.getHeaders().putSingle("Retry-After", "0");
                });
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> { }, errors::add);
                source.open();
                assertTrue(reconnected.await(10, TimeUnit.SECONDS), () -> "connections " + connections.get() + ", errors " + errors);
            }
        } finally {
            client.close();
        }
    }

    @Test
    void aResetIdIsNotSentOnReconnect() throws Exception {
        Client client = ClientBuilder.newClient();
        ClientTestController.LAST_EVENT_IDS.clear();
        try (SseEventSource source = SseEventSource.target(target(client, "reset-id-events"))
            .reconnectingEvery(10, TimeUnit.MILLISECONDS).build()) {
            source.register(event -> { });
            source.open();
            assertEquals("none", ClientTestController.LAST_EVENT_IDS.poll(10, TimeUnit.SECONDS));
        } finally {
            client.close();
        }
    }

    @Test
    void aSignedRetryAfterIsNotValid() throws Exception {
        assertNull(JaxRsSseEventSource.retryAfter("+0"));
        assertNull(JaxRsSseEventSource.retryAfter("-1"));
        assertEquals(2000L, JaxRsSseEventSource.retryAfter("2"));
        Client client = ClientBuilder.newClient();
        try (SseEventSource source = SseEventSource.target(target(client, "invalid-retry")).build()) {
            CompletableFuture<Throwable> failed = new CompletableFuture<>();
            source.register(event -> { }, failed::complete);
            source.open();
            assertEquals(503, ((WebApplicationException) failed.get(10, TimeUnit.SECONDS)).getResponse().getStatus());
        } finally {
            client.close();
        }
    }

    @Test
    void aBlockedReconnectionFilterDoesNotDelayAnotherSource() throws Exception {
        // the sources share the default scheduler of the reconnections
        Client client = ClientBuilder.newClient();
        CountDownLatch filtering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger blockedRequests = new AtomicInteger();
        WebTarget blocked = target(client, "dropping")
            .register((ClientRequestFilter) context -> {
                if (blockedRequests.incrementAndGet() == 2) {
                    filtering.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        CountDownLatch retried = new CountDownLatch(2);
        WebTarget unavailable = target(client, "endless")
            .register((ClientResponseFilter) (request, response) -> {
                retried.countDown();
                response.setStatus(503);
                response.getHeaders().putSingle("Retry-After", "0");
            });
        try (SseEventSource first = SseEventSource.target(blocked).reconnectingEvery(10, TimeUnit.MILLISECONDS).build();
             SseEventSource second = SseEventSource.target(unavailable).build()) {
            first.register(event -> { });
            second.register(event -> { });
            first.open();
            assertTrue(filtering.await(10, TimeUnit.SECONDS));
            second.open();
            assertTrue(retried.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            client.close();
        }
    }

    @Test
    void anErrorResponseRepairedByAFilterDeliversItsEvents() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            var replacement = new ByteArrayInputStream("data: recovered\n\n".getBytes(StandardCharsets.UTF_8));
            WebTarget target = target(client, "failing-events")
                .register((ClientResponseFilter) (request, response) -> {
                    response.setStatus(200);
                    response.getHeaders().putSingle("Content-Type", "text/event-stream");
                    response.setEntityStream(replacement);
                });
            CompletableFuture<String> received = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> received.complete(event.readData()), received::completeExceptionally,
                    () -> received.complete("completed"));
                source.open();
                assertEquals("recovered", received.get(10, TimeUnit.SECONDS));
            }
        } finally {
            client.close();
        }
    }

    @Test
    void anErrorResponseChangedToAnotherSuccessIsAnError() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            WebTarget target = target(client, "failing-events")
                .register((ClientResponseFilter) (request, response) -> response.setStatus(201));
            CompletableFuture<Object> failed = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> { }, failed::complete, () -> failed.complete("completed"));
                source.open();
                Object error = failed.get(10, TimeUnit.SECONDS);
                assertTrue(error instanceof WebApplicationException, String.valueOf(error));
                assertEquals(201, ((WebApplicationException) error).getResponse().getStatus());
            }
        } finally {
            client.close();
        }
    }

    @Test
    void aTimedCloseDoesNotWaitForABlockedStreamClose() throws Exception {
        Client client = ClientBuilder.newClient();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reading = new CountDownLatch(1);
        try {
            // a stream whose read and close block until released
            var replacement = new java.io.InputStream() {
                @Override
                public int read() throws IOException {
                    reading.countDown();
                    await();
                    return -1;
                }

                @Override
                public void close() {
                    await();
                }

                private void await() {
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            WebTarget target = target(client, "endless")
                .register((ClientResponseFilter) (request, response) -> response.setEntityStream(replacement));
            SseEventSource source = SseEventSource.target(target).build();
            source.register(event -> { });
            source.open();
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            long start = System.nanoTime();
            assertFalse(source.close(10, TimeUnit.MILLISECONDS));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        } finally {
            release.countDown();
            client.close();
        }
    }

    @Test
    void aRequestFilterSetsTheEntity() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            WebTarget target = target(client, "body-events")
                .register((ClientRequestFilter) context -> {
                    context.setMethod("POST");
                    context.setEntity("subscription", null, jakarta.ws.rs.core.MediaType.TEXT_PLAIN_TYPE);
                });
            CompletableFuture<String> received = new CompletableFuture<>();
            try (SseEventSource source = SseEventSource.target(target).build()) {
                source.register(event -> received.complete(event.readData()));
                source.open();
                assertEquals("subscription", received.get(10, TimeUnit.SECONDS));
            }
        } finally {
            client.close();
        }
    }

    @Test
    void anEntityStreamReplacedBeforeAnAbortIsClosed() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            var replaced = new java.io.ByteArrayOutputStream() {
                volatile boolean closed;

                @Override
                public void close() {
                    closed = true;
                }
            };
            WebTarget target = target(client, "events")
                .register((ClientRequestFilter) context -> context.setEntityStream(replaced), 1)
                .register((ClientRequestFilter) context -> context.abortWith(Response.noContent().build()), 2);
            try (SseEventSource source = SseEventSource.target(target).build()) {
                CompletableFuture<Throwable> ended = new CompletableFuture<>();
                source.register(event -> { }, ended::complete, () -> ended.complete(null));
                source.open();
                assertNull(ended.get(10, TimeUnit.SECONDS));
            }
            assertTrue(replaced.closed);
        } finally {
            client.close();
        }
    }

    @Test
    void theEntityOfASuccessfulAbortIsClosed() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            var entity = new ClosingStream("unused");
            WebTarget target = target(client, "events")
                .register((ClientRequestFilter) context -> context.abortWith(Response.ok(entity).build()));
            try (SseEventSource source = SseEventSource.target(target).build()) {
                CompletableFuture<Throwable> ended = new CompletableFuture<>();
                source.register(event -> { }, ended::complete, () -> ended.complete(null));
                source.open();
                assertNull(ended.get(10, TimeUnit.SECONDS));
            }
            assertTrue(entity.closed);
        } finally {
            client.close();
        }
    }

    @Test
    void theEntityStreamOfARetriedErrorResponseIsClosed() throws Exception {
        Client client = ClientBuilder.newClient();
        try {
            var entity = new ClosingStream("retried");
            AtomicInteger connections = new AtomicInteger();
            WebTarget target = target(client, "unavailable")
                .register((ClientResponseFilter) (request, response) -> {
                    if (connections.incrementAndGet() == 1) {
                        response.getHeaders().putSingle("Retry-After", "0");
                        response.setEntityStream(entity);
                    }
                });
            try (SseEventSource source = SseEventSource.target(target).build()) {
                CompletableFuture<Throwable> ended = new CompletableFuture<>();
                source.register(event -> { }, ended::complete, () -> ended.complete(null));
                source.open();
                assertTrue(ended.get(10, TimeUnit.SECONDS) instanceof WebApplicationException);
            }
            assertEquals(2, connections.get());
            assertTrue(entity.closed);
        } finally {
            client.close();
        }
    }

    private static final class ClosingStream extends ByteArrayInputStream {
        volatile boolean closed;

        ClosingStream(String content) {
            super(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
