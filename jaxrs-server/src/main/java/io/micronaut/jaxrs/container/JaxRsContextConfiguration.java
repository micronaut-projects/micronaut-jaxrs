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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.jaxrs.common.JaxRsProviderRegistrations;
import io.micronaut.jaxrs.common.JaxRsRouteInterceptors;
import io.micronaut.jaxrs.common.JaxRsUtils;
import jakarta.ws.rs.Path;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.SupplierUtil;
import jakarta.inject.Singleton;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The server {@link Configuration} injected with {@code @Context}: the properties, classes and
 * singletons of the {@link Application} of the application, a view of the registrations of the
 * application and of its features, which are the {@link JaxRsProviderRegistrations} of the
 * provider lookups too. Once the features are configured, the registrations do not change: the
 * ones of a class are cached then.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsContextConfiguration implements Configuration, JaxRsProviderRegistrations {

    private final Supplier<@Nullable Application> application;
    private final BeanProvider<JaxRsFeatures> features;
    private final Supplier<Set<Class<?>>> discovered;
    private final BeanContext context;
    // the registrations of the classes, once the features are configured
    private final Map<Class<?>, Boolean> registered = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<Class<?>, Integer>> contracts = new ConcurrentHashMap<>();

    JaxRsContextConfiguration(BeanProvider<Application> application, BeanProvider<JaxRsFeatures> features, BeanContext context) {
        this.features = features;
        this.context = context;
        this.discovered = SupplierUtil.memoized(() -> discovered(context));
        // the Application may itself be injected with this configuration
        this.application = SupplierUtil.memoized(() -> application.isPresent() ? application.get() : null);
    }

    @Override
    public RuntimeType getRuntimeType() {
        return RuntimeType.SERVER;
    }

    @Override
    public Map<String, Object> getProperties() {
        Application app = application.get();
        Map<String, Object> set = features.get().properties();
        Set<String> removed = features.get().removedProperties();
        if (set.isEmpty() && removed.isEmpty()) {
            return app == null ? Map.of() : app.getProperties();
        }
        // the ones of the application, without the ones the features removed, and the ones the
        // features set (FeatureContext#property)
        Map<String, Object> properties = new LinkedHashMap<>();
        if (app != null) {
            properties.putAll(app.getProperties());
        }
        properties.keySet().removeAll(removed);
        properties.putAll(set);
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
        // configured, and its configure returned true
        return features.get().isEnabled(feature);
    }

    @Override
    public boolean isEnabled(Class<? extends Feature> featureClass) {
        return features.get().isEnabled(featureClass);
    }

    @Override
    public boolean isRegistered(Object instance) {
        Application app = application.get();
        return app != null && app.getSingletons().contains(instance) || features.get().isRegistered(instance);
    }

    @Override
    public boolean isRegistered(Class<?> componentClass) {
        if (features.get().isConfigured()) {
            return registered.computeIfAbsent(componentClass, this::registered);
        }
        return registered(componentClass);
    }

    @Override
    public boolean isRegistered(Class<?> providerClass, Class<?> contract) {
        if (!isRegistered(providerClass)) {
            return false;
        }
        Map<Class<?>, Integer> registeredContracts = getContracts(providerClass);
        return registeredContracts.isEmpty() || registeredContracts.containsKey(contract);
    }

    @Override
    public @Nullable Integer priority(Class<?> providerClass, Class<?> contract) {
        return isRegistered(providerClass) ? getContracts(providerClass).get(contract) : null;
    }

    private boolean registered(Class<?> componentClass) {
        Application app = application.get();
        if (app != null && app.getClasses().contains(componentClass)) {
            return true;
        }
        if (app != null) {
            for (Object instance : app.getSingletons()) {
                if (instance.getClass() == componentClass) {
                    return true;
                }
            }
        }
        // registered by a feature, or discovered
        return features.get().isRegistered(componentClass) || isDiscovered(componentClass);
    }

    /**
     * Whether a class is registered because it is discovered: by an application that lists none
     * (JAX-RS 2.3), see {@link #getClasses()}.
     */
    private boolean isDiscovered(Class<?> componentClass) {
        Application app = application.get();
        return (app == null || app.getClasses().isEmpty() && app.getSingletons().isEmpty())
            && discovered.get().contains(componentClass);
    }

    @Override
    public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        if (features.get().isConfigured()) {
            return contracts.computeIfAbsent(componentClass, this::contracts);
        }
        return contracts(componentClass);
    }

    private Map<Class<?>, Integer> contracts(Class<?> componentClass) {
        Map<Class<?>, Integer> contracts = features.get().contracts(componentClass);
        if (!contracts.isEmpty() || !isRegistered(componentClass)) {
            return contracts;
        }
        // registered by the application, or discovered: for all of the contracts it implements,
        // with the priority of its @Priority
        AnnotationMetadata metadata = context.findBeanDefinition(componentClass)
            .<AnnotationMetadata>map(BeanDefinition::getAnnotationMetadata)
            .orElseGet(() -> JaxRsRouteInterceptors.metadata(componentClass));
        int priority = JaxRsUtils.getPriorityOrder(metadata);
        Map<Class<?>, Integer> implemented = new LinkedHashMap<>();
        for (Class<?> contract : JaxRsFeatures.CONTRACTS) {
            if (contract.isAssignableFrom(componentClass)) {
                implemented.put(contract, priority);
            }
        }
        return Collections.unmodifiableMap(implemented);
    }

    @Override
    public Set<Class<?>> getClasses() {
        Application app = application.get();
        Set<Class<?>> classes = new LinkedHashSet<>();
        if (app != null) {
            classes.addAll(app.getClasses());
        }
        if (classes.isEmpty() && (app == null || app.getSingletons().isEmpty())) {
            // an application that lists none registers the ones that are discovered (JAX-RS 2.3)
            classes.addAll(discovered.get());
        }
        // and the ones the features registered
        classes.addAll(features.get().registeredClasses());
        return Collections.unmodifiableSet(classes);
    }

    @Override
    public Set<Object> getInstances() {
        Application app = application.get();
        Set<Object> instances = new LinkedHashSet<>();
        if (app != null) {
            instances.addAll(app.getSingletons());
        }
        // and the ones the features registered, the enabled features included
        instances.addAll(features.get().registeredInstances());
        return Collections.unmodifiableSet(instances);
    }

    /**
     * The component classes that are discovered: the resources and the server providers and
     * features of the application, without the ones of this module.
     */
    private static Set<Class<?>> discovered(BeanContext context) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (BeanDefinition<?> definition : context.getBeanDefinitions(Qualifiers.byStereotype(Path.class))) {
            classes.add(definition.getBeanType());
        }
        for (Class<?> contract : JaxRsFeatures.CONTRACTS) {
            for (BeanDefinition<?> definition : context.getBeanDefinitions(contract)) {
                Class<?> type = definition.getBeanType();
                if (!type.getName().startsWith("io.micronaut.") && JaxRsUtils.isServerProvider(definition.getAnnotationMetadata())) {
                    classes.add(type);
                }
            }
        }
        return Collections.unmodifiableSet(classes);
    }
}
