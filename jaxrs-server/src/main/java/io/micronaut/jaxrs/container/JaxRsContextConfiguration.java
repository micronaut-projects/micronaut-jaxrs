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

import io.micronaut.core.annotation.Internal;
import jakarta.inject.Singleton;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Server-side Jakarta REST configuration context bean.
 */
@Internal
@Singleton
final class JaxRsContextConfiguration implements Configuration {

    @Override
    public RuntimeType getRuntimeType() {
        return RuntimeType.SERVER;
    }

    @Override
    public Map<String, Object> getProperties() {
        return Map.of();
    }

    @Override
    public Object getProperty(String name) {
        return null;
    }

    @Override
    public Collection<String> getPropertyNames() {
        return Set.of();
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
        return false;
    }

    @Override
    public boolean isRegistered(Class<?> componentClass) {
        return false;
    }

    @Override
    public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        return Map.of();
    }

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of();
    }

    @Override
    public Set<Object> getInstances() {
        return Set.of();
    }
}
