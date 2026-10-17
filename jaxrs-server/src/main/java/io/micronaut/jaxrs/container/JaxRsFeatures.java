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

import io.micronaut.context.RuntimeBeanDefinition;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
    private final Map<Object, Components> components = new ConcurrentHashMap<>();
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
     * @param key           The element of the resource method, see {@link JaxRsRouteSupport#resourceMethodKey}
     * @param method        The resource method
     * @return The components
     */
    Components components(Class<?> resourceClass, Object key, Supplier<Method> method) {
        if (dynamicFeatures.isEmpty()) {
            return Components.EMPTY;
        }
        return components.computeIfAbsent(key, k -> {
            Context featureContext = new Context(false);
            ResourceInfo resourceInfo = new ResourceInfo() {
                @Override
                public Method getResourceMethod() {
                    return method.get();
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
        Set<Class<?>> listed = new HashSet<>();
        if (application != null) {
            listed.addAll(application.getClasses());
            application.getSingletons().forEach(singleton -> listed.add(singleton.getClass()));
        }
        for (T bean : beans) {
            // an application that lists its classes or instances registers only those (JAX-RS 2.3)
            if (listed.isEmpty() || listed.contains(bean.getClass())) {
                instances.putIfAbsent(bean.getClass(), bean);
            }
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
        // a class the annotation processors never saw
        Object instance = JaxRsReflection.get().instantiate(type);
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

    @Override
    public int priority(Object interceptor, Class<?> contract) {
        return currentComponents().priority(interceptor, contract);
    }

    /**
     * @param route The route of a request
     * @return The components the dynamic features registered for its resource method
     */
    Components components(@Nullable RouteInfo<?> route) {
        Object key = JaxRsRouteSupport.resourceMethodKey(route);
        Class<?> resourceClass = JaxRsRouteSupport.resourceClass(route);
        if (key == null || resourceClass == null) {
            return Components.EMPTY;
        }
        return components(resourceClass, key, () -> Objects.requireNonNull(JaxRsRouteSupport.resourceMethod(route)));
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
     * The filters and interceptors a dynamic feature registers for a resource method.
     *
     * @param requestFilters     The container request filters
     * @param responseFilters    The container response filters
     * @param readerInterceptors The reader interceptors
     * @param writerInterceptors The writer interceptors
     * @param priorities         The priorities the components were registered with, by contract,
     *                           {@code null} for all of their contracts
     */
    record Components(List<ContainerRequestFilter> requestFilters,
                      List<ContainerResponseFilter> responseFilters,
                      List<ReaderInterceptor> readerInterceptors,
                      List<WriterInterceptor> writerInterceptors,
                      Map<Object, Map<@Nullable Class<?>, Integer>> priorities) {
        static final Components EMPTY = new Components(List.of(), List.of(), List.of(), List.of(), Map.of());

        boolean isEmpty() {
            return requestFilters.isEmpty() && responseFilters.isEmpty() && readerInterceptors.isEmpty() && writerInterceptors.isEmpty();
        }

        /**
         * @param component A component
         * @param contract  The contract it is used as, e.g. {@link ContainerRequestFilter}
         * @return The priority it was registered with for the contract, else its {@code @Priority}
         */
        int priority(Object component, Class<?> contract) {
            Map<@Nullable Class<?>, Integer> byContract = priorities.get(component);
            if (byContract != null) {
                Integer priority = byContract.get(contract);
                if (priority == null) {
                    priority = byContract.get(null);
                }
                if (priority != null) {
                    return priority;
                }
            }
            return JaxRsUtils.getPriorityOrder(component);
        }
    }

    /**
     * The context a feature registers its components with.
     */
    private final class Context implements FeatureContext {
        private final boolean global;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final Set<Object> instances = new LinkedHashSet<>();
        private final Map<Object, Map<@Nullable Class<?>, Integer>> priorities = new HashMap<>();

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
            var components = new Components(requestFilters, responseFilters, readerInterceptors, writerInterceptors, Map.copyOf(priorities));
            // the explicit priorities, else @Priority, order them; the response filters run in
            // the reverse order (JAX-RS 6.9)
            requestFilters.sort(Comparator.comparingInt(c -> components.priority(c, ContainerRequestFilter.class)));
            responseFilters.sort(Comparator.<ContainerResponseFilter>comparingInt(c -> components.priority(c, ContainerResponseFilter.class)).reversed());
            readerInterceptors.sort(Comparator.comparingInt(c -> components.priority(c, ReaderInterceptor.class)));
            writerInterceptors.sort(Comparator.comparingInt(c -> components.priority(c, WriterInterceptor.class)));
            return components;
        }

        private FeatureContext registerClass(Class<?> componentClass, Map<@Nullable Class<?>, Integer> priorities) {
            if (!global) {
                Object instance = instance(componentClass);
                if (instances.add(instance) && !priorities.isEmpty()) {
                    this.priorities.put(instance, priorities);
                }
                return this;
            }
            return registerClass(componentClass);
        }

        private FeatureContext registerInstance(Object component, Map<@Nullable Class<?>, Integer> priorities) {
            if (!global) {
                if (instances.add(component) && !priorities.isEmpty()) {
                    this.priorities.put(component, priorities);
                }
                return this;
            }
            return registerInstance(component);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private FeatureContext registerClass(Class<?> componentClass) {
            if (global) {
                registeredClasses.add(componentClass);
                RuntimeBeanDefinition<?> definition = context.containsBean(componentClass) ? null
                    : JaxRsReflection.get().beanDefinition(componentClass);
                if (definition != null) {
                    // a class the annotation processors never saw becomes a bean
                    context.registerBeanDefinition(definition);
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

        private static Map<@Nullable Class<?>, Integer> allContracts(int priority) {
            Map<@Nullable Class<?>, Integer> priorities = new HashMap<>();
            priorities.put(null, priority);
            return priorities;
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
            return registerClass(componentClass, allContracts(priority));
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            return registerClass(componentClass);
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            return registerClass(componentClass, Map.copyOf(contracts));
        }

        @Override
        public FeatureContext register(Object component) {
            return registerInstance(component);
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            return registerInstance(component, allContracts(priority));
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            return registerInstance(component);
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            return registerInstance(component, Map.copyOf(contracts));
        }
    }
}
