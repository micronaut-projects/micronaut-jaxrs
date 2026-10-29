/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.jaxrs.client;

import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;
import io.micronaut.http.body.MessageBodyHandlerRegistry;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The JAX-RS Client configuration.
 *
 * @author Denis Stepanov
 * @since 4.6
 */
@Internal
final class JaxRsConfiguration implements Configuration {

    /**
     * The priority of a component or contract registered without one: the one of its
     * {@code @Priority} applies.
     */
    static final int NO_PRIORITY = Integer.MIN_VALUE;

    private static final Logger LOG = LoggerFactory.getLogger(JaxRsConfiguration.class);
    private final Map<String, Object> properties;
    private final List<Component> components;

    // the features whose configure returned true
    private final List<Feature> enabledFeatures;

    // the providers found among the components, and the reading and writing of the entities
    private final JaxRsClientProviders providers;
    private final JaxRsClientEntityIO entityIO;

    public JaxRsConfiguration() {
        this(new LinkedHashMap<>(), new ArrayList<>());
    }

    public JaxRsConfiguration(Map<String, Object> properties, List<Component> components) {
        this(properties, components, new ArrayList<>(), new JaxRsClientProviders.Tables(), null);
    }

    private JaxRsConfiguration(Map<String, Object> properties, List<Component> components, List<Feature> enabledFeatures,
                               JaxRsClientProviders.Tables tables, @Nullable MessageBodyHandlerRegistry handlerRegistry) {
        this.properties = properties;
        this.components = components;
        this.enabledFeatures = enabledFeatures;
        this.providers = new JaxRsClientProviders(this, tables);
        this.entityIO = new JaxRsClientEntityIO(providers, handlerRegistry);
    }

    /**
     * A copy of the configuration: the components keep their instances, and the providers found
     * among them.
     *
     * @return The copy
     */
    JaxRsConfiguration copy() {
        return new JaxRsConfiguration(new LinkedHashMap<>(properties), new ArrayList<>(components), new ArrayList<>(enabledFeatures),
            providers.tables(), entityIO.handlerRegistry());
    }

    /**
     * Forget the providers found among the components, when the components change.
     */
    private void clearCaches() {
        providers.clear();
    }

    /**
     * Replace the properties and components with the ones of another configuration.
     *
     * @param configuration The configuration
     */
    void replaceWith(Configuration configuration) {
        properties.clear();
        components.clear();
        enabledFeatures.clear();
        clearCaches();
        if (configuration instanceof JaxRsConfiguration jaxRsConfiguration) {
            properties.putAll(jaxRsConfiguration.properties);
            components.addAll(jaxRsConfiguration.components);
            enabledFeatures.addAll(jaxRsConfiguration.enabledFeatures);
            return;
        }
        properties.putAll(configuration.getProperties());
        // no contracts: registered for all of the ones it implements
        for (Class<?> componentClass : configuration.getClasses()) {
            Map<Class<?>, Integer> contracts = configuration.getContracts(componentClass);
            if (contracts.isEmpty()) {
                register(componentClass);
            } else {
                register(componentClass, contracts);
            }
        }
        for (Object instance : configuration.getInstances()) {
            Map<Class<?>, Integer> contracts = configuration.getContracts(instance.getClass());
            if (contracts.isEmpty()) {
                register(instance);
            } else {
                register(instance, contracts);
            }
        }
    }

    public void addProperty(String name, @Nullable Object value) {
        // a null value removes the property (JAX-RS, Configurable#property)
        if (value == null) {
            properties.remove(name);
        } else {
            properties.put(name, value);
        }
    }

    /**
     * @param handlerRegistry The body handlers of the HTTP client
     */
    void setHandlerRegistry(MessageBodyHandlerRegistry handlerRegistry) {
        entityIO.setHandlerRegistry(handlerRegistry);
    }

    void register(Class<?> componentClass) {
        register(componentClass, NO_PRIORITY);
    }

    void register(Class<?> componentClass, int priority) {
        register(componentClass, priority, new Class<?>[0]);
    }

    void register(Class<?> componentClass, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(componentClass, NO_PRIORITY, contracts);
    }

    private List<ComponentContract> toContracts(Class<?>[] contracts) {
        return Arrays.stream(contracts).map(c -> new ComponentContract(c, NO_PRIORITY)).toList();
    }

    void register(Class<?> componentClass, int priority, Class<?>... contracts) {
        List<ComponentContract> assignable = assignable(componentClass, toContracts(contracts));
        if (assignable != null) {
            add(new ClassComponent(componentClass, priority, assignable));
        }
    }

