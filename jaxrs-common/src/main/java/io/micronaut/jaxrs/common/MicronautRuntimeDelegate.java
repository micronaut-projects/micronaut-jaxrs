/*
 * Copyright 2017-2020 original authors
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
package io.micronaut.jaxrs.common;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.NonNull;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.ServiceLoader;

/**
 * RuntimeDelegate implementation for JAX-RS.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public final class MicronautRuntimeDelegate extends RuntimeDelegate {

    private static final Map<Class<?>, HeaderDelegate<?>> HEADER_DELEGATES = new HashMap<>();

    static {
        HEADER_DELEGATES.put(MediaType.class, new MediaTypeHeaderDelegate());
        HEADER_DELEGATES.put(Cookie.class, new CookieHeaderDelegate());
        HEADER_DELEGATES.put(EntityTag.class, new EntityTagDelegate());
        HEADER_DELEGATES.put(NewCookie.class, new NewCookieHeaderDelegate());
        HEADER_DELEGATES.put(Link.class, new LinkDelegate());
        HEADER_DELEGATES.put(CacheControl.class, CacheControlDelegate.INSTANCE);
        HEADER_DELEGATES.put(Object.class, ObjectToStringDelegate.INSTANCE);
    }

    @Override
    public UriBuilder createUriBuilder() {
        return new JaxRsUriBuilder();
    }

    @Override
    public Response.ResponseBuilder createResponseBuilder() {
        return new JaxRsResponseBuilder();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        return new JaxRsVariantListBuilder();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) throws IllegalArgumentException, UnsupportedOperationException {
        JaxRsUtils.requireNonNull("application", application);
        JaxRsUtils.requireNonNull("endpointType", endpointType);
        throw new UnsupportedOperationException("Method createEndpoint(..) not supported by implementation");
    }

    @Override
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) throws IllegalArgumentException {
        JaxRsUtils.requireNonNull("type", type);
        return (HeaderDelegate<T>) HEADER_DELEGATES.getOrDefault(type, ObjectToStringDelegate.INSTANCE);
    }

    @Override
    public Link.Builder createLinkBuilder() {
        return new LinkBuilderImpl();
    }

    @Override
    public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        return new JaxRsSeBootstrapConfiguration.Builder();
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration configuration) {
        JaxRsUtils.requireNonNull("application", application);
        JaxRsUtils.requireNonNull("configuration", configuration);
        for (JaxRsSeBootstrapProvider provider : bootstrapProviders()) {
            return provider.bootstrap(application, configuration);
        }
        return CompletableFuture.failedStage(new UnsupportedOperationException("SE bootstrap is not supported by this runtime delegate"));
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz, SeBootstrap.Configuration configuration) {
        JaxRsUtils.requireNonNull("clazz", clazz);
        JaxRsUtils.requireNonNull("configuration", configuration);
        for (JaxRsSeBootstrapProvider provider : bootstrapProviders()) {
            return provider.bootstrap(clazz, configuration);
        }
        return CompletableFuture.failedStage(new UnsupportedOperationException("SE bootstrap is not supported by this runtime delegate"));
    }

    @Override
    public EntityPart.Builder createEntityPartBuilder(@NonNull String partName) throws IllegalArgumentException {
        return JaxRsEntityPart.withName(partName);
    }

    private Iterable<JaxRsSeBootstrapProvider> bootstrapProviders() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = MicronautRuntimeDelegate.class.getClassLoader();
        }
        return ServiceLoader.load(JaxRsSeBootstrapProvider.class, classLoader);
    }
}
