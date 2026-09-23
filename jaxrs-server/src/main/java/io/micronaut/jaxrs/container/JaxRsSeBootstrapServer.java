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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.common.bootstrap.JaxRsSeBootstrap;
import io.micronaut.jaxrs.common.bootstrap.JaxRsSeConfiguration;
import io.micronaut.reflection.ReflectionAnnotations;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * The Java SE bootstrap of JAX-RS (section 2.3.1): an application context with an embedded
 * server for an {@link Application}, at the host, port and root path of the configuration.
 *
 * <p>The resources of the application are served by the routes the annotation processors
 * generated for them, as in any application.</p>
 *
 * @author Denis Stepanov
 * @since 5.0.0
 */
@Internal
public final class JaxRsSeBootstrapServer implements JaxRsSeBootstrap {

    private static final String HTTP = "HTTP";
    private static final String HTTPS = "HTTPS";

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration configuration) {
        return started(() -> start(application, null, configuration));
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> applicationClass, SeBootstrap.Configuration configuration) {
        return started(() -> start(null, applicationClass, configuration));
    }

    /**
     * The server is started on the calling thread, like {@code Micronaut.run}: the environment is
     * deduced from it, e.g. the test environment with a random default port.
     */
    private static CompletionStage<SeBootstrap.Instance> started(Supplier<SeBootstrap.Instance> start) {
        try {
            return CompletableFuture.completedFuture(start.get());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private SeBootstrap.Instance start(@Nullable Application application,
                                       @Nullable Class<? extends Application> applicationClass,
                                       SeBootstrap.Configuration configuration) {
        String protocol = configuration.protocol();
        boolean https = HTTPS.equalsIgnoreCase(protocol);
        if (!https && !HTTP.equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("The protocol " + protocol + " is not supported, only HTTP and HTTPS are");
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put("micronaut.server.host", configuration.host());
        String portProperty = https ? "micronaut.server.ssl.port" : "micronaut.server.port";
        int port = configuration.port();
        if (port == SeBootstrap.Configuration.FREE_PORT) {
            properties.put(portProperty, -1);
        } else if (port != SeBootstrap.Configuration.DEFAULT_PORT) {
            properties.put(portProperty, port);
        }
        if (https) {
            properties.put("micronaut.server.ssl.enabled", true);
        }
        String rootPath = configuration.rootPath();
        if (rootPath != null && !rootPath.isEmpty() && !"/".equals(rootPath)) {
            properties.put("micronaut.server.context-path", rootPath);
        }
        if (applicationClass != null) {
            // the bean of the class, else the class instantiated, see JaxRsApplicationFactory
            properties.put(JaxRsApplicationFactory.APPLICATION, applicationClass.getName());
        }
        ApplicationContext context = ApplicationContext.builder().properties(properties).build();
        if (application != null) {
            register(context, application, Application.class);
            for (Object singleton : application.getSingletons()) {
                // the resources and providers of the application are its instances (JAX-RS 2.3)
                register(context, singleton, null);
            }
        }
        try {
            context.start();
            EmbeddedServer server = context.getBean(EmbeddedServer.class);
            if (!server.isRunning()) {
                server.start();
            }
            Map<String, Object> actual = new HashMap<>();
            actual.put(SeBootstrap.Configuration.PROTOCOL, protocol);
            actual.put(SeBootstrap.Configuration.HOST, configuration.host());
            actual.put(SeBootstrap.Configuration.PORT, server.getPort());
            actual.put(SeBootstrap.Configuration.ROOT_PATH, rootPath == null || rootPath.isEmpty() ? "/" : rootPath);
            actual.put(SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION, configuration.sslClientAuthentication());
            return new Instance(context, server, new JaxRsSeConfiguration(actual));
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    /**
     * Register an instance as the bean of its class, replacing the one the annotation processors
     * generated, whose constructor may not be injectable.
     *
     * @param exposed The type the instance is also the primary bean of, e.g. the application of
     *                the bootstrap, whichever other ones are beans
     */
    @SuppressWarnings("unchecked")
    private static <T> void register(ApplicationContext context, T instance, @Nullable Class<? super T> exposed) {
        Class<T> type = (Class<T>) instance.getClass();
        // the annotations of the class, e.g. its @ApplicationPath
        AnnotationMetadata metadata = ReflectionAnnotations.metadataOf(type);
        RuntimeBeanDefinition.Builder<T> definition = RuntimeBeanDefinition.builder(type, () -> instance)
            .singleton(true)
            .replaces(type);
        if (exposed != null) {
            definition.exposedTypes(type, exposed);
            metadata = ReflectionAnnotations.merge(metadata, ReflectionAnnotations.declaring(Primary.class));
        }
        context.registerBeanDefinition(definition.annotationMetadata(metadata).build());
    }

    /**
     * The running server.
     *
     * @param context       The application context
     * @param server        The server
     * @param configuration The configuration it runs with
     */
    private record Instance(ApplicationContext context,
                            EmbeddedServer server,
                            SeBootstrap.Configuration configuration) implements SeBootstrap.Instance {

        @Override
        public CompletionStage<StopResult> stop() {
            return CompletableFuture.supplyAsync(() -> {
                context.close();
                return new StopResult() {
                    @Override
                    public <T> T unwrap(Class<T> nativeClass) {
                        return Instance.this.unwrap(nativeClass);
                    }
                };
            });
        }

        @Override
        public <T> T unwrap(Class<T> nativeClass) {
            if (nativeClass.isInstance(context)) {
                return nativeClass.cast(context);
            }
            if (nativeClass.isInstance(server)) {
                return nativeClass.cast(server);
            }
            throw new IllegalArgumentException("The instance is not a " + nativeClass.getName());
        }
    }
}
