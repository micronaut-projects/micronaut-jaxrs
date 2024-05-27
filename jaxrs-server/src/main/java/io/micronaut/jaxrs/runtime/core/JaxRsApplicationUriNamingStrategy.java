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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.naming.conventions.PropertyConvention;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.web.router.RouteBuilder;
import io.micronaut.web.router.naming.HyphenatedUriNamingStrategy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

/**
 * Configures a URI naming strategy based on the {@link ApplicationPath} annotation.
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
@Requires(classes = RouteBuilder.UriNamingStrategy.class)
@Requires(beans = Application.class)
@Replaces(HyphenatedUriNamingStrategy.class)
@Primary
public class JaxRsApplicationUriNamingStrategy extends HyphenatedUriNamingStrategy {

    private final String contextPath;

    /**
     * Constructs a new uri naming strategy for the given property.
     *
     * @param beanContext The bean context
     */
    @Inject
    public JaxRsApplicationUriNamingStrategy(BeanContext beanContext, @Value("${micronaut.server.context-path}") @Nullable String contextPath) {
        super(contextPath);
        this.contextPath = normalizeContextPath(
                beanContext.getBeanDefinition(Application.class)
                    .stringValue(ApplicationPath.class)
                    .orElse("/")
        );
    }

    @Deprecated
    public JaxRsApplicationUriNamingStrategy(BeanContext beanContext) {
        this(beanContext, null);
    }

    @Override
    public String resolveUri(Class type) {
        return contextPath + super.resolveUri(type);
    }

    @Override
    public @NonNull String resolveUri(BeanDefinition<?> beanDefinition) {
        return contextPath + super.resolveUri(beanDefinition);
    }

    @Override
    public @NonNull String resolveUri(String property) {
        return contextPath + super.resolveUri(property);
    }

    @Override
    public @NonNull String resolveUri(Class type, PropertyConvention id) {
        return contextPath + super.resolveUri(type, id);
    }

    private String normalizeContextPath(String contextPath) {
        if (contextPath.charAt(0) != '/') {
            contextPath = '/' + contextPath;
        }
        if (contextPath.charAt(contextPath.length() - 1) == '/') {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }
        return contextPath;
    }
}

