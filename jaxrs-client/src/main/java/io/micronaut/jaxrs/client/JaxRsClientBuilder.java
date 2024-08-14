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
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.ContextlessMessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.jaxrs.common.JaxRsInputStreamMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsInputStreamMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsReaderMessageBodyReader;
import io.micronaut.jaxrs.common.JaxRsReaderMessageBodyWriter;
import io.micronaut.jaxrs.common.JaxRsStreamingOutputMessageBodyWriter;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Configuration;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.lang.ref.WeakReference;
import java.net.URI;
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
public final class JaxRsClientBuilder extends ClientBuilder implements JaxRsConfigurable<ClientBuilder> {

    // Only for testing
    private static final List<WeakReference<DefaultHttpClient>> TESTING_CLIENTS = new ArrayList<>();
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
        DefaultHttpClient httpClient = new DefaultHttpClient((URI) null, configuration);
        ContextlessMessageBodyHandlerRegistry handlerRegistry = (ContextlessMessageBodyHandlerRegistry) httpClient.getHandlerRegistry();
        JaxRsConfiguration jaxRsConfiguration = new JaxRsConfiguration();
        httpClient.setHandlerRegistry(new MessageBodyHandlerRegistry() {

            @Override
            public <T> Optional<MessageBodyReader<T>> findReader(@NonNull Argument<T> type, @Nullable List<MediaType> mediaType) {
                return handlerRegistry.findReader(type, mediaType);
            }

            @Override
            public <T> Optional<MessageBodyWriter<T>> findWriter(@NonNull Argument<T> type, @NonNull List<MediaType> mediaType) {
                if (type.getType().equals(Entity.class)) {
                    return Optional.of((MessageBodyWriter<T>) new JaxRsEntityMessageBodyWriter(handlerRegistry, mediaType));
                }
                return handlerRegistry.findWriter(type, mediaType);
            }

        });
        jaxRsConfiguration.register(httpClient.getHandlerRegistry().findReader(Argument.STRING, List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.register(httpClient.getHandlerRegistry().findReader(Argument.of(byte[].class), List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.register(httpClient.getHandlerRegistry().findWriter(Argument.STRING, List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.register(httpClient.getHandlerRegistry().findWriter(Argument.of(byte[].class), List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.register(new JaxRsReaderMessageBodyWriter());
        jaxRsConfiguration.register(new JaxRsReaderMessageBodyWriter());
        jaxRsConfiguration.register(new JaxRsReaderMessageBodyReader());
        jaxRsConfiguration.register(new JaxRsInputStreamMessageBodyWriter<>());
        jaxRsConfiguration.register(new JaxRsInputStreamMessageBodyReader());
        jaxRsConfiguration.register(new JaxRsStreamingOutputMessageBodyWriter<>());

        if (TESTING_MIN_CLIENTS > 0) {
            TESTING_CLIENTS.removeIf(w -> w.get() == null);
            TESTING_CLIENTS.add(new WeakReference<>(httpClient));
            if (TESTING_CLIENTS.size() > TESTING_MIN_CLIENTS) {
                DefaultHttpClient client = TESTING_CLIENTS.remove(0).get();
                if (client != null) {
                    client.close();
                }
            }
        }
        return new JaxRsClient(httpClient, jaxRsConfiguration);
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
//        this.config = config;
        return this;
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
        this.readTimeout = Duration.ofMillis(unit.toMillis(timeout));
        return this;
    }

}
