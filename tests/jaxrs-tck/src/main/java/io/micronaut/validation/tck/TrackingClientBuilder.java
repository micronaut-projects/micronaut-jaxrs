package io.micronaut.validation.tck;

import io.micronaut.jaxrs.client.JaxRsClientBuilder;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configuration;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TCK-only client builder that tracks clients created by tests so leaked
 * clients can be closed without adding test hooks to production code.
 */
public final class TrackingClientBuilder extends ClientBuilder {
    private static final List<Client> CLIENTS = new ArrayList<>();

    private final ClientBuilder delegate = new JaxRsClientBuilder();

    static int markClients() {
        synchronized (CLIENTS) {
            return CLIENTS.size();
        }
    }

    static void closeClientsAfter(int marker) {
        synchronized (CLIENTS) {
            int boundedMarker = Math.max(0, Math.min(marker, CLIENTS.size()));
            for (int i = CLIENTS.size() - 1; i >= boundedMarker; i--) {
                close(CLIENTS.remove(i));
            }
        }
    }

    static void closeClients() {
        closeClientsAfter(0);
    }

    private static void close(Client client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // A TCK assertion may already have intentionally closed the client.
        }
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
    public Client build() {
        Client client = delegate.build();
        synchronized (CLIENTS) {
            CLIENTS.add(client);
        }
        return client;
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
