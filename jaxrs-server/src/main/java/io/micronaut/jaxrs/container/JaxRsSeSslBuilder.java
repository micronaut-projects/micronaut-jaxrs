/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.jaxrs.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpVersion;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.http.server.netty.ssl.ServerSslBuilder;
import io.micronaut.http.ssl.ServerSslConfiguration;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.IdentityCipherSuiteFilter;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import jakarta.ws.rs.SeBootstrap;

import javax.net.ssl.SSLContext;
import java.util.Map;
import java.util.Optional;

/**
 * The SSL of the Netty server of the Java SE bootstrap: the {@link SSLContext} of its
 * configuration, with its client authentication.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsSeSslBuilder implements ServerSslBuilder {

    private final ApplicationContext context;
    private final SSLContext sslContext;
    private final ClientAuth clientAuth;

    private JaxRsSeSslBuilder(ApplicationContext context, SSLContext sslContext, ClientAuth clientAuth) {
        this.context = context;
        this.sslContext = sslContext;
        this.clientAuth = clientAuth;
    }

    /**
     * Register the builder of the SSL of the server, the primary one. Only called when the
     * Netty server is on the classpath: this class is loaded with its types.
     *
     * @param context        The context of the server
     * @param sslContext     The SSL context of the configuration
     * @param authentication The client authentication of the configuration
     */
    static void register(ApplicationContext context, SSLContext sslContext, SeBootstrap.Configuration.SSLClientAuthentication authentication) {
        ClientAuth clientAuth = switch (authentication) {
            case NONE -> ClientAuth.NONE;
            case OPTIONAL -> ClientAuth.OPTIONAL;
            case MANDATORY -> ClientAuth.REQUIRE;
        };
        MutableAnnotationMetadata metadata = new MutableAnnotationMetadata();
        // over the builders of the configured key stores
        metadata.addDeclaredAnnotation(Primary.class.getName(), Map.of());
        context.registerBeanDefinition(RuntimeBeanDefinition.builder(ServerSslBuilder.class, () -> new JaxRsSeSslBuilder(context, sslContext, clientAuth))
            .singleton(true)
            .annotationMetadata(metadata)
            .build());
    }

    @Override
    public ServerSslConfiguration getSslConfiguration() {
        return context.getBean(ServerSslConfiguration.class);
    }

    @Override
    public Optional<SslContext> build() {
        ApplicationProtocolConfig protocols = ApplicationProtocolConfig.DISABLED;
        if (context.findBean(HttpServerConfiguration.class).map(HttpServerConfiguration::getHttpVersion).orElse(null) == HttpVersion.HTTP_2_0) {
            protocols = new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
        }
        return Optional.of(new JdkSslContext(sslContext, false, null, IdentityCipherSuiteFilter.INSTANCE, protocols, clientAuth, null, false));
    }
}
