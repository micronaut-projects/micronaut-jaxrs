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
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.Router;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Application;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The JAX-RS applications of the context, each with its {@link JaxRsApplicationScope}: the
 * {@link Application} named by {@value JaxRsApplicationFactory#APPLICATION}, else every
 * {@code Application} bean, else the default one, {@link JaxRsApplication}. Each one serves the
 * resources it registers under its {@code @ApplicationPath}, which must be distinct.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsApplications implements ApplicationEventListener<StartupEvent> {

    private final ApplicationContext context;
    private final BeanProvider<ApplicationProvider> applicationProvider;
    private final BeanProvider<JaxRsContainerMessageBodyHandlerRegistry> registry;
    private final boolean multiple;
    private volatile @Nullable List<JaxRsApplicationScope> scopes;
    // the scope of the only application: no lookup per request
    private volatile @Nullable JaxRsApplicationScope single;

    JaxRsApplications(ApplicationContext context, BeanProvider<ApplicationProvider> applicationProvider,
                      BeanProvider<JaxRsContainerMessageBodyHandlerRegistry> registry) {
        this.context = context;
        this.applicationProvider = applicationProvider;
        this.registry = registry;
        this.multiple = isMultiple(context);
    }

    /**
     * Whether a context has several applications: more than one {@code Application} bean besides
     * the default one, and neither one named by {@value JaxRsApplicationFactory#APPLICATION} nor
     * a primary one, e.g. the one of the Java SE bootstrap.
     *
     * @param context The context
     * @return Whether it has several applications
     */
    static boolean isMultiple(BeanContext context) {
        if (context instanceof ApplicationContext applicationContext && applicationContext.containsProperty(JaxRsApplicationFactory.APPLICATION)) {
            return false;
        }
        return candidates(context).size() > 1;
    }

    private static List<BeanDefinition<Application>> candidates(BeanContext context) {
        List<BeanDefinition<Application>> candidates = new ArrayList<>();
        for (BeanDefinition<Application> definition : context.getBeanDefinitions(Application.class)) {
            if ((Class<?>) definition.getBeanType() == JaxRsApplication.class) {
                continue;
            }
            if (definition.hasDeclaredAnnotation(Primary.class)) {
                return List.of(definition);
            }
            candidates.add(definition);
        }
        return candidates;
    }

    /**
     * @return Whether the context has several applications
     */
    boolean isMultiple() {
        return multiple;
    }

    /**
     * @return The scopes of the applications, created when first used
     */
    List<JaxRsApplicationScope> scopes() {
        List<JaxRsApplicationScope> s = scopes;
        if (s == null) {
            synchronized (this) {
                s = scopes;
                if (s == null) {
                    s = createScopes();
                    if (s.size() == 1) {
                        single = s.get(0);
                    }
                    scopes = s;
                }
            }
        }
        return s;
    }

    private List<JaxRsApplicationScope> createScopes() {
        if (!multiple) {
            // the bean of the application, as without several applications
            Application application = context.findBean(Application.class).orElse(null);
            return List.of(new JaxRsApplicationScope(context, application, false, applicationProvider.get(), registry.get()));
        }
        // the beans of the applications are the ones of the request
        applicationProvider.get().resolver(request -> current(request).applicationProvider());
        registry.get().current(() -> current().registry());
        String contextPath = context.getProperty("micronaut.server.context-path", String.class).orElse(null);
        List<JaxRsApplicationScope> created = new ArrayList<>();
        Map<String, Application> paths = new HashMap<>();
        for (BeanDefinition<Application> definition : candidates(context)) {
            Application application = context.getBean(definition);
            AnnotationMetadata metadata = definition.getAnnotationMetadata();
            JaxRsApplicationScope scope = new JaxRsApplicationScope(context, application, true,
                new ApplicationProvider(metadata, contextPath), null);
            String path = normalize(scope.applicationPath());
            Application other = paths.putIfAbsent(path, application);
            if (other != null) {
                throw new ConfigurationException("The JAX-RS applications " + other.getClass().getName() + " and "
                    + application.getClass().getName() + " have the same application path: " + path
                    + ". Give each Application bean a distinct @ApplicationPath");
            }
            created.add(scope);
        }
        // the longest path first, see #forPath
        created.sort((a, b) -> Integer.compare(normalize(b.applicationPath()).length(), normalize(a.applicationPath()).length()));
        return List.copyOf(created);
    }

    private static String normalize(String path) {
        String normalized = path.startsWith("/") ? path : "/" + path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * @return The scope of the only application, {@code null} with several
     */
    @Nullable JaxRsApplicationScope single() {
        JaxRsApplicationScope s = single;
        if (s == null && !multiple) {
            scopes();
            s = single;
        }
        return s;
    }

    /**
     * @return The scope of the application of the current request: the only one, else the one of
     * the request, else the first one
     */
    JaxRsApplicationScope current() {
        JaxRsApplicationScope s = single();
        if (s != null) {
            return s;
        }
        HttpRequest<?> request = ServerRequestContext.currentRequest().orElse(null);
        return request == null ? scopes().get(0) : current(request);
    }

    /**
     * The scope of the application that serves a request: the one of the resource method of its
     * route, else the one a sub-resource locator set, else the one whose path is the longest
     * prefix of the path of the request, e.g. before it is matched.
     *
     * @param request The request
     * @return The scope
     */
    JaxRsApplicationScope current(HttpRequest<?> request) {
        JaxRsApplicationScope s = single();
        if (s != null) {
            return s;
        }
        JaxRsRouteResource resource = JaxRsRouteResource.of(RouteAttributes.getRouteInfo(request).orElse(null));
        if (resource != null && resource.scope() != null) {
            return resource.scope();
        }
        JaxRsApplicationScope located = request.getAttribute(JaxRsApplicationScope.ATTRIBUTE, JaxRsApplicationScope.class).orElse(null);
        return located != null ? located : forPath(request.getPath());
    }

    /**
     * @param path The path of a request
     * @return The scope of the application whose path is the longest prefix of it, else the
     * first one
     */
    JaxRsApplicationScope forPath(String path) {
        List<JaxRsApplicationScope> all = scopes();
        if (all.size() == 1) {
            return all.get(0);
        }
        String contextPath = all.get(0).applicationProvider().getContextPath();
        String relative = !contextPath.isEmpty() && path.startsWith(contextPath) ? path.substring(contextPath.length()) : path;
        for (JaxRsApplicationScope scope : all) {
            String applicationPath = normalize(scope.applicationPath());
            if ("/".equals(applicationPath) || relative.equals(applicationPath) || relative.startsWith(applicationPath + "/")) {
                return scope;
            }
        }
        return all.get(all.size() - 1);
    }

    /**
     * Configure the dynamic features of the applications for the resource methods of their
     * routes, at deployment ({@code DynamicFeature#configure}).
     *
     * @param event The startup event
     */
    @Override
    public void onApplicationEvent(StartupEvent event) {
        Collection<JaxRsApplicationScope> all = scopes();
        if (all.stream().noneMatch(scope -> scope.features().hasDynamicFeatures())) {
            return;
        }
        context.findBean(Router.class).ifPresent(router -> router.uriRoutes().forEach(route -> {
            JaxRsRouteResource resource = JaxRsRouteResource.of(route);
            if (resource == null) {
                return;
            }
            JaxRsApplicationScope scope = resource.scope() != null ? resource.scope() : all.iterator().next();
            if (single != null || all.contains(scope)) {
                scope.features().components(route);
            }
        }));
    }
}
