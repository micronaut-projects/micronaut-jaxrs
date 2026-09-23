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

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

/**
 * The implementation of {@link Client}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsClient implements Client, JaxRsConfigurable<Client> {

    private final DefaultHttpClient httpClient;
    private final JaxRsConfiguration config;
    private final @Nullable ExecutorService executorService;
    private volatile boolean closed;

    JaxRsClient(DefaultHttpClient httpClient, JaxRsConfiguration config, @Nullable ExecutorService executorService) {
        this.httpClient = httpClient;
        this.config = config;
        this.executorService = executorService;
    }

    /**
     * @return The executor service of the asynchronous and reactive invocations, the one of the
     * client builder, else a default one
     */
    ExecutorService getExecutorService() {
        return executorService != null ? executorService : ForkJoinPool.commonPool();
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
            httpClient.close();
        }
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
        throw new UnsupportedOperationException();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        throw new UnsupportedOperationException();
    }
}
