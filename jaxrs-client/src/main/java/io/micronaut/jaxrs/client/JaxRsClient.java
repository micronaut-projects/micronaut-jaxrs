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
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The implementation of {@link Client}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsClient implements Client, JaxRsConfigurable<Client> {

    private final DefaultHttpClient httpClient;
    private final JaxRsConfiguration config;
    private final List<AutoCloseable> closeables = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    JaxRsClient(DefaultHttpClient httpClient, JaxRsConfiguration config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    HttpClient getHttpClient() {
        checkOpen();
        return httpClient;
    }

    @Override
    public Client self() {
        checkOpen();
        return this;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            for (AutoCloseable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Continue closing the underlying HTTP client.
                }
            }
            closeables.clear();
            httpClient.close();
        }
    }

    @Override
    public JaxRsConfiguration getConfiguration() {
        checkOpen();
        return config;
    }

    @Override
    public JaxRsWebTarget target(String uri) {
        Objects.requireNonNull(uri, "URI cannot be null");
        checkOpen();
        return target(UriBuilder.fromUri(uri));
    }

    @Override
    public JaxRsWebTarget target(URI uri) {
        Objects.requireNonNull(uri, "URI cannot be null");
        checkOpen();
        return target(UriBuilder.fromUri(uri));
    }

    @Override
    public JaxRsWebTarget target(UriBuilder uriBuilder) {
        Objects.requireNonNull(uriBuilder, "URI builder cannot be null");
        checkOpen();
        return new JaxRsWebTarget(this, uriBuilder, config.copy());
    }

    @Override
    public JaxRsWebTarget target(Link link) {
        Objects.requireNonNull(link, "Link cannot be null");
        checkOpen();
        return target(UriBuilder.fromLink(link));
    }

    @Override
    public Invocation.Builder invocation(Link link) {
        Objects.requireNonNull(link, "Link cannot be null");
        checkOpen();
        Invocation.Builder request = target(UriBuilder.fromLink(link)).request();
        String type = link.getType();
        if (type != null) {
            request = request.accept(type.split(","));
        }
        return request;
    }

    @Override
    public SSLContext getSslContext() {
        checkOpen();
        throw new UnsupportedOperationException();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        checkOpen();
        throw new UnsupportedOperationException();
    }

    void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Client is closed");
        }
    }

    void registerCloseable(AutoCloseable closeable) {
        checkOpen();
        closeables.add(closeable);
    }
}
