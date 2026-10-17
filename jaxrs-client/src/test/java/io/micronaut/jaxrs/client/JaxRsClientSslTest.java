package io.micronaut.jaxrs.client;

import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Property(name = "spec.name", value = "JaxRsClient")
@Property(name = "micronaut.server.ssl.enabled", value = "true")
@Property(name = "micronaut.server.ssl.build-self-signed", value = "true")
@Property(name = "micronaut.server.ssl.port", value = "-1")
@MicronautTest
class JaxRsClientSslTest {

    @Inject
    EmbeddedServer server;

    @Test
    void theDefaultClientDoesNotTrustTheServer() {
        assertThrows(ProcessingException.class, () -> get(ClientBuilder.newBuilder()));
    }

    @Test
    void sslContext() throws Exception {
        SSLContext sslContext = trustAll();
        ClientBuilder builder = ClientBuilder.newBuilder().sslContext(sslContext);

        assertEquals("hello", get(builder));
        try (Client client = builder.build()) {
            assertSame(sslContext, client.getSslContext());
        }
    }

    @Test
    void trustStoreAndHostnameVerifier() throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("server", serverCertificate());
        List<String> verified = new CopyOnWriteArrayList<>();
        HostnameVerifier accepting = (host, session) -> verified.add(host);

        assertEquals("hello", get(ClientBuilder.newBuilder().trustStore(trustStore).hostnameVerifier(accepting)));
        assertEquals(List.of(server.getHost()), verified);

        HostnameVerifier rejecting = (host, session) -> false;
        assertThrows(ProcessingException.class, () -> get(ClientBuilder.newBuilder().trustStore(trustStore).hostnameVerifier(rejecting)));
        try (Client client = ClientBuilder.newBuilder().trustStore(trustStore).hostnameVerifier(rejecting).build()) {
            assertSame(rejecting, client.getHostnameVerifier());
        }
    }

    @Test
    void storesResetTheSslContextAndBack() throws Exception {
        KeyStore emptyTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        emptyTrustStore.load(null, null);
        SSLContext sslContext = trustAll();

        // a trust store set after the SSL context replaces it: the server is not trusted
        assertThrows(ProcessingException.class, () -> get(ClientBuilder.newBuilder().sslContext(sslContext).trustStore(emptyTrustStore)));
        try (Client client = ClientBuilder.newBuilder().sslContext(sslContext).trustStore(emptyTrustStore).build()) {
            assertNotSame(sslContext, client.getSslContext());
        }
        // and an SSL context set after the trust store replaces it
        assertEquals("hello", get(ClientBuilder.newBuilder().trustStore(emptyTrustStore).sslContext(sslContext)));
    }

    private String get(ClientBuilder builder) {
        return withClient(builder, client -> client.target(server.getURI()).path("client/hello").request().get(String.class));
    }

    private static <T> T withClient(ClientBuilder builder, Function<Client, T> function) {
        try (Client client = builder.build()) {
            return function.apply(client);
        }
    }

    private Certificate serverCertificate() throws Exception {
        try (SSLSocket socket = (SSLSocket) trustAll().getSocketFactory().createSocket(server.getHost(), server.getPort())) {
            socket.startHandshake();
            return socket.getSession().getPeerCertificates()[0];
        }
    }

    private static SSLContext trustAll() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        return sslContext;
    }
}
