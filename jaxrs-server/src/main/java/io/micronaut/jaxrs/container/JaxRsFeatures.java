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

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.jaxrs.common.JaxRsContainerMessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.NameBindingPredicate;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import io.micronaut.web.router.Router;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import io.micronaut.context.BeanRegistration;
import jakarta.ws.rs.Priorities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p>The features register their components with a {@link JaxRsFeatureContext}, which validates
 * them: this class keeps the global registrations, discovers and configures the features, and
 * keeps the components and the bound chains of the resource methods, see {@link #bound}.</p>
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
public final class JaxRsFeatures implements JaxRsRouteInterceptors, ApplicationEventListener<StartupEvent> {

    // the contracts of the providers of the server, see Configuration#getContracts
    static final List<Class<?>> CONTRACTS = List.of(
        ContainerRequestFilter.class, ContainerResponseFilter.class, ReaderInterceptor.class, WriterInterceptor.class,
        jakarta.ws.rs.ext.MessageBodyReader.class, jakarta.ws.rs.ext.MessageBodyWriter.class,
        jakarta.ws.rs.ext.ExceptionMapper.class, jakarta.ws.rs.ext.ContextResolver.class,
        jakarta.ws.rs.ext.ParamConverterProvider.class, DynamicFeature.class, Feature.class);

    /**
     * The property of an {@link Application} that disables the features and dynamic features of
     * the Java service loader (JAX-RS 4.1.2).
     */
    static final String LOAD_SERVICES = "jakarta.ws.rs.loadServices";

    // the global registrations, which the JaxRsFeatureContext of the features changes
    final ApplicationContext context;
    final Set<Class<?>> registeredClasses = ConcurrentHashMap.newKeySet();
    final Set<Object> registeredInstances = ConcurrentHashMap.newKeySet();
    // the features whose configure returned true, see Configuration#isEnabled
    final Set<Feature> enabledFeatures = ConcurrentHashMap.newKeySet();
    // the contracts and priorities of the components the features registered globally, by class
    final Map<Class<?>, Registration> registrations = new ConcurrentHashMap<>();
    // the properties the features set, see Configuration#getProperty
    final Map<String, Object> properties = new ConcurrentHashMap<>();
    // the properties of the application the features removed
    final Set<String> removedProperties = ConcurrentHashMap.newKeySet();

    private final JaxRsContainerMessageBodyHandlerRegistry registry;
    private final List<Feature> features;
    // the dynamic features the features register are added when they are configured
    private final List<DynamicFeature> dynamicFeatures;
    private final Map<Object, Components> components = new ConcurrentHashMap<>();
    // the filters and interceptors bound to the resource methods, see #bound
    private final Map<JaxRsBoundProviders.Key, JaxRsBoundProviders> bound = new ConcurrentHashMap<>();
    private volatile boolean configured;
    // the features are configured: the global registrations do not change any more
    private volatile boolean complete;

    JaxRsFeatures(ApplicationContext context, JaxRsContainerMessageBodyHandlerRegistry registry,
                  List<BeanRegistration<Feature>> features, List<BeanRegistration<DynamicFeature>> dynamicFeatures) {
        this.context = context;
        this.registry = registry;
        Application application = context.findBean(Application.class).orElse(null);
        // not the ones constrained to the client (@ConstrainedTo)
        this.features = collect(Feature.class, features, application).stream()
            .filter(this::isServerComponent)
            .toList();
        this.dynamicFeatures = new CopyOnWriteArrayList<>(collect(DynamicFeature.class, dynamicFeatures, application).stream()
            .filter(this::isServerComponent)
            .toList());
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
            JaxRsFeatureContext featureContext = new JaxRsFeatureContext(this, true);
            if (feature.configure(featureContext)) {
                registeredInstances.add(feature);
                enabledFeatures.add(feature);
            }
        }
        complete = true;
        // the dynamic features the features registered configure the resource methods too
        Set<Class<?>> known = new HashSet<>();
        dynamicFeatures.forEach(feature -> known.add(feature.getClass()));
        List<DynamicFeature> registered = new ArrayList<>();
        for (Object instance : registeredInstances) {
            if (instance instanceof DynamicFeature feature && isServerComponent(feature) && known.add(feature.getClass())) {
                registered.add(feature);
            }
        }
        for (Class<?> componentClass : registeredClasses) {
            if (DynamicFeature.class.isAssignableFrom(componentClass) && known.add(componentClass)
                && JaxRsUtils.isServerProvider(annotationMetadata(componentClass))) {
                registered.add((DynamicFeature) instance(componentClass));
            }
        }
        dynamicFeatures.addAll(registered);
    }

    /**
     * @return Whether the features are configured: the global registrations do not change then
     */
    boolean isConfigured() {
        return complete;
    }

    /**
     * @param feature A feature
     * @return Whether it was configured and its {@code configure} returned true
     */
    boolean isEnabled(Feature feature) {
        return enabledFeatures.contains(feature);
    }

    /**
     * @param featureClass A feature class
     * @return Whether a feature of the class was configured and its {@code configure} returned true
     */
    boolean isEnabled(Class<? extends Feature> featureClass) {
        for (Feature feature : enabledFeatures) {
            if (feature.getClass() == featureClass) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The classes the features registered
     */
    Set<Class<?>> registeredClasses() {
        return Collections.unmodifiableSet(registeredClasses);
    }

    /**
     * @return The instances the features registered, and the features that are enabled
     */
    Set<Object> registeredInstances() {
        return Collections.unmodifiableSet(registeredInstances);
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
     * @return The names of the properties of the application the features removed
     */
    Set<String> removedProperties() {
        return removedProperties;
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
    AnnotationMetadata annotationMetadata(Class<?> componentClass) {
        return context.findBeanDefinition(componentClass)
            .<AnnotationMetadata>map(BeanDefinition::getAnnotationMetadata)
            .orElseGet(() -> JaxRsRouteInterceptors.metadata(componentClass));
    }

    /**
     * Whether a component is not constrained to the client (@ConstrainedTo).
     */
    boolean isServerComponent(Object component) {
        return JaxRsUtils.isServerProvider(annotationMetadata(component.getClass()));
    }

    @Override
    public <I> List<BeanRegistration<I>> registered(List<BeanRegistration<I>> beans, Class<I> contract) {
        List<BeanRegistration<I>> registered = new ArrayList<>(beans.size());
        for (BeanRegistration<I> bean : beans) {
            if (!JaxRsUtils.isServerProvider(bean.getBeanDefinition().getAnnotationMetadata())) {
                // constrained to the client (@ConstrainedTo)
                continue;
            }
            Class<?> beanType = bean.getBeanDefinition().getBeanType();
            if (!registry.isRegistered(beanType)) {
                // not listed by an application that lists its classes (JAX-RS 2.3)
                continue;
            }
            Registration registration = registrations.get(beanType);
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
            JaxRsFeatureContext featureContext = new JaxRsFeatureContext(this, false);
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
        if (application != null) {
            // the instances of the application, not the beans of their classes (JAX-RS 2.3.2)
            for (Object singleton : application.getSingletons()) {
                if (type.isInstance(singleton)) {
                    instances.putIfAbsent(singleton.getClass(), type.cast(injected(singleton)));
                }
            }
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
        }
        if (application != null && "false".equalsIgnoreCase(String.valueOf(application.getProperties().get(LOAD_SERVICES)))) {
            // the application disabled the ones of the service loader (JAX-RS 4.1.2)
            return new ArrayList<>(instances.values());
        }
        ClassLoader classLoader = application == null ? context.getClassLoader() : application.getClass().getClassLoader();
        for (T loaded : ServiceLoader.load(type, classLoader)) {
            if (!instances.containsKey(loaded.getClass())) {
                // injected before it is configured, like an instance of the application
                instances.put(loaded.getClass(), type.cast(injected(loaded)));
            }
        }
        return new ArrayList<>(instances.values());
    }

    /**
     * An instance of a component class: the bean, or else the class instantiated and injected.
     */
    Object instance(Class<?> type) {
        if (context.containsBean(type)) {
            return context.getBean(type);
        }
        // a class the annotation processors never saw
        Object instance = JaxRsReflection.get().instantiate(type);
        return injected(instance);
    }

    /**
     * The {@code @Context} members of an instance, like the ones of a bean of its class: proxies
     * of the current request.
     */
    Object injected(Object component) {
        Object injected = context.inject(component);
        JaxRsReflection.get().injectFields(injected, jakarta.ws.rs.core.Context.class, type -> context.findBean(type).orElse(null));
        return injected;
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

    /**
     * The filters and interceptors bound to the resource method of a route, with the name
     * bindings of the route of the current request, which the name binding predicate binds them
     * with, see {@link ContainerNameBindingPredicate}.
     *
     * @param route The route of the resource method, {@code null} if no route matched
     * @return The chains
     */
    JaxRsBoundProviders bound(@Nullable RouteInfo<?> route) {
        Object key = JaxRsRouteSupport.resourceMethodKey(route);
        if (JaxRsRouteSupport.resourceClass(route) == null) {
            key = null;
        }
        List<String> bindings = ServerRequestContext.currentRequest()
            .flatMap(RouteAttributes::getRouteInfo)
            .map(current -> List.copyOf(current.getAnnotationMetadata().getAnnotationNamesByStereotype(NameBinding.class)))
            .orElse(List.of());
        return bound.computeIfAbsent(new JaxRsBoundProviders.Key(key, bindings), k -> new JaxRsBoundProviders(components(route)));
    }

    @Override
    public <I> @Nullable List<I> bound(List<BeanRegistration<I>> beans, Class<I> contract, NameBindingPredicate predicate) {
        int kind;
        if (contract == ReaderInterceptor.class) {
            kind = JaxRsBoundProviders.READER_INTERCEPTORS;
        } else if (contract == WriterInterceptor.class) {
            kind = JaxRsBoundProviders.WRITER_INTERCEPTORS;
        } else {
            return JaxRsRouteInterceptors.super.bound(beans, contract, predicate);
        }
        JaxRsBoundProviders chains = bound(ServerRequestContext.currentRequest().flatMap(RouteAttributes::getRouteInfo).orElse(null));
        Components dynamic = chains.components();
        @SuppressWarnings("unchecked")
        List<I> forRoute = (List<I>) (kind == JaxRsBoundProviders.READER_INTERCEPTORS ? dynamic.readerInterceptors() : dynamic.writerInterceptors());
        return chains.chain(kind, beans, () -> JaxRsRouteInterceptors.bind(
            JaxRsRouteInterceptors.merge(registered(beans, contract), forRoute, interceptor -> dynamic.priority(interceptor, contract)),
            predicate));
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

        /**
         * @param component A component
         * @param contract  The contract it is used as, e.g. {@link ContainerRequestFilter}
         * @return The priority it was registered with for the contract, else its {@code @Priority}
         */
        int priority(Object component, Class<?> contract) {
            return priority(priorities, defaultPriorities, component, contract);
        }

        /**
         * @param priorities        The priorities components were registered with, by contract
         * @param defaultPriorities The priorities of the annotations of the components
         * @param component         A component
         * @param contract          The contract it is used as
         * @return The priority it was registered with for the contract, else its {@code @Priority}
         */
        static int priority(Map<Object, Map<@Nullable Class<?>, Integer>> priorities, Map<Object, Integer> defaultPriorities,
                            Object component, Class<?> contract) {
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
    record Registration(@Nullable Set<Class<?>> contracts, Map<@Nullable Class<?>, Integer> priorities) {

        boolean isFor(Class<?> contract) {
            return contracts == null || contracts.contains(contract);
        }

        @Nullable Integer priority(Class<?> contract) {
            Integer priority = priorities.get(contract);
            return priority == null ? priorities.get(null) : priority;
        }
    }
}
