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
package io.micronaut.jaxrs.container;

import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.common.JaxRsProviderRegistrations;
import jakarta.inject.Singleton;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * The server {@link Configuration} injected with {@code @Context}: the one of the application of
 * the current request, see {@link JaxRsContextConfiguration}.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
@Singleton
final class JaxRsCurrentConfiguration implements Configuration, JaxRsProviderRegistrations {

    private final JaxRsApplications applications;

    JaxRsCurrentConfiguration(JaxRsApplications applications) {
        this.applications = applications;
    }

    private JaxRsContextConfiguration current() {
        return applications.current().configuration();
    }

    @Override
    public RuntimeType getRuntimeType() {
        return RuntimeType.SERVER;
    }

    @Override
    public Map<String, Object> getProperties() {
        return current().getProperties();
    }

    @Override
    public @Nullable Object getProperty(String name) {
        return current().getProperty(name);
    }

    @Override
    public Collection<String> getPropertyNames() {
        return current().getPropertyNames();
    }

    @Override
    public boolean isEnabled(Feature feature) {
        return current().isEnabled(feature);
    }

    @Override
    public boolean isEnabled(Class<? extends Feature> featureClass) {
        return current().isEnabled(featureClass);
    }

    @Override
    public boolean isRegistered(Object component) {
        return current().isRegistered(component);
    }

    @Override
    public boolean isRegistered(Class<?> componentClass) {
        return current().isRegistered(componentClass);
    }

    @Override
    public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        return current().getContracts(componentClass);
    }

    @Override
    public Set<Class<?>> getClasses() {
        return current().getClasses();
    }

    @Override
    public Set<Object> getInstances() {
        return current().getInstances();
    }

    @Override
    public boolean isRegistered(Class<?> providerClass, Class<?> contract) {
        return current().isRegistered(providerClass, contract);
    }

    @Override
    public @Nullable Integer priority(Class<?> providerClass, Class<?> contract) {
        return current().priority(providerClass, contract);
    }
}
