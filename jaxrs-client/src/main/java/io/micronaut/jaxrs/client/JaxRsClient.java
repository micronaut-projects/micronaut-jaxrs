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
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.net.URI;

/**
 * The implementation of {@link Client}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsClient implements Client, JaxRsConfigurable<Client> {

    private final HttpClient httpClient;
    private final JaxRsConfiguration config;

    JaxRsClient(HttpClient httpClient, JaxRsConfiguration config) {
        this.httpClient = httpClient;
        this.config = config;
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
        httpClient.close();
    }

    @Override
    public JaxRsConfiguration getConfiguration() {
        return config;
    }

    @Override
    public JaxRsWebTarget target(String uri) {
        return target(UriBuilder.fromUri(uri));
    }

    @Override
    public JaxRsWebTarget target(URI uri) {
        return target(UriBuilder.fromUri(uri));
    }

    @Override
    public JaxRsWebTarget target(UriBuilder uriBuilder) {
        return new JaxRsWebTarget(this, uriBuilder, config.copy());
    }

    @Override
    public JaxRsWebTarget target(Link link) {
        return target(UriBuilder.fromLink(link));
    }

    @Override
    public Invocation.Builder invocation(Link link) {
        return target(UriBuilder.fromLink(link)).request();
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
