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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The context a feature registers its components with ({@link Feature#configure}): the one of a
 * feature, whose registrations are the global ones of the {@link JaxRsFeatures}, or the one of a
 * dynamic feature for a resource method, whose registrations are the ones of the method. It
 * validates the registrations: a component registered already, or for contracts it does not
 * implement, is ignored (Configurable).
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
final class JaxRsFeatureContext implements FeatureContext {
    // a property a dynamic feature removed for the resource method
    private static final Object REMOVED = new Object();

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsFeatures.class);

    private final JaxRsFeatures features;
    private final boolean global;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Set<Object> instances = new LinkedHashSet<>();
    // the instances a dynamic feature registered as a class
    private final Set<Object> classInstances = new HashSet<>();
    private final Map<Object, Map<@Nullable Class<?>, Integer>> priorities = new HashMap<>();
    private final Map<Object, Integer> defaultPriorities = new HashMap<>();
    private final Map<Object, Set<Class<?>>> contracts = new HashMap<>();
    private final Set<Class<?>> classes = new HashSet<>();

    /**
     * @param features The features, whose global registrations a global context changes
     * @param global   Whether it is the context of a feature, else the one of a dynamic feature
     *                 for a resource method
     */
    JaxRsFeatureContext(JaxRsFeatures features, boolean global) {
        this.features = features;
        this.global = global;
    }

    JaxRsFeatures.Components components() {
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
        var components = new JaxRsFeatures.Components(requestFilters, responseFilters, readerInterceptors, writerInterceptors, Map.copyOf(priorities), Map.copyOf(defaultPriorities));
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
            ? features.registeredClasses.contains(componentClass) || features.registrations.containsKey(componentClass)
                || features.registeredInstances.stream().anyMatch(i -> i.getClass() == componentClass)
                // a class or an instance of the application, or a class that is discovered
                || features.context.getBean(Configuration.class).isRegistered(componentClass)
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
        Object component = registered == null ? null : features.injected(registered);
        if (global) {
            if (contracts != null || !priorities.isEmpty()) {
                features.registrations.put(componentClass, new JaxRsFeatures.Registration(contracts, new HashMap<>(priorities)));
            }
            if (component != null) {
                registerInstance(component, contracts);
            } else {
                registerClass(componentClass, contracts);
            }
            return this;
        }
        Object instance = component != null ? component : features.instance(componentClass);
        if (component == null) {
            classInstances.add(instance);
        }
        if (instances.add(instance)) {
            defaultPriorities.put(instance, JaxRsUtils.getPriorityOrder(metadata(componentClass)));
            if (!priorities.isEmpty()) {
                this.priorities.put(instance, priorities);
            }
            if (contracts != null) {
                this.contracts.put(instance, contracts);
            }
            if (instance instanceof Feature feature && features.isServerComponent(feature)) {
                // a feature registered by a dynamic feature configures the same resource
                // method (DynamicFeature.configure), unless constrained to the client
                feature.configure(this);
            }
        }
        return this;
    }

    /**
     * Whether a component class is registered as a feature: it is one, and it is registered
     * for all of its contracts or for the one of a feature.
     */
    private static boolean isFeature(Class<?> componentClass, @Nullable Set<Class<?>> contracts) {
        return Feature.class.isAssignableFrom(componentClass) && (contracts == null || contracts.contains(Feature.class));
    }

    /**
     * Whether a component class is registered for a contract that is not the one of a feature,
     * e.g. a feature that is a filter too, which is processed for each one (Configurable).
     */
    private static boolean isOtherProvider(Class<?> componentClass, @Nullable Set<Class<?>> contracts) {
        for (Class<?> contract : JaxRsFeatures.CONTRACTS) {
            if (contract != Feature.class && contract.isAssignableFrom(componentClass)
                && (contracts == null || contracts.contains(contract))) {
                return true;
            }
        }
        return false;
    }

    private void registerClass(Class<?> componentClass, @Nullable Set<Class<?>> contracts) {
        features.registeredClasses.add(componentClass);
        if (isFeature(componentClass, contracts) && JaxRsUtils.isServerProvider(features.annotationMetadata(componentClass))) {
            // a feature registered by another one is configured too, unless constrained to the client (@ConstrainedTo)
            Feature feature = (Feature) features.instance(componentClass);
            features.registeredInstances.add(feature);
            if (feature.configure(this)) {
                features.enabledFeatures.add(feature);
            }
        }
        if (!isOtherProvider(componentClass, contracts)) {
            return;
        }
        RuntimeBeanDefinition<?> definition = features.context.containsBean(componentClass) ? null
            : JaxRsReflection.get().beanDefinition(componentClass);
        if (definition != null) {
            // a class the annotation processors never saw becomes a bean
            features.context.registerBeanDefinition(definition);
        }
    }

    private void registerInstance(Object component, @Nullable Set<Class<?>> contracts) {
        features.registeredInstances.add(component);
        if (component instanceof Feature feature && isFeature(component.getClass(), contracts)
            && features.isServerComponent(feature) && feature.configure(this)) {
            // unless constrained to the client (@ConstrainedTo)
            features.enabledFeatures.add(feature);
        }
        if (!isOtherProvider(component.getClass(), contracts)) {
            return;
        }
        if (features.context.containsBean(component.getClass())) {
            features.context.registerSingleton(component);
        } else {
            // with the annotations of its class, e.g. its @Priority
            registerSingleton(component);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void registerSingleton(T component) {
        Class<T> type = (Class<T>) component.getClass();
        features.context.registerBeanDefinition(RuntimeBeanDefinition.builder(type, () -> component)
            .singleton(true)
            .annotationMetadata(MutableAnnotationMetadata.of(JaxRsRouteInterceptors.metadata(type)))
            .build());
    }

    /**
     * The annotations of a component class: the ones of its bean definition, else the ones
     * of the class, see {@link JaxRsRouteInterceptors#metadata(Class)}.
     */
    private AnnotationMetadata metadata(Class<?> componentClass) {
        return features.annotationMetadata(componentClass);
    }

    private static Map<@Nullable Class<?>, Integer> allContracts(int priority) {
        Map<@Nullable Class<?>, Integer> priorities = new HashMap<>();
        priorities.put(null, priority);
        return priorities;
    }

    @Override
    public Configuration getConfiguration() {
        Configuration configuration = features.context.getBean(Configuration.class);
        // the one of a dynamic feature has the properties and components of the method too
        return global ? configuration : new MethodConfiguration(configuration, this);
    }

    @Override
    public FeatureContext property(String name, @Nullable Object value) {
        if (!global) {
            // removed for the resource method: a null value hides the global one
            properties.put(name, value == null ? REMOVED : value);
            return this;
        }
        // the ones of a global feature are the ones of the configuration
        if (value == null) {
            features.properties.remove(name);
            features.removedProperties.add(name);
        } else {
            features.properties.put(name, value);
            features.removedProperties.remove(name);
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

    /**
     * The configuration a dynamic feature sees for a resource method: the one of the application,
     * with the properties and components registered for the method (Configurable#getConfiguration).
     *
     * @param global The configuration of the application
     * @param local  The context of the resource method
     */
    private record MethodConfiguration(Configuration global, JaxRsFeatureContext local) implements Configuration {

        @Override
        public jakarta.ws.rs.RuntimeType getRuntimeType() {
            return global.getRuntimeType();
        }

        @Override
        public Map<String, Object> getProperties() {
            Map<String, Object> properties = new LinkedHashMap<>(global.getProperties());
            local.properties.forEach((name, value) -> {
                if (value == REMOVED) {
                    properties.remove(name);
                } else {
                    properties.put(name, value);
                }
            });
            return Collections.unmodifiableMap(properties);
        }

        @Override
        public @Nullable Object getProperty(String name) {
            return getProperties().get(name);
        }

        @Override
        public Collection<String> getPropertyNames() {
            return getProperties().keySet();
        }

        @Override
        public boolean isEnabled(Feature feature) {
            return global.isEnabled(feature);
        }

        @Override
        public boolean isEnabled(Class<? extends Feature> featureClass) {
            return global.isEnabled(featureClass);
        }

        @Override
        public boolean isRegistered(Object component) {
            return local.instances.contains(component) || global.isRegistered(component);
        }

        @Override
        public boolean isRegistered(Class<?> componentClass) {
            return local.classes.contains(componentClass) || global.isRegistered(componentClass);
        }

        @Override
        public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
            for (Object instance : local.instances) {
                if (instance.getClass() == componentClass) {
                    Set<Class<?>> registered = local.contracts.get(instance);
                    Collection<Class<?>> contracts = registered != null ? registered
                        : JaxRsFeatures.CONTRACTS.stream().filter(contract -> contract.isInstance(instance)).toList();
                    Map<Class<?>, Integer> byContract = new LinkedHashMap<>();
                    for (Class<?> contract : contracts) {
                        byContract.put(contract, JaxRsFeatures.Components.priority(local.priorities, local.defaultPriorities, instance, contract));
                    }
                    return byContract;
                }
            }
            return global.getContracts(componentClass);
        }

        @Override
        public Set<Class<?>> getClasses() {
            Set<Class<?>> classes = new LinkedHashSet<>(global.getClasses());
            local.classInstances.forEach(instance -> classes.add(instance.getClass()));
            return Collections.unmodifiableSet(classes);
        }

        @Override
        public Set<Object> getInstances() {
            Set<Object> instances = new LinkedHashSet<>(global.getInstances());
            for (Object instance : local.instances) {
                if (!local.classInstances.contains(instance)) {
                    instances.add(instance);
                }
            }
            return Collections.unmodifiableSet(instances);
        }
    }
}
