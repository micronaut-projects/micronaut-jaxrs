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
import io.micronaut.http.client.HttpVersionSelection;
import io.micronaut.http.client.netty.ssl.ClientSslBuilder;
import io.micronaut.http.ssl.SslConfiguration;
import io.micronaut.http.ssl.SslConfigurationException;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * The SSL of a client: the {@link SSLContext} of its builder, or the one of its key store, trust
 * store and host name verifier (JAX-RS, {@code ClientBuilder}), which the HTTP client uses.
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
final class JaxRsClientSsl implements ClientSslBuilder {

    private final SSLContext sslContext;

    JaxRsClientSsl(SSLContext sslContext) {
        this.sslContext = sslContext;
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
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (hostnameVerifier != null) {
                for (int i = 0; i < trustManagers.length; i++) {
                    if (trustManagers[i] instanceof X509ExtendedTrustManager trustManager) {
                        trustManagers[i] = new VerifyingTrustManager(trustManager, hostnameVerifier);
                    }
                }
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new SslConfigurationException(e);
        }
    }

    @Override
    public SslContext build(SslConfiguration ssl, HttpVersionSelection versionSelection) {
        ApplicationProtocolConfig protocols = ApplicationProtocolConfig.DISABLED;
        if (versionSelection.isAlpn()) {
            protocols = new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                versionSelection.getAlpnSupportedProtocols()
            );
        }
        if (versionSelection.isHttp2CipherSuites()) {
            return new JdkSslContext(sslContext, true, Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE,
                protocols, ClientAuth.NONE, null, false);
        }
        return new JdkSslContext(sslContext, true, null, IdentityCipherSuiteFilter.INSTANCE, protocols, ClientAuth.NONE, null, false);
    }

    /**
     * A trust manager whose host name verification is the one of a {@link HostnameVerifier}: the
     * certificates are checked without the endpoint identification of the engine, then the
     * verifier decides on the host.
     */
    private static final class VerifyingTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager delegate;
        private final HostnameVerifier hostnameVerifier;

        VerifyingTrustManager(X509ExtendedTrustManager delegate, HostnameVerifier hostnameVerifier) {
            this.delegate = delegate;
            this.hostnameVerifier = hostnameVerifier;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            verify(engine.getPeerHost(), engine.getHandshakeSession());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
            if (socket instanceof SSLSocket sslSocket) {
                verify(sslSocket.getInetAddress().getHostName(), sslSocket.getHandshakeSession());
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        private void verify(@Nullable String host, @Nullable SSLSession session) throws CertificateException {
            if (host == null || session == null || !hostnameVerifier.verify(host, session)) {
                throw new CertificateException("The host name " + host + " is not verified");
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            delegate.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }
}
