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

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configuration;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.lang.ref.WeakReference;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The client builder of the TCK, which its service file selects before the one of the client:
 * the TCK leaves most of its clients open, so the builder closes the oldest of
 * them once more than {@link #KEPT_CLIENTS} were built, unless an event source of it is open.
 */
public final class TckClientBuilder extends ClientBuilder {

    /**
     * The number of the newest clients that are kept open.
     */
    static final int KEPT_CLIENTS = 5;

    private static final List<WeakReference<JaxRsClient>> CLIENTS = new ArrayList<>();

    private final JaxRsClientBuilder delegate = new JaxRsClientBuilder();

    @Override
    public Client build() {
        JaxRsClient client = (JaxRsClient) delegate.build();
        JaxRsClient oldest = null;
        synchronized (CLIENTS) {
            CLIENTS.removeIf(w -> w.get() == null);
            CLIENTS.add(new WeakReference<>(client));
            if (CLIENTS.size() > KEPT_CLIENTS) {
                oldest = CLIENTS.remove(0).get();
            }
        }
        // closing it would shut down the event loop of its event sources
        if (oldest != null && !oldest.hasOpenEventSources()) {
            oldest.close();
        }
        return client;
    }

    @Override
    public ClientBuilder withConfig(Configuration config) {
        delegate.withConfig(config);
        return this;
    }

    @Override
    public ClientBuilder sslContext(SSLContext sslContext) {
        delegate.sslContext(sslContext);
        return this;
    }

    @Override
    public ClientBuilder keyStore(KeyStore keyStore, char[] password) {
        delegate.keyStore(keyStore, password);
        return this;
    }

    @Override
    public ClientBuilder trustStore(KeyStore trustStore) {
        delegate.trustStore(trustStore);
        return this;
    }

    @Override
    public ClientBuilder hostnameVerifier(HostnameVerifier verifier) {
        delegate.hostnameVerifier(verifier);
        return this;
    }

    @Override
    public ClientBuilder executorService(ExecutorService executorService) {
        delegate.executorService(executorService);
        return this;
    }

    @Override
    public ClientBuilder scheduledExecutorService(ScheduledExecutorService scheduledExecutorService) {
        delegate.scheduledExecutorService(scheduledExecutorService);
        return this;
    }

    @Override
    public ClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        delegate.connectTimeout(timeout, unit);
        return this;
    }

    @Override
    public ClientBuilder readTimeout(long timeout, TimeUnit unit) {
        delegate.readTimeout(timeout, unit);
        return this;
    }

    @Override
    public Configuration getConfiguration() {
        return delegate.getConfiguration();
    }

    @Override
    public ClientBuilder property(String name, Object value) {
        delegate.property(name, value);
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass) {
        delegate.register(componentClass);
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, int priority) {
        delegate.register(componentClass, priority);
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Class<?>... contracts) {
        delegate.register(componentClass, contracts);
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        delegate.register(componentClass, contracts);
        return this;
    }

    @Override
    public ClientBuilder register(Object component) {
        delegate.register(component);
        return this;
    }

    @Override
    public ClientBuilder register(Object component, int priority) {
        delegate.register(component, priority);
        return this;
    }

    @Override
    public ClientBuilder register(Object component, Class<?>... contracts) {
        delegate.register(component, contracts);
        return this;
    }

    @Override
    public ClientBuilder register(Object component, Map<Class<?>, Integer> contracts) {
        delegate.register(component, contracts);
        return this;
    }
}
