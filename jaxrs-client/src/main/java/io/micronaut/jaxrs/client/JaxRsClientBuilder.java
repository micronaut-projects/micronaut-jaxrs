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
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import io.micronaut.jaxrs.common.JaxRsInputStreamMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsInputStreamMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsReaderMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsReaderMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsStreamingOutputMessageBodyWriter;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configuration;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.lang.ref.WeakReference;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The implementation of {@link ClientBuilder}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public sealed class JaxRsClientBuilder extends ClientBuilder implements JaxRsConfigurable<ClientBuilder> permits NettyJaxRsClientBuilder {

    // Only for testing
    private static final List<WeakReference<HttpClient>> TESTING_CLIENTS = new ArrayList<>();
    private static final int TESTING_MIN_CLIENTS = Optional.ofNullable(System.getProperty("micronaut.testing.jaxrs.min.clients")).map(Integer::parseInt).orElse(-1);

    private JaxRsConfiguration config;
    private SSLContext sslContext;
    private Map<KeyStore, char[]> keyStores = new HashMap<>();
    private KeyStore trustStore;
    private HostnameVerifier hostnameVerifier;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private Duration connectTimeout;
    private Duration readTimeout;

    @Override
    public Client build() {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(connectTimeout);
        configuration.setReadTimeout(readTimeout);
        HttpClient httpClient = HttpClient.create(null, configuration);
        JaxRsConfiguration jaxRsConfiguration = new JaxRsConfiguration();
        registerConfiguration(httpClient, jaxRsConfiguration);
        if (TESTING_MIN_CLIENTS > 0) {
            TESTING_CLIENTS.removeIf(w -> w.get() == null);
            TESTING_CLIENTS.add(new WeakReference<>(httpClient));
            if (TESTING_CLIENTS.size() > TESTING_MIN_CLIENTS) {
                HttpClient client = TESTING_CLIENTS.remove(0).get();
                if (client != null) {
                    client.close();
                }
            }
        }
        return new JaxRsClient(httpClient, jaxRsConfiguration);
    }

    protected void registerConfiguration(HttpClient httpClient, JaxRsConfiguration jaxRsConfiguration) {
        jaxRsConfiguration.register(new JaxRsReaderMessageBodyWriter());
        jaxRsConfiguration.register(new JaxRsReaderMessageBodyWriter());
        jaxRsConfiguration.register(new JaxRsReaderMessageBodyReader());
        jaxRsConfiguration.register(new JaxRsInputStreamMessageBodyWriter<>());
        jaxRsConfiguration.register(new JaxRsInputStreamMessageBodyReader());
        jaxRsConfiguration.register(new JaxRsStreamingOutputMessageBodyWriter<>());
    }

    @Override
    public ClientBuilder self() {
        return this;
    }

    @Override
    public JaxRsConfiguration getConfiguration() {
        return config;
    }

    @Override
    public ClientBuilder withConfig(Configuration config) {
        throw new IllegalStateException("Not supported");
    }

    @Override
    public ClientBuilder sslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    @Override
    public ClientBuilder keyStore(KeyStore keyStore, char[] password) {
        keyStores.put(keyStore, password);
        return this;
    }

    @Override
    public ClientBuilder trustStore(KeyStore trustStore) {
        this.trustStore = trustStore;
        return this;
    }

    @Override
    public ClientBuilder hostnameVerifier(HostnameVerifier verifier) {
        this.hostnameVerifier = verifier;
        return this;
    }

    @Override
    public ClientBuilder executorService(ExecutorService executorService) {
        this.executorService = executorService;
        return this;
    }

    @Override
    public ClientBuilder scheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
        return this;
    }

    @Override
    public ClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        this.connectTimeout = Duration.ofMillis(unit.toMillis(timeout));
        return this;
    }

    @Override
    public ClientBuilder readTimeout(long timeout, TimeUnit unit) {
        this.readTimeout = Duration.of(timeout, unit.toChronoUnit());
        return this;
    }
}
