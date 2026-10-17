package io.micronaut.jaxrs.container;

import jakarta.ws.rs.SeBootstrap;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Java SE bootstrap serves HTTPS with the {@link SSLContext} of its configuration, and its
 * client authentication.
 */
class SeBootstrapSslTest {

    private static final char[] PASSWORD = "password".toCharArray();

    private static KeyStore keyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = SeBootstrapSslTest.class.getResourceAsStream("/se-bootstrap-keystore.p12")) {
            keyStore.load(in, PASSWORD);
        }
        return keyStore;
    }

    /**
     * @param server Whether it is the context of the server, else of a client that trusts it
     * @param key    Whether a client presents the key
     */
    private static SSLContext sslContext(boolean server, boolean key) throws Exception {
        KeyStore keyStore = keyStore();
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, PASSWORD);
        // the server trusts its own certificate as the one of a client
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keyStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(server || key ? keys.getKeyManagers() : null, trust.getTrustManagers(), null);
        return context;
    }

    private static SeBootstrap.Instance start(SeBootstrap.Configuration.SSLClientAuthentication authentication) throws Exception {
        SeBootstrap.Configuration requested = SeBootstrap.Configuration.builder()
            .protocol("HTTPS")
            .port(SeBootstrap.Configuration.FREE_PORT)
            .sslContext(sslContext(true, false))
            .sslClientAuthentication(authentication)
            .build();
        System.setProperty("spec.name", "SeBootstrapTest");
        try {
            return SeBootstrap.start(new SeBootstrapTest.SeApplication("over https"), requested).toCompletableFuture().get();
        } finally {
            System.clearProperty("spec.name");
        }
    }

    private static String get(SeBootstrap.Instance instance, boolean key) throws Exception {
        URI uri = instance.configuration().baseUriBuilder().path("se/resource").build();
        try (HttpClient client = HttpClient.newBuilder().sslContext(sslContext(false, key)).build()) {
            return client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    @Test
    void servesWithTheSslContextOfTheConfiguration() throws Exception {
        SeBootstrap.Instance instance = start(SeBootstrap.Configuration.SSLClientAuthentication.NONE);
        try {
            assertEquals("HTTPS", instance.configuration().protocol());
            assertEquals("over https", get(instance, false));
        } finally {
            instance.stop().toCompletableFuture().get();
        }
    }

    @Test
    void mandatoryClientAuthenticationRejectsAClientWithoutCertificate() throws Exception {
        SeBootstrap.Instance instance = start(SeBootstrap.Configuration.SSLClientAuthentication.MANDATORY);
        try {
            assertEquals(SeBootstrap.Configuration.SSLClientAuthentication.MANDATORY, instance.configuration().sslClientAuthentication());
            assertEquals("over https", get(instance, true));
            assertThrows(IOException.class, () -> get(instance, false));
        } finally {
            instance.stop().toCompletableFuture().get();
        }
    }
}