    void register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            // registered for no contract: ignored, like the one with no contract classes
            return;
        }
        List<ComponentContract> assignable = assignable(componentClass, toContracts(contracts));
        if (assignable != null) {
            add(new ClassComponent(componentClass, NO_PRIORITY, assignable));
        }
    }

    /**
     * The contracts a component implements: the others are ignored, and a component that
     * implements none of the given contracts is not registered (JAX-RS, {@code Configurable}).
     *
     * @return The contracts, or {@code null} if the component is not registered
     */
    private static @Nullable List<ComponentContract> assignable(Class<?> componentClass, List<ComponentContract> contracts) {
        if (contracts.isEmpty()) {
            return contracts;
        }
        List<ComponentContract> assignable = contracts.stream()
            .filter(contract -> contract.contract().isAssignableFrom(componentClass))
            .toList();
        if (assignable.size() != contracts.size()) {
            LOG.warn("The component {} does not implement the contracts {}: they are ignored", componentClass.getName(),
                contracts.stream().filter(c -> !assignable.contains(c)).map(c -> c.contract().getName()).toList());
        }
        return assignable.isEmpty() ? null : assignable;
    }

    private List<ComponentContract> toContracts(Map<Class<?>, Integer> contracts) {
        return contracts.entrySet().stream().map(e -> new ComponentContract(e.getKey(), e.getValue())).toList();
    }

    void register(Object component) {
        register(component, NO_PRIORITY);
    }

    /**
     * Register a standard provider of the client: several of them may be of the same class, e.g.
     * the readers of the HTTP client.
     *
     * @param component The provider
     */
    void registerStandard(Object component) {
        components.add(InstanceComponent.of(component, NO_PRIORITY, List.of()));
        clearCaches();
    }

    void register(Object component, int priority) {
        register(component, priority, new Class<?>[0]);
    }

    void register(Object component, Class<?>... contracts) {
        if (contracts == null || contracts.length == 0) {
            return;
        }
        register(component, NO_PRIORITY, contracts);
    }

    void register(Object component, int priority, Class<?>... contracts) {
        List<ComponentContract> assignable = assignable(component.getClass(), toContracts(contracts));
        if (assignable != null) {
            add(InstanceComponent.of(component, priority, assignable));
        }
    }

    void register(Object component, Map<Class<?>, Integer> contracts) {
        if (contracts == null || contracts.isEmpty()) {
            // registered for no contract: ignored, like the one with no contract classes
            return;
        }
        List<ComponentContract> assignable = assignable(component.getClass(), toContracts(contracts));
        if (assignable != null) {
            add(InstanceComponent.of(component, NO_PRIORITY, assignable));
        }
    }

    /**
     * Add a component: a feature is configured, and is enabled when it says so (JAX-RS,
     * {@code Feature#configure}). A component of a class that is registered already is ignored
     * (JAX-RS, {@code Configurable}).
     */
    private void add(Component component) {
        Class<?> componentClass = component.componentClass();
        if (isRegistered(componentClass)) {
            LOG.warn("The component {} is registered already: the registration is ignored", componentClass.getName());
            return;
        }
        components.add(component);
        clearCaches();
        if (JaxRsClientProviders.isNotConstrainedToClient(component.metadata())) {
            // kept in the configuration, which may configure a server, but not used by the client
            LOG.warn("The component {} is constrained to another runtime than the client: the client ignores it", componentClass.getName());
            return;
        }
        if (JaxRsClientProviders.registeredFor(component, Feature.class)) {
            Feature feature = providers.injected(component.tryGet(Feature.class));
            if (feature != null && feature.configure(new ClientFeatureContext())) {
                enabledFeatures.add(feature);
            }
        }
    }

    @Override
    public RuntimeType getRuntimeType() {
        return RuntimeType.CLIENT;
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
        return enabledFeatures.contains(feature);
    }

    @Override
    public boolean isEnabled(Class<? extends Feature> featureClass) {
        return enabledFeatures.stream().anyMatch(feature -> feature.getClass() == featureClass);
    }

    @Override
    public boolean isRegistered(Object instance) {
        for (Component component : components) {
            if (component instanceof InstanceComponent instanceComponent && instanceComponent.component.equals(instance)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isRegistered(Class<?> componentClass) {
        for (Component component : components) {
            if (component.is(componentClass)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        return components.stream()
            .filter(c -> c.is(componentClass))
            .flatMap(c -> c.components().stream())
            .collect(Collectors.toMap(component -> component.contract, component -> component.priority, (p1, p2) -> p1));
    }

    @Override
    public Set<Class<?>> getClasses() {
        return components.stream()
            .flatMap(c -> c instanceof ClassComponent classComponent ? Stream.of(classComponent.componentClass) : Stream.empty())
            .collect(Collectors.toSet());
    }

    @Override
    public Set<Object> getInstances() {
        return components.stream()
            .flatMap(c -> c instanceof InstanceComponent instanceComponent ? Stream.of(instanceComponent.component) : Stream.empty())
            .collect(Collectors.toSet());
    }

    /**
     * @return The components, in the order of their registration
     */
    List<Component> components() {
        return components;
    }

    /**
     * @return The providers found among the components
     */
    JaxRsClientProviders providers() {
        return providers;
    }

    /**
     * @return The reading and the writing of the entities
     */
    JaxRsClientEntityIO entityIO() {
        return entityIO;
    }

    /**
     * @return The request filters, in the order of their priorities
     */
    List<ClientRequestFilter> getRequestFilters() {
        return providers.getRequestFilters();
    }

    /**
     * @return The response filters, in the reverse order of their priorities
     */
    List<ClientResponseFilter> getResponseFilters() {
        return providers.getResponseFilters();
    }

    /**
     * @param type The type of the providers
     * @param <T>  The type
     * @return The registered providers of a type, in the order of their priorities
     */
    <T> List<T> getProviders(Class<T> type) {
        return providers.getProviders(type);
    }

    /**
     * Make the providers of this configuration the current ones until the returned scope is
     * closed, see {@link JaxRsClientProviders#current()}.
     *
     * @return The scope, which restores the previous providers once closed
     */
    JaxRsClientProviders.CurrentScope current() {
        return providers.current();
    }

    sealed interface Component {

        boolean is(Class<?> type);

        Class<?> componentClass();

        <T> @Nullable T tryGet(Class<T> type);

        int priority();

        List<ComponentContract> components();

        /**
         * @return The annotations of the class of the component, read when it is registered, see
         * {@link JaxRsRouteInterceptors#metadata(Class)}
         */
        AnnotationMetadata metadata();

    }

    record InstanceComponent(Object component, int priority,
                             List<ComponentContract> components, AnnotationMetadata metadata) implements Component {

        static InstanceComponent of(Object component, int priority, List<ComponentContract> components) {
            return new InstanceComponent(component, priority, components, JaxRsRouteInterceptors.metadata(component.getClass()));
        }

        @Override
        public <T> @Nullable T tryGet(Class<T> type) {
            if (type.isInstance(component)) {
                return (T) component;
            }
            return null;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(component.getClass());
        }

        @Override
        public Class<?> componentClass() {
            return component.getClass();
        }
    }

    /**
     * A component registered as a class: it is instantiated once, when it is first used, and the
     * copies of the configuration share the instance.
     */
    static final class ClassComponent implements Component {

        private final Class<?> componentClass;
        private final int priority;
        private final List<ComponentContract> components;
        private final AnnotationMetadata metadata;
        private volatile @Nullable Object instance;

        ClassComponent(Class<?> componentClass, int priority, List<ComponentContract> components) {
            this.componentClass = componentClass;
            this.priority = priority;
            this.components = components;
            this.metadata = JaxRsRouteInterceptors.metadata(componentClass);
        }

        @Override
        public AnnotationMetadata metadata() {
            return metadata;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> @Nullable T tryGet(Class<T> type) {
            if (type.isAssignableFrom(componentClass)) {
                return (T) instance();
            }
            return null;
        }

        private Object instance() {
            Object current = instance;
            if (current == null) {
                synchronized (this) {
                    current = instance;
                    if (current == null) {
                        current = JaxRsReflection.get().instantiate(componentClass);
                        instance = current;
                    }
                }
            }
            return current;
        }

        @Override
        public boolean is(Class<?> type) {
            return type.equals(componentClass);
        }

        @Override
        public Class<?> componentClass() {
            return componentClass;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public List<ComponentContract> components() {
            return components;
        }
    }

    /**
     * The context a feature registers its components in: the configuration.
     */
    private final class ClientFeatureContext implements FeatureContext {

        @Override
        public Configuration getConfiguration() {
            return JaxRsConfiguration.this;
        }

        @Override
        public FeatureContext property(String name, Object value) {
            addProperty(name, value);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass) {
            JaxRsConfiguration.this.register(componentClass);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, int priority) {
            JaxRsConfiguration.this.register(componentClass, priority);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            JaxRsConfiguration.this.register(componentClass, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
            JaxRsConfiguration.this.register(componentClass, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Object component) {
            JaxRsConfiguration.this.register(component);
            return this;
        }

        @Override
        public FeatureContext register(Object component, int priority) {
            JaxRsConfiguration.this.register(component, priority);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Class<?>... contracts) {
            JaxRsConfiguration.this.register(component, contracts);
            return this;
        }

        @Override
        public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
            JaxRsConfiguration.this.register(component, contracts);
            return this;
        }
    }

    record ComponentContract(Class<?> contract, int priority) {
    }
}
