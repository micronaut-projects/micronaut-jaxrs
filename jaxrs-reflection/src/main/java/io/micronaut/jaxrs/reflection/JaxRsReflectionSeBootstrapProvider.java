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
package io.micronaut.jaxrs.reflection;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.common.JaxRsSeBootstrapProvider;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Reflection-backed fallback for Jakarta REST SE bootstrap.
 */
@Internal
public final class JaxRsReflectionSeBootstrapProvider implements JaxRsSeBootstrapProvider {

    /**
     * Default constructor used by {@link java.util.ServiceLoader}.
     */
    public JaxRsReflectionSeBootstrapProvider() {
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> applicationClass, SeBootstrap.Configuration configuration) {
        JaxRsUtils.requireNonNull("applicationClass", applicationClass);
        JaxRsUtils.requireNonNull("configuration", configuration);
        try {
            Constructor<? extends Application> constructor = applicationClass.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return bootstrap(constructor.newInstance(), configuration);
        } catch (InvocationTargetException e) {
            return CompletableFuture.failedStage(propagate(e.getCause()));
        } catch (ReflectiveOperationException e) {
            return CompletableFuture.failedStage(new IllegalArgumentException("Cannot instantiate Jakarta REST application " + applicationClass.getName(), e));
        }
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration configuration) {
        JaxRsUtils.requireNonNull("application", application);
        JaxRsUtils.requireNonNull("configuration", configuration);
        try {
            String protocol = valueOrDefault(configuration.protocol(), "HTTP");
            String host = valueOrDefault(configuration.host(), "localhost");
            String rootPath = normalizeRootPath(valueOrDefault(configuration.rootPath(), "/"));
            int requestedPort = configuration.port();
            int bindPort = requestedPort > 0 ? requestedPort : 0;
            HttpServer server = HttpServer.create(new InetSocketAddress(host, bindPort), 0);
            List<Route> routes = routes(application, rootPath);
            server.createContext("/", exchange -> handle(exchange, routes));
            server.start();
            SeBootstrap.Configuration actualConfiguration = SeBootstrap.Configuration.builder()
                .property(SeBootstrap.Configuration.PROTOCOL, protocol)
                .property(SeBootstrap.Configuration.HOST, host)
                .property(SeBootstrap.Configuration.PORT, server.getAddress().getPort())
                .property(SeBootstrap.Configuration.ROOT_PATH, rootPath)
                .build();
            return CompletableFuture.completedStage(new Instance(server, actualConfiguration));
        } catch (RuntimeException | IOException e) {
            return CompletableFuture.failedStage(e);
        }
    }

    private static List<Route> routes(Application application, String rootPath) {
        List<Route> routes = new ArrayList<>();
        String applicationPath = applicationPath(application.getClass());
        for (Object singleton : application.getSingletons()) {
            addRoutes(routes, rootPath, applicationPath, singleton);
        }
        Set<Class<?>> classes = application.getClasses();
        for (Class<?> resourceClass : classes) {
            Object instance = instantiate(resourceClass);
            if (instance != null) {
                addRoutes(routes, rootPath, applicationPath, instance);
            }
        }
        return routes;
    }

    private static void addRoutes(List<Route> routes, String rootPath, String applicationPath, Object resource) {
        String resourcePath = resourcePath(resource.getClass());
        if (resourcePath.isEmpty()) {
            return;
        }
        for (Method method : resource.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(GET.class)) {
                continue;
            }
            String methodPath = resourcePath(method);
            routes.add(new Route(join(rootPath, applicationPath, resourcePath, methodPath), resource, method));
        }
    }

    private static Object instantiate(Class<?> resourceClass) {
        try {
            Constructor<?> constructor = resourceClass.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return constructor.newInstance();
        } catch (NoSuchMethodException e) {
            return null;
        } catch (InvocationTargetException e) {
            throw propagate(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate Jakarta REST resource " + resourceClass.getName(), e);
        }
    }

    private static void handle(HttpExchange exchange, List<Route> routes) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        for (Route route : routes) {
            if (route.path().equals(path)) {
                write(exchange, route.invoke());
                return;
            }
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private static void write(HttpExchange exchange, Object result) throws IOException {
        byte[] body = String.valueOf(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private static String applicationPath(Class<?> type) {
        ApplicationPath annotation = type.getAnnotation(ApplicationPath.class);
        return annotation == null ? "" : annotation.value();
    }

    private static String resourcePath(Class<?> type) {
        Path annotation = type.getAnnotation(Path.class);
        return annotation == null ? "" : annotation.value();
    }

    private static String resourcePath(Method method) {
        Path annotation = method.getAnnotation(Path.class);
        return annotation == null ? "" : annotation.value();
    }

    private static String join(String... segments) {
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            String normalized = stripSlashes(segment);
            if (normalized.isEmpty()) {
                continue;
            }
            builder.append('/').append(normalized);
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    private static String normalizeRootPath(String rootPath) {
        if (rootPath.isBlank() || "/".equals(rootPath)) {
            return "/";
        }
        return '/' + stripSlashes(rootPath);
    }

    private static String stripSlashes(String value) {
        String stripped = value == null ? "" : value.strip();
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static RuntimeException propagate(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(throwable);
    }

    private record Route(String path, Object target, Method method) {
        private Object invoke() {
            try {
                if (!method.canAccess(target)) {
                    method.setAccessible(true);
                }
                return method.invoke(target);
            } catch (InvocationTargetException e) {
                throw propagate(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot invoke Jakarta REST resource method " + method, e);
            }
        }
    }

    private record Instance(HttpServer server, SeBootstrap.Configuration configuration) implements SeBootstrap.Instance {
        @Override
        public CompletionStage<StopResult> stop() {
            server.stop(0);
            return CompletableFuture.completedStage(new StopResult() {
                @Override
                public <T> T unwrap(Class<T> type) {
                    if (type.isInstance(server)) {
                        return type.cast(server);
                    }
                    throw new IllegalArgumentException("Cannot unwrap stop result to " + type.getName());
                }
            });
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            if (type.isInstance(server)) {
                return type.cast(server);
            }
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new IllegalArgumentException("Cannot unwrap SE bootstrap instance to " + type.getName());
        }
    }
}
