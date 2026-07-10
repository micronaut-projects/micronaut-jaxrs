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

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpRequest;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.jaxrs.common.JaxRsApplicationResources;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The application path provider.
 *
 * @author Denis Stepanov
 * @since 4.6.0
 */
@Internal
@Singleton
public final class ApplicationProvider implements AnnotationMetadataProvider {

    private final String path;
    private final String applicationPath;
    private final AnnotationMetadata annotationMetadata;
    private final Set<String> applicationClassNames;

    /**
     * Constructs a new uri naming strategy for the given property.
     *
     * @param beanContext The bean context
     * @param contextPath The context path
     */

    ApplicationProvider(BeanContext beanContext,
                        @Value("${micronaut.server.context-path}") @Nullable String contextPath) {
        List<BeanDefinition<?>> applicationDefinitions = new ArrayList<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            if (isApplicationDefinition(definition)) {
                applicationDefinitions.add(definition);
            }
        }
        this.annotationMetadata = applicationDefinitions.stream()
            .filter(definition -> definition.hasAnnotation(Primary.class) || definition.hasStereotype(Primary.class))
            .findFirst()
            .or(() -> applicationDefinitions.stream().findFirst())
            .map(AnnotationMetadataProvider::getAnnotationMetadata)
            .orElse(AnnotationMetadata.EMPTY_METADATA);
        this.applicationClassNames = applicationClassNames(beanContext, applicationDefinitions);
        String applicationPath = annotationMetadata.stringValue(ApplicationPath.class)
            .map(path -> URLDecoder.decode(path, StandardCharsets.UTF_8))
            .orElse("/");
        this.applicationPath = normalizeContextPath(applicationPath);
        this.path = concatContextPath(contextPath, applicationPath);

    }

    /**
     * @return The path
     */
    @NonNull
    public String getPath() {
        return path;
    }

    /**
     * @return The application path
     */
    @NonNull
    public String getApplicationPath() {
        return applicationPath;
    }

    /**
     * @param request The HTTP request
     * @return Whether the request is inside the active Jakarta REST application path
     */
    public boolean isApplicationRequest(HttpRequest<?> request) {
        return isApplicationPath(request.getPath());
    }

    /**
     * @return The annotationMetadata
     */
    @NonNull
    public AnnotationMetadata getAnnotationMetadata() {
        return annotationMetadata;
    }

    /**
     * @param resourceClassName The resource class name
     * @return Whether the resource class is allowed by the active application
     */
    public boolean isApplicationResource(String resourceClassName) {
        return applicationClassNames.isEmpty() || applicationClassNames.contains(resourceClassName);
    }

    private static boolean isApplicationDefinition(BeanDefinition<?> definition) {
        Class<?> beanType = definition.getBeanType();
        return beanType != JaxRsApplication.class && isAssignableByName(beanType, Application.class.getName());
    }

    private static boolean isAssignableByName(Class<?> type, String superTypeName) {
        Class<?> current = type;
        while (current != null) {
            if (current.getName().equals(superTypeName)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> applicationClassNames(BeanContext beanContext, List<BeanDefinition<?>> applicationDefinitions) {
        boolean hasGeneratedResourceMetadata = applicationDefinitions.stream()
            .anyMatch(definition -> definition.hasAnnotation(JaxRsApplicationResources.class));
        Set<String> generatedClassNames = applicationDefinitions.stream()
            .flatMap(definition -> Arrays.stream(definition.stringValues(JaxRsApplicationResources.class)))
            .collect(Collectors.toUnmodifiableSet());
        if (hasGeneratedResourceMetadata) {
            return generatedClassNames;
        }
        return applicationDefinitions.stream()
            .flatMap(definition -> applicationClassNames(beanContext, definition))
            .collect(Collectors.toUnmodifiableSet());
    }

    @SuppressWarnings("unchecked")
    private static Stream<String> applicationClassNames(BeanContext beanContext, BeanDefinition<?> definition) {
        BeanDefinition<Object> objectDefinition = (BeanDefinition<Object>) definition;
        Object bean = beanContext.getBean(objectDefinition);
        Object classes;
        if (bean instanceof Application application) {
            classes = application.getClasses();
        } else {
            classes = objectDefinition.findMethod("getClasses")
                .map(method -> method.invoke(bean))
                .orElse(null);
        }
        if (classes instanceof Set<?> set && !set.isEmpty()) {
            return set.stream()
                .filter(Class.class::isInstance)
                .map(Class.class::cast)
                .map(Class::getName);
        }
        return Stream.empty();
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

    private boolean isApplicationPath(String requestPath) {
        return "/".equals(path) || requestPath.equals(path) || requestPath.startsWith(path + '/');
    }

}
