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

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanIntrospector;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.reflect.exception.InstantiationException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerHttpRequestContext;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.JaxRsResourceTemplateMetadata;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.web.router.MethodBasedRouteInfo;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.Router;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Route-scoped registry for providers installed by Jakarta REST {@link DynamicFeature}s.
 */
@Internal
@Singleton
final class JaxRsDynamicFeatureRegistry {

    static final DynamicComponents EMPTY = new DynamicComponents(List.of(), List.of(), List.of(), List.of(), List.of());
    private static final String COMPONENTS_ATTRIBUTE = JaxRsDynamicFeatureRegistry.class.getName() + ".COMPONENTS";

    private static final List<Class<?>> SERVER_CONTRACTS = List.of(
        ContainerRequestFilter.class,
        ContainerResponseFilter.class,
        ReaderInterceptor.class,
        WriterInterceptor.class
    );

    private final BeanContext beanContext;
    private final BeanProvider<Router> routerProvider;
    private final ApplicationProvider applicationProvider;
    private final List<DynamicFeature> dynamicFeatures;
    private final List<JaxRsProviderInstantiator> providerInstantiators;
    private final DynamicComponents globalComponents;
    private final ConcurrentMap<RouteInfo<?>, DynamicComponents> routeComponents = new ConcurrentHashMap<>();

    JaxRsDynamicFeatureRegistry(BeanContext beanContext,
                                BeanProvider<Router> routerProvider,
                                ApplicationProvider applicationProvider,
                                List<Feature> features,
                                List<DynamicFeature> dynamicFeatures,
                                List<JaxRsProviderInstantiator> providerInstantiators) {
        this.beanContext = beanContext;
        this.routerProvider = routerProvider;
        this.applicationProvider = applicationProvider;
        this.providerInstantiators = providerInstantiators;
        this.dynamicFeatures = providers(DynamicFeature.class, dynamicFeatures);
        this.globalComponents = configureFeatures(providers(Feature.class, features));
    }

    DynamicComponents components(RouteInfo<?> routeInfo) {
        if (!isJaxRsRoute(routeInfo) || !isApplicationRoute(routeInfo)) {
            return EMPTY;
        }
        if (dynamicFeatures.isEmpty()) {
            return globalComponents;
        }
        // DynamicFeature.configure is specified per resource method. Cache the
        // resulting components by RouteInfo so filters/interceptors do not repeat
        // feature registration on every request.
        return routeComponents.computeIfAbsent(routeInfo, route -> globalComponents.merge(configure(route)));
    }

    DynamicComponents globalComponents() {
        return globalComponents;
    }

    DynamicComponents currentComponents() {
        return currentRequest()
            .map(this::components)
            .orElse(EMPTY);
    }

    DynamicComponents components(HttpRequest<?> request) {
        Optional<DynamicComponents> existing = request.getAttribute(COMPONENTS_ATTRIBUTE, DynamicComponents.class);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Request filters may query providers before Micronaut has attached the
        // selected route. Fall back to a router lookup once, then cache the answer
        // on the request.
        DynamicComponents components = routeInfo(request)
            .or(() -> routeInfoFromRouter(request))
            .map(this::components)
            .orElseGet(() -> applicationProvider.isApplicationRequest(request) ? globalComponents : EMPTY);
        request.setAttribute(COMPONENTS_ATTRIBUTE, components);
        return components;
    }

    boolean hasReaderInterceptors() {
        return !currentComponents().readerInterceptors().isEmpty();
    }

    private DynamicComponents configure(RouteInfo<?> routeInfo) {
        DynamicFeatureContext context = new DynamicFeatureContext(beanContext, providerInstantiators);
        ResourceInfo resourceInfo = new RouteResourceInfo(routeInfo);
        for (DynamicFeature dynamicFeature : dynamicFeatures) {
            dynamicFeature.configure(resourceInfo, context);
        }
        return context.toComponents();
    }

    private DynamicComponents configureFeatures(List<Feature> features) {
        DynamicComponents components = EMPTY;
        for (Feature feature : features) {
            DynamicFeatureContext context = new DynamicFeatureContext(beanContext, providerInstantiators);
            if (feature.configure(context)) {
                components = components.merge(context.toComponents());
            }
        }
        return components;
    }

