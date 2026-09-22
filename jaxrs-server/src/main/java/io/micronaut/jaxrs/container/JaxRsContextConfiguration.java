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

import io.micronaut.context.BeanProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.SupplierUtil;
import jakarta.inject.Singleton;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The server {@link Configuration} injected with {@code @Context}: the properties, classes and
 * singletons of the {@link Application} of the application.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsContextConfiguration implements Configuration {

    private final Supplier<@Nullable Application> application;
    private final BeanProvider<JaxRsFeatures> features;

    JaxRsContextConfiguration(BeanProvider<Application> application, BeanProvider<JaxRsFeatures> features) {
        this.features = features;
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
        return app == null ? Map.of() : app.getProperties();
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
        return isRegistered(feature);
    }

    @Override
    public boolean isEnabled(Class<? extends Feature> featureClass) {
        return isRegistered(featureClass);
    }

    @Override
    public boolean isRegistered(Object instance) {
        return getInstances().contains(instance) || features.get().isRegistered(instance);
    }

    @Override
    public boolean isRegistered(Class<?> componentClass) {
        if (getClasses().contains(componentClass)) {
            return true;
        }
        for (Object instance : getInstances()) {
            if (instance.getClass() == componentClass) {
                return true;
            }
        }
        // registered by a feature
        return features.get().isRegistered(componentClass);
    }

    @Override
    public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        return Map.of();
    }

    @Override
    public Set<Class<?>> getClasses() {
        Application app = application.get();
        return app == null ? Set.of() : app.getClasses();
    }

    @Override
    public Set<Object> getInstances() {
        Application app = application.get();
        return app == null ? Set.of() : app.getSingletons();
    }
}
