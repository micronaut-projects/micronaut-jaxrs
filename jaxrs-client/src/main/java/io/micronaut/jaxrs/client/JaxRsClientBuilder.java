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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.MediaType;
import io.micronaut.http.body.ContextlessMessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.http.body.MessageBodyReader;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.jaxrs.common.JaxRsStandardProviders;
import io.micronaut.jaxrs.common.body.standard.JaxRsInputStreamMessageBodyReader;
import io.micronaut.jaxrs.common.body.standard.JaxRsInputStreamMessageBodyWriter;
import io.micronaut.jaxrs.common.body.standard.JaxRsMultivaluedMapMessageBodyWriter;
import io.micronaut.jaxrs.common.body.standard.JaxRsMultivaluedStringObjectMapMessageBodyReader;
import io.micronaut.jaxrs.common.body.standard.JaxRsMultivaluedStringStringMapMessageBodyReader;
import io.micronaut.jaxrs.common.body.standard.JaxRsReaderMessageBodyReader;
import io.micronaut.jaxrs.common.body.standard.JaxRsReaderMessageBodyWriter;
import io.micronaut.jaxrs.common.body.standard.JaxRsStreamingOutputMessageBodyWriter;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Configuration;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.lang.ref.WeakReference;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The implementation of {@link ClientBuilder}.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
public final class JaxRsClientBuilder extends ClientBuilder implements JaxRsConfigurable<ClientBuilder> {

    // Only for testing
    private static final List<WeakReference<JaxRsClient>> TESTING_CLIENTS = new ArrayList<>();
    private static final int TESTING_MIN_CLIENTS = Optional.ofNullable(System.getProperty("micronaut.testing.jaxrs.min.clients")).map(Integer::parseInt).orElse(-1);

    private final JaxRsConfiguration config = new JaxRsConfiguration();
    @Nullable
    private SSLContext sslContext;
    @Nullable
    private KeyStore keyStore;
    private char @Nullable [] keyStorePassword;
    @Nullable
    private KeyStore trustStore;
    @Nullable
    private HostnameVerifier hostnameVerifier;
    @Nullable
    private ExecutorService executorService;
    @Nullable
    private ScheduledExecutorService scheduledExecutorService;
    @Nullable
    private Duration connectTimeout;
    @Nullable
    private Duration readTimeout;

    @Override
    public Client build() {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(connectTimeout);
        configuration.setReadTimeout(readTimeout);
        // the SSL of this builder, else the one of the HTTP client
        SSLContext clientSslContext = JaxRsClientSsl.sslContext(sslContext, keyStore, keyStorePassword, trustStore, hostnameVerifier);
        if (clientSslContext != null) {
            JaxRsClientSsl.configure(configuration.getSslConfiguration(), clientSslContext, hostnameVerifier);
        }
        // the main HTTP client owns the event loop: the other HTTP clients of the client, e.g. of
        // its event sources, run on it, and it shuts it down when it closes
        DefaultHttpClient httpClient = DefaultHttpClient.builder().configuration(configuration).build();
        Supplier<DefaultHttpClient> httpClients = () -> DefaultHttpClient.builder()
            .configuration(configuration)
            .eventLoopOf(httpClient)
            .build();
        ContextlessMessageBodyHandlerRegistry handlerRegistry = (ContextlessMessageBodyHandlerRegistry) httpClient.getHandlerRegistry();
        // the properties and components registered on this builder, then the standard ones
        JaxRsConfiguration jaxRsConfiguration = config.copy();
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
        jaxRsConfiguration.setHandlerRegistry(httpClient.getHandlerRegistry());
        jaxRsConfiguration.registerStandard(httpClient.getHandlerRegistry().findReader(Argument.STRING, List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.registerStandard(httpClient.getHandlerRegistry().findReader(Argument.of(byte[].class), List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.registerStandard(httpClient.getHandlerRegistry().findWriter(Argument.STRING, List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.registerStandard(httpClient.getHandlerRegistry().findWriter(Argument.of(byte[].class), List.of(MediaType.ALL_TYPE)).get());
        jaxRsConfiguration.registerStandard(new JaxRsReaderMessageBodyWriter<>());
        jaxRsConfiguration.registerStandard(new JaxRsReaderMessageBodyReader());
        jaxRsConfiguration.registerStandard(new JaxRsInputStreamMessageBodyWriter<>());
        jaxRsConfiguration.registerStandard(new JaxRsInputStreamMessageBodyReader());
        jaxRsConfiguration.registerStandard(new JaxRsStreamingOutputMessageBodyWriter<>());
        jaxRsConfiguration.registerStandard(new JaxRsMultivaluedMapMessageBodyWriter());
        jaxRsConfiguration.registerStandard(new JaxRsMultivaluedStringObjectMapMessageBodyReader());
        jaxRsConfiguration.registerStandard(new JaxRsMultivaluedStringStringMapMessageBodyReader());
        // the standard providers of other modules, e.g. File, DataSource, Source and JAXB
        for (JaxRsStandardProviders standardProviders : ServiceLoader.load(JaxRsStandardProviders.class, JaxRsClientBuilder.class.getClassLoader())) {
            standardProviders.providers().forEach(jaxRsConfiguration::registerStandard);
        }

        JaxRsClient client = new JaxRsClient(httpClient, httpClients, jaxRsConfiguration, executorService, scheduledExecutorService,
            clientSslContext, hostnameVerifier);
        if (TESTING_MIN_CLIENTS > 0) {
            TESTING_CLIENTS.removeIf(w -> w.get() == null);
            TESTING_CLIENTS.add(new WeakReference<>(client));
            if (TESTING_CLIENTS.size() > TESTING_MIN_CLIENTS) {
                JaxRsClient oldest = TESTING_CLIENTS.remove(0).get();
                if (oldest != null) {
                    oldest.closeForTesting();
                }
            }
        }
        return client;
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
        this.config.replaceWith(config);
        return this;
    }

    @Override
    public ClientBuilder sslContext(SSLContext sslContext) {
        // it resets the stores set before (JAX-RS, ClientBuilder#sslContext)
        this.sslContext = sslContext;
        this.keyStore = null;
        this.keyStorePassword = null;
        this.trustStore = null;
        return this;
    }

    @Override
    public ClientBuilder keyStore(KeyStore keyStore, char[] password) {
        this.keyStore = keyStore;
        this.keyStorePassword = password;
        // it resets the SSL context set before (JAX-RS, ClientBuilder#keyStore)
        this.sslContext = null;
        return this;
    }

    @Override
    public ClientBuilder trustStore(KeyStore trustStore) {
        this.trustStore = trustStore;
        // it resets the SSL context set before (JAX-RS, ClientBuilder#trustStore)
        this.sslContext = null;
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
