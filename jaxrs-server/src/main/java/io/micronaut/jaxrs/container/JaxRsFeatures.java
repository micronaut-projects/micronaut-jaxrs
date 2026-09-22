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
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.reflect.InstantiationUtils;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.web.router.MethodBasedRouteInfo;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.reflection.ReflectionBeanDefinition;
import io.micronaut.reflection.ReflectionBeanIntrospection;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link Feature}s and {@link DynamicFeature}s of the application: the beans, the ones its
 * {@link Application} registers, and the ones of the Java service loader (JAX-RS 3.1).
 *
 * <p>A {@link Feature} is configured once, at startup: the components it registers become beans,
 * a class the annotation processors never saw through a {@link ReflectionBeanDefinition}, so the
 * container filters, interceptors and providers see them like any other. A
 * {@link DynamicFeature} is configured for each resource method: the filters and interceptors it
 * registers apply to that method only.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
public final class JaxRsFeatures implements JaxRsRouteInterceptors {

    private final ApplicationContext context;
    private final List<Feature> features;
    private final List<DynamicFeature> dynamicFeatures;
    private final Set<Class<?>> registeredClasses = ConcurrentHashMap.newKeySet();
    private final Set<Object> registeredInstances = ConcurrentHashMap.newKeySet();
    private final Map<Method, Components> components = new ConcurrentHashMap<>();
    private volatile boolean configured;

    JaxRsFeatures(ApplicationContext context, Collection<Feature> features, Collection<DynamicFeature> dynamicFeatures) {
        this.context = context;
        Application application = context.findBean(Application.class).orElse(null);
        this.features = collect(Feature.class, features, application);
        this.dynamicFeatures = collect(DynamicFeature.class, dynamicFeatures, application);
    }

    /**
     * Configure the features, once: called at startup, before the container filters are created.
     */
    void configure() {
        if (configured) {
            return;
        }
        configured = true;
        for (Feature feature : features) {
            Context featureContext = new Context(true);
            if (feature.configure(featureContext)) {
                registeredInstances.add(feature);
            }
        }
    }