    private <T> List<T> providers(Class<T> contract, List<T> beanProviders) {
        Map<String, T> providers = new LinkedHashMap<>();
        beanProviders.stream()
            .filter(provider -> applicationProvider.isApplicationResource(provider.getClass().getName()))
            .forEach(provider -> providers.put(provider.getClass().getName(), provider));
        for (T provider : ServiceLoader.load(contract, beanContext.getClassLoader())) {
            providers.putIfAbsent(provider.getClass().getName(), provider);
        }
        return List.copyOf(providers.values());
    }

    private static Optional<RouteInfo<?>> routeInfo(HttpRequest<?> request) {
        return RouteAttributes.getRouteInfo(request)
            .or(() -> RouteAttributes.getRouteMatch(request).map(routeMatch -> routeMatch.getRouteInfo()));
    }

    private static Optional<HttpRequest<?>> currentRequest() {
        Optional<HttpRequest<Object>> request = ServerRequestContext.currentRequest();
        if (request.isPresent()) {
            return Optional.of(request.get());
        }
        return ServerHttpRequestContext.find().map(httpRequest -> httpRequest);
    }

    private Optional<RouteInfo<?>> routeInfoFromRouter(HttpRequest<?> request) {
        Optional<? extends RouteInfo<?>> routeInfo = routerProvider.get()
            .find(request)
            .map(match -> match.getRouteInfo())
            .filter(JaxRsDynamicFeatureRegistry::isJaxRsRoute)
            .filter(this::isApplicationRoute)
            .findFirst();
        return routeInfo.map(info -> info);
    }

    private static boolean isJaxRsRoute(RouteInfo<?> routeInfo) {
        return routeInfo.getAnnotationMetadata().hasAnnotation(Path.class);
    }

    private boolean isApplicationRoute(RouteInfo<?> routeInfo) {
        String resourceClassName = routeInfo.getAnnotationMetadata()
            .stringValue(JaxRsResourceTemplate.class, JaxRsResourceTemplateMetadata.MEMBER_ROOT_CLASS_NAME)
            .orElseGet(() -> routeInfo.getDeclaringType().getName());
        return applicationProvider.isApplicationResource(resourceClassName);
    }

    private record RouteResourceInfo(RouteInfo<?> routeInfo) implements ResourceInfo {

        @Override
        public @Nullable Method getResourceMethod() {
            if (routeInfo instanceof MethodBasedRouteInfo<?, ?> methodBasedRouteInfo) {
                return methodBasedRouteInfo.getTargetMethod().getTargetMethod();
            }
            return null;
        }

        @Override
        public Class<?> getResourceClass() {
            return routeInfo.getDeclaringType();
        }
    }

    record DynamicComponents(List<ContainerRequestFilter> requestFilters,
                             List<ContainerResponseFilter> responseFilters,
                             List<ReaderInterceptor> readerInterceptors,
                             List<WriterInterceptor> writerInterceptors,
                             List<ContainerRequestFilter> preMatchingRequestFilters) {

        private DynamicComponents merge(DynamicComponents other) {
            if (this == EMPTY) {
                return other;
            }
            if (other == EMPTY) {
                return this;
            }
            return new DynamicComponents(
                merge(requestFilters, other.requestFilters),
                merge(responseFilters, other.responseFilters),
                merge(readerInterceptors, other.readerInterceptors),
                merge(writerInterceptors, other.writerInterceptors),
                merge(preMatchingRequestFilters, other.preMatchingRequestFilters)
            );
        }

        private static <T> List<T> merge(List<T> first, List<T> second) {
            if (first.isEmpty()) {
                return second;
            }
            if (second.isEmpty()) {
                return first;
            }
            List<T> merged = new ArrayList<>(first.size() + second.size());
            merged.addAll(first);
            merged.addAll(second);
            return List.copyOf(merged);
        }
    }

