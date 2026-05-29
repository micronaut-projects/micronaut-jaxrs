/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.jaxrs.client;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.http.client.sse.SseClient;
import io.micronaut.http.sse.Event;
import io.micronaut.jaxrs.common.JaxRsTemporaryFiles;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.RedirectionException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.RxInvoker;
import jakarta.ws.rs.client.RxInvokerProvider;
import jakarta.ws.rs.client.SyncInvoker;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaxRsClientBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void jaxRsClientPreservesEncodedResponseHeaders() {
        Client client = new JaxRsClientBuilder().build();
        try {
            JaxRsClient jaxRsClient = (JaxRsClient) client;
            DefaultHttpClient httpClient = (DefaultHttpClient) jaxRsClient.getHttpClient();

            assertFalse(httpClient.getConfiguration().isDecompressionEnabled());
        } finally {
            client.close();
        }
    }

    @Test
    void newClientWithConfigurationCopiesProperties() {
        Client configured = new JaxRsClientBuilder()
            .property("JAXRSTCK", "JAXRSTCK")
            .build();
        try {
            Client client = ClientBuilder.newClient(configured.getConfiguration());
            try {
                assertEquals("JAXRSTCK", client.getConfiguration().getProperty("JAXRSTCK"));
            } finally {
                client.close();
            }
        } finally {
            configured.close();
        }
    }

    @Test
    void sseFileDataUsesConfiguredTemporaryDirectory() throws Exception {
        Path configured = Files.createDirectory(tempDir.resolve("jaxrs-sse"));
        JaxRsInboundSseEvent event = new JaxRsInboundSseEvent(Event.of("data"), configured);

        File file = event.readData(File.class);

        assertTrue(file.toPath().startsWith(configured));
        assertEquals("data", Files.readString(file.toPath()));
    }

    @Test
    void tempDirectoryPropertyAcceptsPathValues() {
        Client client = new JaxRsClientBuilder()
            .property(JaxRsTemporaryFiles.TEMP_DIRECTORY_PROPERTY, tempDir)
            .build();
        try {
            JaxRsConfiguration configuration = (JaxRsConfiguration) client.getConfiguration();

            assertEquals(tempDir, configuration.tempDirectory());
        } finally {
            client.close();
        }
    }

    @Test
    void nullTargetsThrowNullPointerException() {
        Client client = new JaxRsClientBuilder().build();
        try {
            assertThrows(NullPointerException.class, () -> client.target((String) null));
            assertThrows(NullPointerException.class, () -> client.target((URI) null));
            assertThrows(NullPointerException.class, () -> client.target((Link) null));
            assertThrows(NullPointerException.class, () -> client.invocation(null));
        } finally {
            client.close();
        }
    }

    @Test
    void closeInvalidatesClientAndTargets() {
        Client client = new JaxRsClientBuilder().build();
        WebTarget target = client.target("http://localhost");

        client.close();
        client.close();

        assertThrows(IllegalStateException.class, client::getConfiguration);
        assertThrows(IllegalStateException.class, () -> client.target("http://localhost"));
        assertThrows(IllegalStateException.class, target::getUri);
        assertThrows(IllegalStateException.class, target::request);
    }

    @Test
    void rxInvokerClassUsesRegisteredProvider() {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        TestRxInvokerProvider.executorService = null;
        Client client = new JaxRsClientBuilder()
            .executorService(executorService)
            .build();
        try {
            TestRxInvoker invoker = client
                .register(TestRxInvokerProvider.class)
                .target("http://localhost")
                .request()
                .rx(TestRxInvoker.class);

            assertEquals(TestRxInvoker.class, invoker.getClass());
            assertSame(executorService, TestRxInvokerProvider.executorService);
        } finally {
            client.close();
            executorService.shutdownNow();
        }
    }

    @Test
    void scheduledExecutorServiceIsCopiedToClientConfiguration() {
        ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        Client client = new JaxRsClientBuilder()
            .scheduledExecutorService(scheduledExecutorService)
            .build();
        try {
            JaxRsConfiguration configuration = (JaxRsConfiguration) client
                .target("http://localhost")
                .getConfiguration();

            assertSame(scheduledExecutorService, configuration.getScheduledExecutorService());
        } finally {
            client.close();
            scheduledExecutorService.shutdownNow();
        }
    }

    @Test
    void sseReconnectUsesConfiguredSchedulerAndDoesNotShutItDown() {
        RecordingScheduledExecutorService scheduledExecutorService = new RecordingScheduledExecutorService();
        Client client = new JaxRsClientBuilder().build();
        JaxRsSseEventSource eventSource = new JaxRsSseEventSource(
            client.target("http://localhost"),
            25,
            scheduledExecutorService,
            false,
            new CompletingSseClient(),
            false,
            null
        );
        try {
            eventSource.open();

            assertEquals(25, scheduledExecutorService.delayMillis.get());
            assertTrue(eventSource.close(1, TimeUnit.SECONDS));
            assertFalse(scheduledExecutorService.isShutdown());
        } finally {
            eventSource.close(1, TimeUnit.SECONDS);
            client.close();
            scheduledExecutorService.shutdownNow();
        }
    }

    @Test
    void closingUnopenedSseEventSourceCompletesImmediately() {
        RecordingScheduledExecutorService scheduledExecutorService = new RecordingScheduledExecutorService();
        Client client = new JaxRsClientBuilder().build();
        JaxRsSseEventSource eventSource = new JaxRsSseEventSource(
            client.target("http://localhost"),
            25,
            scheduledExecutorService,
            true,
            new CompletingSseClient(),
            false,
            null
        );
        try {
            assertTrue(eventSource.close(1, TimeUnit.MILLISECONDS));
            assertTrue(scheduledExecutorService.isShutdown());
        } finally {
            client.close();
            scheduledExecutorService.shutdownNow();
        }
    }

    @Test
    void closingOwnedSseEventSourceClosesSseClient() {
        RecordingScheduledExecutorService scheduledExecutorService = new RecordingScheduledExecutorService();
        ClosingSseClient sseClient = new ClosingSseClient();
        Client client = new JaxRsClientBuilder().build();
        JaxRsSseEventSource eventSource = new JaxRsSseEventSource(
            client.target("http://localhost"),
            25,
            scheduledExecutorService,
            false,
            sseClient,
            true,
            null
        );
        try {
            eventSource.open();

            assertTrue(eventSource.close(1, TimeUnit.SECONDS));
            assertTrue(sseClient.closed.get());
            assertFalse(scheduledExecutorService.isShutdown());
        } finally {
            eventSource.close(1, TimeUnit.SECONDS);
            client.close();
            scheduledExecutorService.shutdownNow();
        }
    }

    @Test
    void asyncInvokerRunsRequestFiltersOnConfiguredExecutor() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService executorService = Executors.newSingleThreadExecutor(task -> new Thread(task, "jaxrs-client-executor"));
        Client client = new JaxRsClientBuilder()
            .executorService(executorService)
            .build()
            .register((ClientRequestFilter) requestContext -> {
                threadName.set(Thread.currentThread().getName());
                requestContext.abortWith(Response.ok().build());
            });
        try {
            Response response = client.target("http://localhost")
                .request()
                .rx()
                .get()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

            assertEquals(200, response.getStatus());
            assertEquals("jaxrs-client-executor", threadName.get());
        } finally {
            client.close();
            executorService.shutdownNow();
        }
    }

    @Test
    void headerStripsHttpOptionalWhitespace() {
        AtomicReference<String> authorization = new AtomicReference<>();
        Client client = new JaxRsClientBuilder()
            .build()
            .register((ClientRequestFilter) requestContext -> {
                authorization.set(requestContext.getHeaderString("Authorization"));
                requestContext.abortWith(Response.ok().build());
            });
        try {
            Response response = client.target("http://localhost")
                .request()
                .header("Authorization", " Basic token\t")
                .get();

            assertEquals(200, response.getStatus());
            assertEquals("Basic token", authorization.get());
        } finally {
            client.close();
        }
    }

    @Test
    void typedInvocationThrowsMostSpecificWebApplicationException() {
        AtomicReference<Integer> status = new AtomicReference<>();
        Client client = new JaxRsClientBuilder()
            .build()
            .register((ClientRequestFilter) requestContext -> requestContext.abortWith(Response.status(status.get()).build()));
        try {
            for (ExpectedException expected : List.of(
                new ExpectedException(345, RedirectionException.class),
                new ExpectedException(456, ClientErrorException.class),
                new ExpectedException(400, BadRequestException.class),
                new ExpectedException(401, NotAuthorizedException.class),
                new ExpectedException(403, ForbiddenException.class),
                new ExpectedException(404, NotFoundException.class),
                new ExpectedException(405, NotAllowedException.class),
                new ExpectedException(406, NotAcceptableException.class),
                new ExpectedException(415, NotSupportedException.class),
                new ExpectedException(567, ServerErrorException.class),
                new ExpectedException(500, InternalServerErrorException.class),
                new ExpectedException(503, ServiceUnavailableException.class)
            )) {
                status.set(expected.status());
                assertThrows(expected.type(), () -> client.target("http://localhost").request().get(String.class));
            }
        } finally {
            client.close();
        }
    }

    private record ExpectedException(int status, Class<? extends WebApplicationException> type) {
    }

    private static final class RecordingScheduledExecutorService extends ScheduledThreadPoolExecutor {
        private final AtomicLong delayMillis = new AtomicLong(-1);

        private RecordingScheduledExecutorService() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            delayMillis.set(unit.toMillis(delay));
            return super.schedule(() -> {
            }, 1, TimeUnit.DAYS);
        }
    }

    private static final class CompletingSseClient implements SseClient {
        @Override
        public <I> Publisher<Event<ByteBuffer<?>>> eventStream(HttpRequest<I> request) {
            return subscriber -> {
                subscriber.onSubscribe(new EmptySubscription());
                subscriber.onComplete();
            };
        }

        @Override
        public <I, B> Publisher<Event<B>> eventStream(HttpRequest<I> request, Argument<B> eventType) {
            return complete();
        }

        @Override
        public <I, B> Publisher<Event<B>> eventStream(HttpRequest<I> request, Argument<B> eventType, Argument<?> errorType) {
            return complete();
        }

        private static <B> Publisher<Event<B>> complete() {
            return subscriber -> {
                subscriber.onSubscribe(new EmptySubscription());
                subscriber.onComplete();
            };
        }
    }

    private static final class ClosingSseClient implements SseClient, AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public <I> Publisher<Event<ByteBuffer<?>>> eventStream(HttpRequest<I> request) {
            return subscriber -> {
                subscriber.onSubscribe(new EmptySubscription());
                subscriber.onComplete();
            };
        }

        @Override
        public <I, B> Publisher<Event<B>> eventStream(HttpRequest<I> request, Argument<B> eventType) {
            return complete();
        }

        @Override
        public <I, B> Publisher<Event<B>> eventStream(HttpRequest<I> request, Argument<B> eventType, Argument<?> errorType) {
            return complete();
        }

        @Override
        public void close() {
            closed.set(true);
        }

        private static <B> Publisher<Event<B>> complete() {
            return subscriber -> {
                subscriber.onSubscribe(new EmptySubscription());
                subscriber.onComplete();
            };
        }
    }

    private static final class EmptySubscription implements Subscription {
        @Override
        public void request(long n) {
        }

        @Override
        public void cancel() {
        }
    }

    @Introspected
    public static final class TestRxInvokerProvider implements RxInvokerProvider<TestRxInvoker> {
        static ExecutorService executorService;

        @Override
        public boolean isProviderFor(Class<?> clazz) {
            return clazz == TestRxInvoker.class;
        }

        @Override
        public TestRxInvoker getRxInvoker(SyncInvoker syncInvoker, ExecutorService executorService) {
            TestRxInvokerProvider.executorService = executorService;
            return new TestRxInvoker();
        }
    }

    public static final class TestRxInvoker implements RxInvoker<CompletionStage<String>> {
        private static final CompletionStage<String> RESULT = CompletableFuture.completedFuture("test");

        @Override
        public CompletionStage<String> get() {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> get(Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> get(GenericType<R> responseType) {
            return RESULT;
        }

        @Override
        public CompletionStage<String> put(Entity<?> entity) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> put(Entity<?> entity, Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> put(Entity<?> entity, GenericType<R> responseType) {
            return RESULT;
        }

        @Override
        public CompletionStage<String> post(Entity<?> entity) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> post(Entity<?> entity, Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> post(Entity<?> entity, GenericType<R> responseType) {
            return RESULT;
        }

        @Override
        public CompletionStage<String> delete() {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> delete(Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> delete(GenericType<R> responseType) {
            return RESULT;
        }

        @Override
        public CompletionStage<String> head() {
            return RESULT;
        }

        @Override
        public CompletionStage<String> options() {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> options(Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> options(GenericType<R> responseType) {
            return RESULT;
        }

        @Override
        public CompletionStage<String> trace() {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> trace(Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> trace(GenericType<R> responseType) {
            return RESULT;
        }

        @Override
        public CompletionStage<String> method(String name) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> method(String name, Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> method(String name, GenericType<R> responseType) {
            return RESULT;
        }

        @Override
        public CompletionStage<String> method(String name, Entity<?> entity) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> method(String name, Entity<?> entity, Class<R> responseType) {
            return RESULT;
        }

        @Override
        public <R> CompletionStage<String> method(String name, Entity<?> entity, GenericType<R> responseType) {
            return RESULT;
        }
    }
}