    /**
     * @param componentClass A class
     * @return Whether a feature registered it
     */
    public boolean isRegistered(Class<?> componentClass) {
        if (registeredClasses.contains(componentClass)) {
            return true;
        }
        for (Object instance : registeredInstances) {
            if (instance.getClass() == componentClass) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param instance An instance
     * @return Whether a feature registered it, or it is a feature that is enabled
     */
    public boolean isRegistered(Object instance) {
        return registeredInstances.contains(instance);
    }

    /**
     * The components the dynamic features register for a resource method.
     *
     * @param resourceClass The resource class
     * @param method        The resource method
     * @return The components
     */
    Components components(Class<?> resourceClass, Method method) {
        if (dynamicFeatures.isEmpty()) {
            return Components.EMPTY;
        }
        return components.computeIfAbsent(method, m -> {
            Context featureContext = new Context(false);
            ResourceInfo resourceInfo = new ResourceInfo() {
                @Override
                public Method getResourceMethod() {
                    return m;
                }

                @Override
                public Class<?> getResourceClass() {
                    return resourceClass;
                }
            };
            for (DynamicFeature feature : dynamicFeatures) {
                feature.configure(resourceInfo, featureContext);
            }
            return featureContext.components();
        });
    }

    /**
     * The instances of a type: the beans, the ones of the {@code Application}, and the ones the
     * service loader provides, one per class.
     */
    private <T> List<T> collect(Class<T> type, Collection<T> beans, @Nullable Application application) {
        Map<Class<?>, T> instances = new LinkedHashMap<>();
        for (T bean : beans) {
            instances.putIfAbsent(bean.getClass(), bean);
        }
        if (application != null) {
            for (Class<?> c : application.getClasses()) {
                if (type.isAssignableFrom(c) && !instances.containsKey(c)) {
                    instances.put(c, type.cast(instance(c)));
                }
            }
            for (Object singleton : application.getSingletons()) {
                if (type.isInstance(singleton)) {
                    instances.putIfAbsent(singleton.getClass(), type.cast(singleton));
                }
            }
        }
        ClassLoader classLoader = application == null ? context.getClassLoader() : application.getClass().getClassLoader();
        for (T loaded : ServiceLoader.load(type, classLoader)) {
            instances.putIfAbsent(loaded.getClass(), loaded);
        }
        return new ArrayList<>(instances.values());
    }

    /**
     * An instance of a component class: the bean, or else the class instantiated and injected.
     */
    private Object instance(Class<?> type) {
        if (context.containsBean(type)) {
            return context.getBean(type);
        }
        // a class the annotation processors never saw: instantiated from a reflective
        // introspection, which reaches a class or constructor that is not public
        Object instance = ReflectionBeanIntrospection.isIntrospectable(type)
            ? ReflectionBeanIntrospection.of(type).instantiate()
            : InstantiationUtils.instantiate(type);
        return context.inject(instance);
    }

    @Override
    public List<ReaderInterceptor> readerInterceptors() {
        return currentComponents().readerInterceptors();
    }

    @Override
    public List<WriterInterceptor> writerInterceptors() {
        return currentComponents().writerInterceptors();
    }

    /**
     * @param route The route of a request
     * @return The components the dynamic features registered for its resource method
     */
    Components components(@Nullable RouteInfo<?> route) {
        if (route instanceof MethodBasedRouteInfo<?, ?> methodRoute) {
            return components(methodRoute.getDeclaringType(), methodRoute.getTargetMethod().getTargetMethod());
        }
        return Components.EMPTY;
    }

    private Components currentComponents() {
        if (dynamicFeatures.isEmpty()) {
            return Components.EMPTY;
        }
        return ServerRequestContext.currentRequest()
            .flatMap(RouteAttributes::getRouteInfo)
            .map(this::components)
            .orElse(Components.EMPTY);
    }

    /**
     * A class and every class and interface it extends or implements, but {@code Object}.
     */
    private static Class<?>[] supertypes(Class<?> type) {
        Set<Class<?>> types = new LinkedHashSet<>();
        List<Class<?>> pending = new ArrayList<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> next = pending.remove(pending.size() - 1);
            if (next == null || next == Object.class || !types.add(next)) {
                continue;
            }
            pending.add(next.getSuperclass());
            pending.addAll(List.of(next.getInterfaces()));
        }
        return types.toArray(Class<?>[]::new);
    }

    /**
     * The filters and interceptors a dynamic feature registers for a resource method.
     *
     * @param requestFilters     The container request filters
     * @param responseFilters    The container response filters
     * @param readerInterceptors The reader interceptors
     * @param writerInterceptors The writer interceptors
     */
    record Components(List<ContainerRequestFilter> requestFilters,
                      List<ContainerResponseFilter> responseFilters,
                      List<ReaderInterceptor> readerInterceptors,
                      List<WriterInterceptor> writerInterceptors) {
        static final Components EMPTY = new Components(List.of(), List.of(), List.of(), List.of());

        boolean isEmpty() {
            return requestFilters.isEmpty() && responseFilters.isEmpty() && readerInterceptors.isEmpty() && writerInterceptors.isEmpty();
        }
    }

    /**
     * The context a feature registers its components with.
     */
    private final class Context implements FeatureContext {
        private final boolean global;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final Set<Object> instances = new LinkedHashSet<>();

        Context(boolean global) {
            this.global = global;
        }

        Components components() {
            List<ContainerRequestFilter> requestFilters = new ArrayList<>();
            List<ContainerResponseFilter> responseFilters = new ArrayList<>();
            List<ReaderInterceptor> readerInterceptors = new ArrayList<>();
            List<WriterInterceptor> writerInterceptors = new ArrayList<>();
            for (Object instance : instances) {
                if (instance instanceof ContainerRequestFilter f) {
                    requestFilters.add(f);
                }
                if (instance instanceof ContainerResponseFilter f) {
                    responseFilters.add(f);
                }
                if (instance instanceof ReaderInterceptor i) {
                    readerInterceptors.add(i);
                }
                if (instance instanceof WriterInterceptor i) {
                    writerInterceptors.add(i);
                }
            }
            return new Components(requestFilters, responseFilters, readerInterceptors, writerInterceptors);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private FeatureContext registerClass(Class<?> componentClass) {
            if (global) {
                registeredClasses.add(componentClass);
                if (!context.containsBean(componentClass) && ReflectionBeanDefinition.isDefinable(componentClass)) {
                    // a class the annotation processors never saw becomes a bean, found by each of
                    // its types: a runtime definition is only indexed by the types it exposes
                    context.registerBeanDefinition(ReflectionBeanDefinition.builder((Class) componentClass)
                        .exposedTypes(supertypes(componentClass))
                        .build());
                }
            } else {
                instances.add(instance(componentClass));
            }
            return this;
        }

        private FeatureContext registerInstance(Object component) {
            if (global) {
                registeredInstances.add(component);
                if (component instanceof Feature feature) {
                    feature.configure(this);
                } else {
                    context.registerSingleton(component);
                }
            } else {
                instances.add(component);
            }
            return this;
        }

        @Override
        public Configuration getConfiguration() {
            return context.getBean(Configuration.class);
        }

        @Override
        public FeatureContext property(String name, Object value) {
            properties.put(name, value);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            return registerClass(componentClass);
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            return registerClass(componentClass);
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            return registerClass(componentClass);
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            return registerClass(componentClass);
        }

        @Override
        public FeatureContext register(Object component) {
            return registerInstance(component);
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            return registerInstance(component);
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            return registerInstance(component);
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            return registerInstance(component);
        }
    }
}
