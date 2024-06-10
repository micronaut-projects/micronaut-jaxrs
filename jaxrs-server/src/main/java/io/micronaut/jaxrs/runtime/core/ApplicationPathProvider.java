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
package io.micronaut.jaxrs.runtime.core;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The application path provider.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public final class ApplicationPathProvider {

    private final String path;

    /**
     * Constructs a new uri naming strategy for the given property.
     *
     * @param beanContext The bean context
     * @param contextPath The context path
     */

    ApplicationPathProvider(BeanContext beanContext,
                            @Value("${micronaut.server.context-path}") @Nullable String contextPath) {
        String applicationPath = beanContext.findBeanDefinition(Application.class).flatMap(bd -> bd.stringValue(ApplicationPath.class))
            .map(path -> URLDecoder.decode(path, StandardCharsets.UTF_8))
            .orElse("/");
        this.path = concatContextPath(contextPath, applicationPath);
    }

    /**
     * @return The path
     */
    @NonNull
    public String getPath() {
        return path;
    }

    @NonNull
    private String concatContextPath(@Nullable String contextPath, @NonNull String applicationPath) {
        if (contextPath == null || contextPath.isEmpty() || contextPath.endsWith("/")) {
            return normalizeContextPath(applicationPath);
        }
        return normalizeContextPath(normalizeContextPath(contextPath).concat(normalizeContextPath(applicationPath)));
    }

    private String normalizeContextPath(String contextPath) {
        if (!contextPath.startsWith("/")) {
            contextPath = '/' + contextPath;
        }
        if (contextPath.charAt(contextPath.length() - 1) == '/') {
            contextPath = contextPath.substring(0, contextPath.length() - 1);
        }
        return contextPath;
    }

}
