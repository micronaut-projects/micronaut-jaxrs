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
import io.micronaut.http.ssl.AbstractClientSslConfiguration;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.http.ssl.SslConfigurationException;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * The SSL of a client: the {@link SSLContext} of its builder, or the one of its key store and
 * trust store, and its host name verifier (JAX-RS, {@code ClientBuilder}), which the HTTP client
 * connects with.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsClientSsl {

    private JaxRsClientSsl() {
    }

    /**
     * Let the HTTP client connect with an SSL context. With a host name verifier, it decides on
     * the host once the handshake completes, instead of the endpoint identification of the HTTP
     * client.
     *
     * @param ssl              The SSL configuration of the HTTP client
     * @param sslContext       The SSL context
     * @param hostnameVerifier The host name verifier
     */
    static void configure(SslConfiguration ssl,
                          SSLContext sslContext,
                          @Nullable HostnameVerifier hostnameVerifier) {
        if (hostnameVerifier != null && ssl instanceof AbstractClientSslConfiguration clientSsl) {
            clientSsl.setEndpointIdentification(false);
            clientSsl.setHostnameVerifier(hostnameVerifier);
        }
        ssl.setSslContext(sslContext);
    }

    /**
     * The SSL context of a client builder.
     *
     * @param sslContext       The SSL context of the builder
     * @param keyStore         The key store
     * @param keyStorePassword The password of the key store
     * @param trustStore       The trust store
     * @param hostnameVerifier The host name verifier
     * @return The SSL context, or {@code null} when the builder configured none: the one of the
     * HTTP client is used
     */
    static @Nullable SSLContext sslContext(@Nullable SSLContext sslContext,
                                           @Nullable KeyStore keyStore,
                                           char @Nullable [] keyStorePassword,
                                           @Nullable KeyStore trustStore,
                                           @Nullable HostnameVerifier hostnameVerifier) {
        if (sslContext != null) {
            // the SSL context wins over the stores (JAX-RS, ClientBuilder#sslContext)
            return sslContext;
        }
        if (keyStore == null && trustStore == null && hostnameVerifier == null) {
            return null;
        }
        try {
            KeyManager[] keyManagers = null;
            if (keyStore != null) {
                KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, keyStorePassword);
                keyManagers = keyManagerFactory.getKeyManagers();
            }
            // the trust store, else the one of the JDK
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagerFactory.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new SslConfigurationException(e);
        }
    }
}
