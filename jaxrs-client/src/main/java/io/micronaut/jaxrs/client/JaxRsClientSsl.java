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
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.BiFunction;

/**
 * The SSL of a client: the {@link SSLContext} of its builder, or the one of its key store, trust
 * store and host name verifier (JAX-RS, {@code ClientBuilder}), which the HTTP client connects
 * with.
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
     * the host instead of the endpoint identification of the HTTP client.
     *
     * @param ssl              The SSL configuration of the HTTP client
     * @param sslContext       The SSL context
     * @param provided         Whether the context is the one of the builder, else the one built
     *                         by {@link #sslContext}, which verifies the host already
     * @param hostnameVerifier The host name verifier
     */
    static void configure(SslConfiguration ssl,
                          SSLContext sslContext,
                          boolean provided,
                          @Nullable HostnameVerifier hostnameVerifier) {
        SSLContext context = sslContext;
        if (hostnameVerifier != null) {
            if (ssl instanceof AbstractClientSslConfiguration clientSsl) {
                clientSsl.setEndpointIdentification(false);
            }
            if (provided) {
                // its trust managers are the ones of the user: the host is verified after the handshake
                context = new VerifyingSslContext(sslContext, hostnameVerifier);
            }
        }
        ssl.setSslContext(context);
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

    /**
     * Verify the host name of a session with a host name verifier.
     */
    private static boolean verified(HostnameVerifier hostnameVerifier, @Nullable String host, @Nullable SSLSession session) {
        return host != null && session != null && hostnameVerifier.verify(host, session);
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
            if (!verified(hostnameVerifier, host, session)) {
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

    /**
     * An SSL context whose engines verify the host name with a host name verifier once their
     * handshake is finished.
     */
    private static final class VerifyingSslContext extends SSLContext {

        VerifyingSslContext(SSLContext delegate, HostnameVerifier hostnameVerifier) {
            super(new VerifyingSslContextSpi(delegate, hostnameVerifier), delegate.getProvider(), delegate.getProtocol());
        }
    }

    private static final class VerifyingSslContextSpi extends SSLContextSpi {

        private final SSLContext delegate;
        private final HostnameVerifier hostnameVerifier;

        VerifyingSslContextSpi(SSLContext delegate, HostnameVerifier hostnameVerifier) {
            this.delegate = delegate;
            this.hostnameVerifier = hostnameVerifier;
        }

        @Override
        protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom sr) {
            throw new UnsupportedOperationException("The SSL context is initialized already");
        }

        @Override
        protected SSLSocketFactory engineGetSocketFactory() {
            return delegate.getSocketFactory();
        }

        @Override
        protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return delegate.getServerSocketFactory();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine() {
            SSLEngine engine = delegate.createSSLEngine();
            return new VerifyingSslEngine(engine, hostnameVerifier);
        }

        @Override
        protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return new VerifyingSslEngine(delegate.createSSLEngine(host, port), hostnameVerifier);
        }

        @Override
        protected SSLSessionContext engineGetServerSessionContext() {
            return delegate.getServerSessionContext();
        }

        @Override
        protected SSLSessionContext engineGetClientSessionContext() {
            return delegate.getClientSessionContext();
        }

        @Override
        protected SSLParameters engineGetDefaultSSLParameters() {
            return delegate.getDefaultSSLParameters();
        }

        @Override
        protected SSLParameters engineGetSupportedSSLParameters() {
            return delegate.getSupportedSSLParameters();
        }
    }

    /**
     * An engine that fails its handshake when the host name verifier rejects the host of the
     * session.
     */
    private static final class VerifyingSslEngine extends SSLEngine {

        private final SSLEngine delegate;
        private final HostnameVerifier hostnameVerifier;
        private volatile boolean verified;

        VerifyingSslEngine(SSLEngine delegate, HostnameVerifier hostnameVerifier) {
            super(delegate.getPeerHost(), delegate.getPeerPort());
            this.delegate = delegate;
            this.hostnameVerifier = hostnameVerifier;
        }

        private SSLEngineResult checked(SSLEngineResult result) throws SSLException {
            if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED && !verified && delegate.getUseClientMode()) {
                // once per session: the handshake may finish on both a wrap and an unwrap
                if (!verified(hostnameVerifier, delegate.getPeerHost(), delegate.getSession())) {
                    delegate.closeOutbound();
                    throw new SSLHandshakeException("The host name " + delegate.getPeerHost() + " is not verified");
                }
                verified = true;
            }
            return result;
        }

        @Override
        public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int length, ByteBuffer dst) throws SSLException {
            return checked(delegate.wrap(srcs, offset, length, dst));
        }

        @Override
        public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts, int offset, int length) throws SSLException {
            return checked(delegate.unwrap(src, dsts, offset, length));
        }

        @Override
        public Runnable getDelegatedTask() {
            return delegate.getDelegatedTask();
        }

        @Override
        public void closeInbound() throws SSLException {
            delegate.closeInbound();
        }

        @Override
        public boolean isInboundDone() {
            return delegate.isInboundDone();
        }

        @Override
        public void closeOutbound() {
            delegate.closeOutbound();
        }

        @Override
        public boolean isOutboundDone() {
            return delegate.isOutboundDone();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public String[] getEnabledCipherSuites() {
            return delegate.getEnabledCipherSuites();
        }

        @Override
        public void setEnabledCipherSuites(String[] suites) {
            delegate.setEnabledCipherSuites(suites);
        }

        @Override
        public String[] getSupportedProtocols() {
            return delegate.getSupportedProtocols();
        }

        @Override
        public String[] getEnabledProtocols() {
            return delegate.getEnabledProtocols();
        }

        @Override
        public void setEnabledProtocols(String[] protocols) {
            delegate.setEnabledProtocols(protocols);
        }

        @Override
        public SSLSession getSession() {
            return delegate.getSession();
        }

        @Override
        public SSLSession getHandshakeSession() {
            return delegate.getHandshakeSession();
        }

        @Override
        public void beginHandshake() throws SSLException {
            delegate.beginHandshake();
        }

        @Override
        public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
            return delegate.getHandshakeStatus();
        }

        @Override
        public void setUseClientMode(boolean mode) {
            delegate.setUseClientMode(mode);
        }

        @Override
        public boolean getUseClientMode() {
            return delegate.getUseClientMode();
        }

        @Override
        public void setNeedClientAuth(boolean need) {
            delegate.setNeedClientAuth(need);
        }

        @Override
        public boolean getNeedClientAuth() {
            return delegate.getNeedClientAuth();
        }

        @Override
        public void setWantClientAuth(boolean want) {
            delegate.setWantClientAuth(want);
        }

        @Override
        public boolean getWantClientAuth() {
            return delegate.getWantClientAuth();
        }

        @Override
        public void setEnableSessionCreation(boolean flag) {
            delegate.setEnableSessionCreation(flag);
        }

        @Override
        public boolean getEnableSessionCreation() {
            return delegate.getEnableSessionCreation();
        }

        @Override
        public SSLParameters getSSLParameters() {
            return delegate.getSSLParameters();
        }

        @Override
        public void setSSLParameters(SSLParameters params) {
            delegate.setSSLParameters(params);
        }

        @Override
        public String getApplicationProtocol() {
            return delegate.getApplicationProtocol();
        }

        @Override
        public String getHandshakeApplicationProtocol() {
            return delegate.getHandshakeApplicationProtocol();
        }

        @Override
        public void setHandshakeApplicationProtocolSelector(BiFunction<SSLEngine, List<String>, String> selector) {
            // the selector sees this engine
            delegate.setHandshakeApplicationProtocolSelector(selector == null ? null : (engine, protocols) -> selector.apply(this, protocols));
        }

        @Override
        public BiFunction<SSLEngine, List<String>, String> getHandshakeApplicationProtocolSelector() {
            return delegate.getHandshakeApplicationProtocolSelector();
        }
    }
}
