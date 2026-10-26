/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.netty.DefaultHttpClient;
import jakarta.ws.rs.client.Client;
import org.jspecify.annotations.Nullable;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.sse.SseEventSource;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * The implementation of {@link Client}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsClient implements Client, JaxRsConfigurable<Client> {

    private final DefaultHttpClient httpClient;
    private final Supplier<DefaultHttpClient> httpClients;
    private final JaxRsConfiguration config;
    private final @Nullable ExecutorService executorService;
    private final @Nullable ScheduledExecutorService scheduledExecutorService;
    private final @Nullable SSLContext sslContext;
    private final @Nullable HostnameVerifier hostnameVerifier;
    // the event sources opened from the targets of this client, closed with it
    private final Set<SseEventSource> eventSources = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * @param httpClient       The HTTP client
     * @param httpClients      Creates the other HTTP clients of the client, configured like its own
     *                         and on the event loop of its own, which owns it
     * @param config           The configuration
     * @param executorService  The executor service of the builder
     * @param scheduledExecutorService The scheduled executor service of the builder
     * @param sslContext       The SSL context of the builder, {@code null} for the default one
     * @param hostnameVerifier The host name verifier of the builder
     */
    JaxRsClient(DefaultHttpClient httpClient,
                Supplier<DefaultHttpClient> httpClients,
                JaxRsConfiguration config,
                @Nullable ExecutorService executorService,
                @Nullable ScheduledExecutorService scheduledExecutorService,
                @Nullable SSLContext sslContext,
                @Nullable HostnameVerifier hostnameVerifier) {
        this.httpClient = httpClient;
        this.httpClients = httpClients;
        this.config = config;
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
    }

    /**
     * An HTTP client configured like the one of this client, e.g. for an event source, which
     * disconnects by closing it.
     *
     * @return A new HTTP client
     */
    DefaultHttpClient newHttpClient() {
        return httpClients.get();
    }

    /**
     * @return The executor service of the asynchronous and reactive invocations, the one of the
     * client builder, else a default one
     */
    ExecutorService getExecutorService() {
        return executorService != null ? executorService : ForkJoinPool.commonPool();
    }

    /**
     * @return The scheduled executor service of the builder, which schedules e.g. the reconnects of
     * an event source, {@code null} for the default one
     */
    @Nullable ScheduledExecutorService getScheduledExecutorService() {
        return scheduledExecutorService;
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    @Override
    public Client self() {
        return this;
    }

    @Override
    public void close() {
        // closing again has no effect
        if (!closed) {
            closed = true;
            // the event sources and their HTTP clients first: the main HTTP client shuts down the
            // event loop they run on
            for (SseEventSource source : eventSources) {
                source.close();
            }
            eventSources.clear();
            httpClient.close();
        }
    }

    /**
     * Release a client that the tests left open, unless an event source of it is open: closing
     * it would shut down the event loop of the event source.
     */
    void closeForTesting() {
        if (eventSources.isEmpty()) {
            close();
        }
    }

    /**
     * Track an open event source of a target of this client: closing the client closes it.
     *
     * @param source The event source
     */
    void opened(SseEventSource source) {
        eventSources.add(source);
        if (closed && eventSources.remove(source)) {
            // closed meanwhile
            source.close();
        }
    }

    /**
     * @param source An event source that is closed
     */
    void closed(SseEventSource source) {
        eventSources.remove(source);
    }

    /**
     * Fail when the client is closed: its methods and the ones of its web targets cannot be used.
     */
    void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The client is closed");
        }
    }

    @Override
    public JaxRsConfiguration getConfiguration() {
        checkOpen();
        return config;
    }

    @Override
    public JaxRsWebTarget target(String uri) {
        checkOpen();
        return target(UriBuilder.fromUri(Objects.requireNonNull(uri, "uri")));
    }

    @Override
    public JaxRsWebTarget target(URI uri) {
        checkOpen();
        return target(UriBuilder.fromUri(Objects.requireNonNull(uri, "uri")));
    }

    @Override
    public JaxRsWebTarget target(UriBuilder uriBuilder) {
        checkOpen();
        return new JaxRsWebTarget(this, Objects.requireNonNull(uriBuilder, "uriBuilder"), config.copy());
    }

    @Override
    public JaxRsWebTarget target(Link link) {
        checkOpen();
        return target(UriBuilder.fromLink(Objects.requireNonNull(link, "link")));
    }

    @Override
    public Invocation.Builder invocation(Link link) {
        checkOpen();
        Objects.requireNonNull(link, "link");
        Invocation.Builder request = target(UriBuilder.fromLink(link)).request();
        String type = link.getType();
        if (type != null) {
            request = request.accept(type.split(","));
        }
        return request;
    }

    @Override
    public SSLContext getSslContext() {
        if (sslContext != null) {
            return sslContext;
        }
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    @SuppressWarnings("NullAway") // no host name verifier means the default verification
    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }
}
