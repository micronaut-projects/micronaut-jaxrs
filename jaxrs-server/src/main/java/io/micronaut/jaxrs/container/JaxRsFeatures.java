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
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.Router;
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
import java.util.Arrays;
import io.micronaut.context.BeanRegistration;
import jakarta.ws.rs.Priorities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * {@link DynamicFeature} is configured for each resource method, at startup: the filters and
 * interceptors it registers apply to that method only. The methods of a sub-resource a locator
 * returns known only at runtime are configured when first requested.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
public final class JaxRsFeatures implements JaxRsRouteInterceptors, ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsFeatures.class);
    // the contracts of the providers of the server, see Configuration#getContracts
    private static final List<Class<?>> CONTRACTS = List.of(
        ContainerRequestFilter.class, ContainerResponseFilter.class, ReaderInterceptor.class, WriterInterceptor.class,
        jakarta.ws.rs.ext.MessageBodyReader.class, jakarta.ws.rs.ext.MessageBodyWriter.class,
        jakarta.ws.rs.ext.ExceptionMapper.class, jakarta.ws.rs.ext.ContextResolver.class,
        jakarta.ws.rs.ext.ParamConverterProvider.class, DynamicFeature.class, Feature.class);

    private final ApplicationContext context;
    private final List<Feature> features;
    private final List<DynamicFeature> dynamicFeatures;
    private final Set<Class<?>> registeredClasses = ConcurrentHashMap.newKeySet();
    private final Set<Object> registeredInstances = ConcurrentHashMap.newKeySet();
    private final Map<Object, Components> components = new ConcurrentHashMap<>();
    // the contracts and priorities of the components the features registered globally, by class
    private final Map<Class<?>, Registration> registrations = new ConcurrentHashMap<>();
    // the properties the features set, see Configuration#getProperty
    private final Map<String, Object> properties = new ConcurrentHashMap<>();
    private volatile boolean configured;

    JaxRsFeatures(ApplicationContext context, List<BeanRegistration<Feature>> features, List<BeanRegistration<DynamicFeature>> dynamicFeatures) {
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
     * @return The properties the features set
     */
    Map<String, Object> properties() {
        return properties;
    }

    /**
     * @param componentClass A component class
     * @return The contracts a feature registered it for, with their priorities, empty if it
     * registered it for all of the contracts it implements
     */
    Map<Class<?>, Integer> contracts(Class<?> componentClass) {
        Registration registration = registrations.get(componentClass);
        if (registration == null) {
            return Map.of();
        }
        Collection<Class<?>> registered = registration.contracts();
        if (registered == null) {
            // registered for all of the contracts it implements, e.g. with a priority
            registered = CONTRACTS.stream().filter(contract -> contract.isAssignableFrom(componentClass)).toList();
        }
        Map<Class<?>, Integer> contracts = new LinkedHashMap<>();
        for (Class<?> contract : registered) {
            Integer priority = registration.priority(contract);
            // without a priority, the one of its @Priority (Configurable#register(Class, Class[]))
            contracts.put(contract, priority == null ? JaxRsUtils.getPriorityOrder(annotationMetadata(componentClass)) : priority);
        }
        return contracts;
    }

    /**
     * The annotations of a component class: the ones of its bean definition, else the ones of
     * the class, see {@link JaxRsRouteInterceptors#metadata(Class)}.
     */
    private AnnotationMetadata annotationMetadata(Class<?> componentClass) {
        return context.findBeanDefinition(componentClass)
            .<AnnotationMetadata>map(BeanDefinition::getAnnotationMetadata)
            .orElseGet(() -> JaxRsRouteInterceptors.metadata(componentClass));
    }

    @Override
    public <I> List<BeanRegistration<I>> registered(List<BeanRegistration<I>> beans, Class<I> contract) {
        List<BeanRegistration<I>> registered = new ArrayList<>(beans.size());
        for (BeanRegistration<I> bean : beans) {
            if (!JaxRsUtils.isServerProvider(bean.getBeanDefinition().getAnnotationMetadata())) {
                // constrained to the client (@ConstrainedTo)
                continue;
            }
            Registration registration = registrations.get(bean.getBeanDefinition().getBeanType());
            if (registration == null) {
                registered.add(bean);
            } else if (registration.isFor(contract)) {
                Integer priority = registration.priority(contract);
                registered.add(priority == null ? bean : JaxRsRouteInterceptors.registration(bean, priority));
            }
        }
        return registered;
    }

    /**
     * Configure the dynamic features for the resource methods of the routes, at deployment
     * ({@link DynamicFeature#configure}): a feature that fails, fails the startup.
     *
     * @param event The startup event
     */
    @Override
    public void onApplicationEvent(StartupEvent event) {
        if (dynamicFeatures.isEmpty()) {
            return;
        }
        context.findBean(Router.class).ifPresent(router -> router.uriRoutes().forEach(this::components));
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
    private <T> List<T> collect(Class<T> type, List<BeanRegistration<T>> beans, @Nullable Application application) {
        Map<Class<?>, T> instances = new LinkedHashMap<>();
        Set<Class<?>> listed = new HashSet<>();
        if (application != null) {
            listed.addAll(application.getClasses());
            application.getSingletons().forEach(singleton -> listed.add(singleton.getClass()));
        }
        for (BeanRegistration<T> bean : beans) {
            // an application that lists its classes or instances registers only those (JAX-RS 2.3)
            Class<?> beanType = bean.getBeanDefinition().getBeanType();
            if (listed.isEmpty() || listed.contains(beanType)) {
                instances.putIfAbsent(beanType, bean.getBean());
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
     * @param defaultPriorities  The priorities of the annotations of the components, read when
     *                           they were registered
     */
    record Components(List<ContainerRequestFilter> requestFilters,
                      List<ContainerResponseFilter> responseFilters,
                      List<ReaderInterceptor> readerInterceptors,
                      List<WriterInterceptor> writerInterceptors,
                      Map<Object, Map<@Nullable Class<?>, Integer>> priorities,
                      Map<Object, Integer> defaultPriorities) {
        static final Components EMPTY = new Components(List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());

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
            Integer priority = defaultPriorities.get(component);
            return priority == null ? Priorities.USER : priority;
        }
    }

    /**
     * How a component is registered: for which contracts, with which priorities.
     *
     * @param contracts  The contracts, {@code null} for all of the ones it implements
     * @param priorities The priorities, by contract, {@code null} for all of its contracts
     */
    private record Registration(@Nullable Set<Class<?>> contracts, Map<@Nullable Class<?>, Integer> priorities) {

        boolean isFor(Class<?> contract) {
            return contracts == null || contracts.contains(contract);
        }

        @Nullable Integer priority(Class<?> contract) {
            Integer priority = priorities.get(contract);
            return priority == null ? priorities.get(null) : priority;
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
        private final Map<Object, Integer> defaultPriorities = new HashMap<>();
        private final Map<Object, Set<Class<?>>> contracts = new HashMap<>();
        private final Set<Class<?>> classes = new HashSet<>();

        Context(boolean global) {
            this.global = global;
        }

        Components components() {
            List<ContainerRequestFilter> requestFilters = new ArrayList<>();
            List<ContainerResponseFilter> responseFilters = new ArrayList<>();
            List<ReaderInterceptor> readerInterceptors = new ArrayList<>();
            List<WriterInterceptor> writerInterceptors = new ArrayList<>();
            for (Object instance : instances) {
                if (!JaxRsUtils.isServerProvider(metadata(instance.getClass()))) {
                    // constrained to the client (@ConstrainedTo)
                    continue;
                }
                if (instance instanceof ContainerRequestFilter f && isFor(instance, ContainerRequestFilter.class)) {
                    requestFilters.add(f);
                }
                if (instance instanceof ContainerResponseFilter f && isFor(instance, ContainerResponseFilter.class)) {
                    responseFilters.add(f);
                }
                if (instance instanceof ReaderInterceptor i && isFor(instance, ReaderInterceptor.class)) {
                    readerInterceptors.add(i);
                }
                if (instance instanceof WriterInterceptor i && isFor(instance, WriterInterceptor.class)) {
                    writerInterceptors.add(i);
                }
            }
            var components = new Components(requestFilters, responseFilters, readerInterceptors, writerInterceptors, Map.copyOf(priorities), Map.copyOf(defaultPriorities));
            // the explicit priorities, else @Priority, order them; the response filters run in
            // the reverse order (JAX-RS 6.9)
            requestFilters.sort(Comparator.comparingInt(c -> components.priority(c, ContainerRequestFilter.class)));
            responseFilters.sort(Comparator.<ContainerResponseFilter>comparingInt(c -> components.priority(c, ContainerResponseFilter.class)).reversed());
            readerInterceptors.sort(Comparator.comparingInt(c -> components.priority(c, ReaderInterceptor.class)));
            writerInterceptors.sort(Comparator.comparingInt(c -> components.priority(c, WriterInterceptor.class)));
            return components;
        }

        private boolean isFor(Object instance, Class<?> contract) {
            Set<Class<?>> registered = contracts.get(instance);
            return registered == null || registered.contains(contract);
        }

        /**
         * Whether a component class was registered already: a registration of the class or of
         * an instance of it, which is rejected (Configurable).
         */
        private boolean rejected(Class<?> componentClass) {
            boolean registered = global
                ? registeredClasses.contains(componentClass) || registrations.containsKey(componentClass)
                    || registeredInstances.stream().anyMatch(i -> i.getClass() == componentClass)
                : !classes.add(componentClass);
            if (registered) {
                LOG.warn("The component {} is already registered, the registration is ignored", componentClass.getName());
            }
            return registered;
        }

        /**
         * The contracts a component is registered for: the ones it implements, without the
         * others, which are ignored (Configurable), {@code null} if none of them.
         */
        private @Nullable Set<Class<?>> contracts(Class<?> componentClass, Collection<Class<?>> contracts) {
            Set<Class<?>> implemented = new LinkedHashSet<>();
            for (Class<?> contract : contracts) {
                if (contract != null && contract.isAssignableFrom(componentClass)) {
                    implemented.add(contract);
                } else {
                    LOG.warn("The component {} does not implement the contract {}, the contract is ignored",
                        componentClass.getName(), contract == null ? null : contract.getName());
                }
            }
            if (implemented.isEmpty()) {
                LOG.warn("The component {} implements none of its contracts, the registration is ignored", componentClass.getName());
                return null;
            }
            return implemented;
        }

        private FeatureContext register(Class<?> componentClass, @Nullable Object registered,
                                        @Nullable Set<Class<?>> contracts, Map<@Nullable Class<?>, Integer> priorities) {
            if (rejected(componentClass)) {
                return this;
            }
            // an instance is injected before it is used (Configurable.register(Object))
            Object component = registered == null ? null : injected(registered);
            if (global) {
                if (contracts != null || !priorities.isEmpty()) {
                    registrations.put(componentClass, new Registration(contracts, new HashMap<>(priorities)));
                }
                if (component != null) {
                    registerInstance(component);
                } else {
                    registerClass(componentClass);
                }
                return this;
            }
            Object instance = component != null ? component : instance(componentClass);
            if (instances.add(instance)) {
                defaultPriorities.put(instance, JaxRsUtils.getPriorityOrder(metadata(componentClass)));
                if (!priorities.isEmpty()) {
                    this.priorities.put(instance, priorities);
                }
                if (contracts != null) {
                    this.contracts.put(instance, contracts);
                }
                if (instance instanceof Feature feature) {
                    // a feature registered by a dynamic feature configures the same resource
                    // method (DynamicFeature.configure)
                    feature.configure(this);
                }
            }
            return this;
        }

        /**
         * The {@code @Context} members of an instance, like the ones of a bean of its class: proxies
         * of the current request.
         */
        private Object injected(Object component) {
            Object injected = context.inject(component);
            JaxRsReflection.get().injectFields(injected, jakarta.ws.rs.core.Context.class, type -> context.findBean(type).orElse(null));
            return injected;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void registerClass(Class<?> componentClass) {
            registeredClasses.add(componentClass);
            if (Feature.class.isAssignableFrom(componentClass)) {
                // a feature registered by another one is configured too
                Feature feature = (Feature) instance(componentClass);
                registeredInstances.add(feature);
                feature.configure(this);
                return;
            }
            RuntimeBeanDefinition<?> definition = context.containsBean(componentClass) ? null
                : JaxRsReflection.get().beanDefinition(componentClass);
            if (definition != null) {
                // a class the annotation processors never saw becomes a bean
                context.registerBeanDefinition(definition);
            }
        }

        private void registerInstance(Object component) {
            registeredInstances.add(component);
            if (component instanceof Feature feature) {
                feature.configure(this);
            } else if (context.containsBean(component.getClass())) {
                context.registerSingleton(component);
            } else {
                // with the annotations of its class, e.g. its @Priority
                registerSingleton(component);
            }
        }

        @SuppressWarnings("unchecked")
        private <T> void registerSingleton(T component) {
            Class<T> type = (Class<T>) component.getClass();
            context.registerBeanDefinition(RuntimeBeanDefinition.builder(type, () -> component)
                .singleton(true)
                .annotationMetadata(MutableAnnotationMetadata.of(JaxRsRouteInterceptors.metadata(type)))
                .build());
        }

        /**
         * The annotations of a component class: the ones of its bean definition, else the ones
         * of the class, see {@link JaxRsRouteInterceptors#metadata(Class)}.
         */
        private AnnotationMetadata metadata(Class<?> componentClass) {
            return annotationMetadata(componentClass);
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
        public FeatureContext property(String name, @Nullable Object value) {
            // the ones of a global feature are the ones of the configuration
            Map<String, Object> target = global ? JaxRsFeatures.this.properties : properties;
            if (value == null) {
                target.remove(name);
            } else {
                target.put(name, value);
            }
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            return register(componentClass, null, null, Map.of());
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            return register(componentClass, null, null, allContracts(priority));
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            Set<Class<?>> registered = contracts(componentClass, Arrays.asList(contracts));
            return registered == null ? this : register(componentClass, null, registered, Map.of());
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            Set<Class<?>> registered = contracts(componentClass, contracts.keySet());
            return registered == null ? this : register(componentClass, null, registered, priorities(registered, contracts));
        }

        @Override
        public FeatureContext register(Object component) {
            return register(component.getClass(), component, null, Map.of());
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            return register(component.getClass(), component, null, allContracts(priority));
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            Set<Class<?>> registered = contracts(component.getClass(), Arrays.asList(contracts));
            return registered == null ? this : register(component.getClass(), component, registered, Map.of());
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            Set<Class<?>> registered = contracts(component.getClass(), contracts.keySet());
            return registered == null ? this : register(component.getClass(), component, registered, priorities(registered, contracts));
        }

        private static Map<@Nullable Class<?>, Integer> priorities(Set<Class<?>> registered, Map<Class<?>, Integer> contracts) {
            Map<@Nullable Class<?>, Integer> priorities = new HashMap<>();
            for (Class<?> contract : registered) {
                priorities.put(contract, contracts.get(contract));
            }
            return priorities;
        }
    }
}