    private static final class DynamicFeatureContext implements FeatureContext, Configuration {
        private final BeanContext beanContext;
        private final List<JaxRsProviderInstantiator> providerInstantiators;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<Component> components = new ArrayList<>();

        private DynamicFeatureContext(BeanContext beanContext) {
            this(beanContext, List.of());
        }

        private DynamicFeatureContext(BeanContext beanContext, List<JaxRsProviderInstantiator> providerInstantiators) {
            this.beanContext = beanContext;
            this.providerInstantiators = providerInstantiators;
        }

        @Override
        public Configuration getConfiguration() {
            return this;
        }

        @Override
        public FeatureContext property(String name, Object value) {
            properties.put(name, value);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            return register(componentClass, Ordered.LOWEST_PRECEDENCE);
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            return registerComponent(componentClass, instantiate(componentClass), defaultContracts(componentClass, priority));
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            return registerComponent(componentClass, instantiate(componentClass), contracts(componentClass, 0, contracts));
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            return registerComponent(componentClass, instantiate(componentClass), contracts(componentClass, contracts));
        }

        @Override
        public FeatureContext register(Object component) {
            return register(component, Ordered.LOWEST_PRECEDENCE);
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            return registerComponent(component.getClass(), component, defaultContracts(component.getClass(), priority));
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            return registerComponent(component.getClass(), component, contracts(component.getClass(), 0, contracts));
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            return registerComponent(component.getClass(), component, contracts(component.getClass(), contracts));
        }

        @Override
        public RuntimeType getRuntimeType() {
            return RuntimeType.SERVER;
        }

        @Override
        public Map<String, Object> getProperties() {
            return Collections.unmodifiableMap(properties);
        }

        @Override
        public @Nullable Object getProperty(String name) {
            return properties.get(name);
        }

        @Override
        public Collection<String> getPropertyNames() {
            return properties.keySet();
        }

        @Override
        public boolean isEnabled(Feature feature) {
            return false;
        }

        @Override
        public boolean isEnabled(Class<? extends Feature> featureClass) {
            return false;
        }

        @Override
        public boolean isRegistered(Object instance) {
            return components.stream().anyMatch(component -> component.instance() == instance);
        }

        @Override
        public boolean isRegistered(Class<?> componentClass) {
            return components.stream().anyMatch(component -> component.componentClass().equals(componentClass));
        }

        @Override
        public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
            return components.stream()
                .filter(component -> component.componentClass().equals(componentClass))
                .flatMap(component -> component.contracts().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
        }

        @Override
        public Set<Class<?>> getClasses() {
            return components.stream()
                .map(Component::componentClass)
                .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Set<Object> getInstances() {
            return components.stream()
                .map(Component::instance)
                .collect(Collectors.toUnmodifiableSet());
        }

        private FeatureContext registerComponent(Class<?> componentClass, Object component, Map<Class<?>, Integer> contracts) {
            if (component instanceof Feature feature) {
                feature.configure(this);
            }
            if (!contracts.isEmpty()) {
                components.add(new Component(componentClass, component, contracts));
            }
            return this;
        }

        private Object instantiate(Class<?> componentClass) {
            Optional<?> bean = beanContext.findBean(componentClass);
            if (bean.isPresent()) {
                return bean.get();
            }
            Optional<BeanIntrospection<Object>> introspection = (Optional<BeanIntrospection<Object>>) (Optional<?>)
                BeanIntrospector.forClassLoader(beanContext.getClassLoader()).findIntrospection(componentClass);
            if (introspection.isPresent()) {
                try {
                    return introspection.get().instantiate();
                } catch (InstantiationException e) {
                    throw new IllegalStateException("Cannot instantiate dynamic Jakarta REST provider " + componentClass.getName(), e);
                }
            }
            for (JaxRsProviderInstantiator providerInstantiator : providerInstantiators) {
                Optional<Object> instantiated = providerInstantiator.instantiate(componentClass);
                if (instantiated.isPresent()) {
                    return instantiated.get();
                }
            }
            throw new IllegalStateException("Cannot instantiate dynamic Jakarta REST provider " +
                componentClass.getName() + " without a bean definition or BeanIntrospection");
        }

        private DynamicComponents toComponents() {
            List<Prioritized<ContainerRequestFilter>> requestFilters = new ArrayList<>();
            List<Prioritized<ContainerRequestFilter>> preMatchingRequestFilters = new ArrayList<>();
            List<Prioritized<ContainerResponseFilter>> responseFilters = new ArrayList<>();
            List<Prioritized<ReaderInterceptor>> readerInterceptors = new ArrayList<>();
            List<Prioritized<WriterInterceptor>> writerInterceptors = new ArrayList<>();
            for (Component component : components) {
                addRequestFilter(component, requestFilters, preMatchingRequestFilters);
                add(component, ContainerResponseFilter.class, responseFilters);
                add(component, ReaderInterceptor.class, readerInterceptors);
                add(component, WriterInterceptor.class, writerInterceptors);
            }
            return new DynamicComponents(
                sorted(requestFilters, false),
                sorted(responseFilters, true),
                sorted(readerInterceptors, false),
                sorted(writerInterceptors, false),
                sorted(preMatchingRequestFilters, false)
            );
        }

        private static void addRequestFilter(Component component,
                                             List<Prioritized<ContainerRequestFilter>> requestFilters,
                                             List<Prioritized<ContainerRequestFilter>> preMatchingRequestFilters) {
            Integer priority = component.contracts().get(ContainerRequestFilter.class);
            if (priority != null && component.instance() instanceof ContainerRequestFilter requestFilter) {
                List<Prioritized<ContainerRequestFilter>> target = component.instance().getClass().isAnnotationPresent(PreMatching.class)
                    ? preMatchingRequestFilters
                    : requestFilters;
                target.add(new Prioritized<>(requestFilter, effectivePriority(component.instance(), priority)));
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> void add(Component component, Class<T> contract, List<Prioritized<T>> target) {
            Integer priority = component.contracts().get(contract);
            if (priority != null && contract.isInstance(component.instance())) {
                target.add(new Prioritized<>((T) component.instance(), effectivePriority(component.instance(), priority)));
            }
        }

        private static int effectivePriority(Object instance, int priority) {
            return priority == Ordered.LOWEST_PRECEDENCE ? JaxRsUtils.getPriorityOrder(instance) : priority;
        }

        private static <T> List<T> sorted(List<Prioritized<T>> components, boolean reversed) {
            Comparator<Prioritized<T>> comparator = Comparator.comparingInt(Prioritized::priority);
            if (reversed) {
                comparator = comparator.reversed();
            }
            return components.stream()
                .sorted(comparator)
                .map(Prioritized::component)
                .toList();
        }

        private static Map<Class<?>, Integer> defaultContracts(Class<?> componentClass, int priority) {
            return SERVER_CONTRACTS.stream()
                .filter(contract -> contract.isAssignableFrom(componentClass))
                .collect(Collectors.toMap(contract -> contract, contract -> priority, (left, right) -> left, LinkedHashMap::new));
        }

        private static Map<Class<?>, Integer> contracts(Class<?> componentClass, int priority, Class<?>... contracts) {
            if (contracts == null || contracts.length == 0) {
                return Map.of();
            }
            Map<Class<?>, Integer> result = new LinkedHashMap<>();
            for (Class<?> contract : contracts) {
                if (contract != null && contract.isAssignableFrom(componentClass) && SERVER_CONTRACTS.contains(contract)) {
                    result.put(contract, priority);
                }
            }
            return result;
        }

        private static Map<Class<?>, Integer> contracts(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            if (contracts == null || contracts.isEmpty()) {
                return Map.of();
            }
            Map<Class<?>, Integer> result = new LinkedHashMap<>();
            contracts.forEach((contract, priority) -> {
                if (contract != null && contract.isAssignableFrom(componentClass) && SERVER_CONTRACTS.contains(contract)) {
                    result.put(contract, priority == null ? Ordered.LOWEST_PRECEDENCE : priority);
                }
            });
            return result;
        }
    }

    private record Component(Class<?> componentClass, Object instance, Map<Class<?>, Integer> contracts) {
    }

    private record Prioritized<T>(T component, int priority) {
    }
}
