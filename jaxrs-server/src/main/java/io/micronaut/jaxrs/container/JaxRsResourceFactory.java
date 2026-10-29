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
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.BeanProperty;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.PathVariables;
import io.micronaut.http.form.FormData;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Creates the resources of the routes for a request: the root resources and the classes
 * sub-resource locators return that are created per request, with the values of the request in
 * their constructor, fields and setters, and the resources of the {@code ResourceContext}.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsResourceFactory {

    private static final List<Class<? extends Annotation>> REQUEST_ANNOTATIONS = List.of(
        PathParam.class, QueryParam.class, MatrixParam.class, HeaderParam.class, CookieParam.class,
        FormParam.class, BeanParam.class, Context.class
    );

    private final BeanContext beanContext;
    private final JaxRsRouteSupport support;
    // how to create the classes the locators return
    private final Map<Class<?>, Creator<?>> creators = new ConcurrentHashMap<>();
    // how ResourceContext creates and initializes classes for a request, empty for a class without values of the request
    private final Map<Class<?>, Optional<RequestType<?>>> contextRequestTypes = new ConcurrentHashMap<>();

    /**
     * @param beanContext The bean context
     * @param support     The route support, which reads the values of the request
     */
    JaxRsResourceFactory(BeanContext beanContext, JaxRsRouteSupport support) {
        this.beanContext = beanContext;
        this.support = support;
    }

    /**
     * The instances of a bean, resolved by its type like it is injected, so that a definition
     * that replaces it provides them, e.g. the instances of an application the Java SE bootstrap
     * registers. A singleton is resolved once, when first used.
     */
    <B> Supplier<B> instances(BeanDefinition<B> definition) {
        Class<B> type = definition.getBeanType();
        return definition.isSingleton()
            ? SupplierUtil.memoized(() -> beanContext.getBean(type))
            : () -> beanContext.getBean(type);
    }

    @SuppressWarnings("unchecked")
    /**
     * Create an instance of a class a locator returned, for a request.
     *
     * @param type          The class
     * @param request       The request
     * @param pathVariables The path variables of the request
     * @param form          The form, if the route reads one
     * @param <T>           The type
     * @return The instance
     */
    <T> T createLocated(Class<T> type, HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) {
        return ((Creator<T>) creators.computeIfAbsent(type, this::creator)).create(request, pathVariables, form);
    }

    /**
     * How to create an instance of a class a locator returned, for a request.
     */
    private <T> Creator<T> creator(Class<T> type) {
        BeanDefinition<T> definition = beanContext.findBeanDefinition(type).orElse(null);
        if (definition != null && definition.isSingleton()) {
            Supplier<T> singleton = instances(definition);
            return (request, pathVariables, form) -> singleton.get();
        }
        RequestType<T> created = requestType(type, definition);
        if (created != null) {
            return (request, pathVariables, form) -> create(created, request, pathVariables, form);
        }
        BeanIntrospection<T> introspection = introspection(type);
        if (introspection == null) {
            throw new IllegalStateException("Cannot create the sub-resource " + type.getName());
        }
        return (request, pathVariables, form) -> beanContext.inject(introspection.instantiate());
    }

    /**
     * A resource of {@code ResourceContext.getResource} for a request: the singleton bean, else an
     * instance created for the request with its values, like a class a locator returns.
     *
     * @param type          The class of the resource
     * @param request       The request
     * @param pathVariables The path variables of the request
     * @param <T>           The type
     * @return The resource
     */
    <T> T resource(Class<T> type, HttpRequest<?> request, PathVariables pathVariables) {
        BeanDefinition<T> definition = beanContext.findBeanDefinition(type).orElse(null);
        if (definition != null && definition.isSingleton()) {
            return beanContext.getBean(definition);
        }
        RequestType<T> requestType = contextRequestType(type);
        if (requestType != null) {
            return create(requestType, request, pathVariables, contextForm(request));
        }
        return definition != null ? beanContext.getBean(definition) : beanContext.inject(JaxRsReflection.get().instantiate(type));
    }

    /**
     * Initialize a resource of {@code ResourceContext.initResource} for a request: its injected
     * members, and its fields and setters with the values of the request.
     *
     * @param resource      The resource
     * @param request       The request
     * @param pathVariables The path variables of the request
     * @param <T>           The type
     * @return The resource
     */
    @SuppressWarnings("unchecked")
    <T> T initResource(T resource, HttpRequest<?> request, PathVariables pathVariables) {
        T instance = beanContext.inject(resource);
        RequestType<T> requestType = contextRequestType((Class<T>) instance.getClass());
        if (requestType != null) {
            for (Member<T> member : requestType.members()) {
                JaxRsParameterBinding.ValueReader reader = member.reader();
                member.property().set(instance, reader == null ? null : reader.read(request, pathVariables, contextForm(request)));
            }
        }
        return instance;
    }

    /**
     * The form of the entity for the resources of the {@code ResourceContext}: the one the route
     * read, else an empty one, not the query.
     */
    private static FormData contextForm(HttpRequest<?> request) {
        return request.getAttribute(JaxRsRuntimeRoutes.FORM, FormData.class).orElseGet(() -> new JaxRsEntityForm("", StandardCharsets.UTF_8, false));
    }

    @SuppressWarnings("unchecked")
    private <T> @Nullable RequestType<T> contextRequestType(Class<T> type) {
        return (RequestType<T>) contextRequestTypes.computeIfAbsent(type,
            t -> Optional.ofNullable(requestType(type, beanContext.findBeanDefinition(type).orElse(null)))).orElse(null);
    }

    static boolean isRequestAnnotated(AnnotationMetadata metadata) {
        for (Class<? extends Annotation> annotation : REQUEST_ANNOTATIONS) {
            if (metadata.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How to create a class for every request: with the values of the request in its constructor,
     * fields and setters. {@code null} for a class that is not created per request.
     */
    <T> @Nullable RequestType<T> requestType(Class<T> type, @Nullable BeanDefinition<T> definition) {
        BeanIntrospection<T> introspection = introspection(type);
        Argument<?>[] constructorArguments = definition != null ? definition.getConstructor().getArguments()
            : introspection != null ? introspection.getConstructorArguments() : new Argument<?>[0];
        boolean encodedType = introspection != null && introspection.hasAnnotation(Encoded.class);
        // an @Encoded constructor keeps the values of its parameters encoded
        boolean encodedConstructor = definition != null ? definition.getConstructor().getAnnotationMetadata().hasAnnotation(Encoded.class)
            : introspection != null && introspection.getConstructor().getAnnotationMetadata().hasAnnotation(Encoded.class);
        JaxRsParameterBinding.@Nullable ValueReader[] constructorReaders = new JaxRsParameterBinding.ValueReader[constructorArguments.length];
        boolean perRequest = false;
        boolean usesForm = false;
        boolean encodedForm = false;
        for (int i = 0; i < constructorArguments.length; i++) {
            Argument<?> argument = constructorArguments[i];
            if (isRequestAnnotated(argument.getAnnotationMetadata())) {
                boolean encoded = encodedType || encodedConstructor || argument.getAnnotationMetadata().hasAnnotation(Encoded.class);
                constructorReaders[i] = support.reader(argument.getAnnotationMetadata(), argument, encoded);
                perRequest = true;
                usesForm |= usesForm(argument);
                encodedForm |= usesEncodedForm(argument, encoded);
            }
        }
        List<Member<T>> members = new ArrayList<>();
        if (introspection != null) {
            for (BeanProperty<T, Object> property : introspection.getBeanProperties()) {
                AnnotationMetadata metadata = property.getAnnotationMetadata();
                if (!property.isReadOnly() && isRequestAnnotated(metadata)) {
                    boolean encoded = encodedType || metadata.hasAnnotation(Encoded.class);
                    members.add(new Member<>(property, support.reader(metadata, property.asArgument(), encoded)));
                    usesForm |= usesForm(property.asArgument());
                    encodedForm |= usesEncodedForm(property.asArgument(), encoded);
                }
            }
        }
        if (!perRequest && members.isEmpty()) {
            return null;
        }
        // the constructor parameters read from the request, the @Parameters of a prototype bean
        int count = 0;
        for (JaxRsParameterBinding.ValueReader reader : constructorReaders) {
            if (reader != null) {
                count++;
            }
        }
        int[] requestIndexes = new int[count];
        String[] requestNames = new String[count];
        for (int i = 0, j = 0; i < constructorReaders.length; i++) {
            if (constructorReaders[i] != null) {
                requestIndexes[j] = i;
                requestNames[j++] = constructorArguments[i].getName();
            }
        }
        return new RequestType<>(type, definition != null, introspection, constructorReaders, requestIndexes, requestNames, members,
            usesForm, encodedForm);
    }

    /**
     * Create a type for a request: the prototype bean with the values of the request as its
     * {@code @Parameter}s, or else the class, then its fields and setters with the values of the
     * request.
     */
    <T> T create(RequestType<T> requestType, HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form) {
        JaxRsParameterBinding.@Nullable ValueReader[] readers = requestType.constructorReaders();
        Object[] values = new Object[readers.length];
        int[] requestIndexes = requestType.requestIndexes();
        @Nullable Object[] requestValues = new Object[requestIndexes.length];
        for (int j = 0; j < requestIndexes.length; j++) {
            int i = requestIndexes[j];
            values[i] = Objects.requireNonNull(readers[i]).read(request, pathVariables, form);
            requestValues[j] = values[i];
        }
        T instance;
        BeanIntrospection<T> introspection = requestType.introspection();
        if (requestType.bean() || introspection == null) {
            instance = createBean(requestType.type(), requestType.requestNames(), requestValues);
        } else {
            instance = beanContext.inject(values.length == 0 ? introspection.instantiate() : introspection.instantiate(values));
        }
        for (Member<T> member : requestType.members()) {
            JaxRsParameterBinding.ValueReader reader = member.reader();
            member.property().set(instance, reader == null ? null : reader.read(request, pathVariables, form));
        }
        return instance;
    }

    static boolean usesForm(Argument<?> argument) {
        AnnotationMetadata metadata = argument.getAnnotationMetadata();
        return metadata.hasAnnotation(FormParam.class) || metadata.hasAnnotation(BeanParam.class) && usesForm(argument.getType(), 0);
    }

    /**
     * @param argument A parameter, field or setter read from the request
     * @param encoded  Whether it is {@code @Encoded}, or its method or class
     * @return Whether it is an {@code @Encoded} form field, or a {@code @BeanParam} that reads one
     */
    static boolean usesEncodedForm(Argument<?> argument, boolean encoded) {
        AnnotationMetadata metadata = argument.getAnnotationMetadata();
        return metadata.hasAnnotation(FormParam.class) && encoded
            || metadata.hasAnnotation(BeanParam.class) && usesEncodedForm(argument.getType(), 0);
    }

    /**
     * @return Whether a {@code @BeanParam} type, or one nested in it, reads an {@code @Encoded} form field
     */
    static boolean usesEncodedForm(Class<?> type, int depth) {
        BeanIntrospection<?> introspection = depth > 8 ? null : introspection(type);
        if (introspection == null) {
            return false;
        }
        boolean encodedType = introspection.hasAnnotation(Encoded.class);
        for (Argument<?> argument : introspection.getConstructorArguments()) {
            AnnotationMetadata metadata = argument.getAnnotationMetadata();
            if (metadata.hasAnnotation(FormParam.class) && (encodedType || metadata.hasAnnotation(Encoded.class))) {
                return true;
            }
        }
        for (BeanProperty<?, ?> property : introspection.getBeanProperties()) {
            AnnotationMetadata metadata = property.getAnnotationMetadata();
            if (metadata.hasAnnotation(FormParam.class) && (encodedType || metadata.hasAnnotation(Encoded.class))
                || metadata.hasAnnotation(BeanParam.class) && usesEncodedForm(property.getType(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether a {@code @BeanParam} type, or one nested in it, reads a form field
     */
    private static boolean usesForm(Class<?> type, int depth) {
        if (depth > 8) {
            return false;
        }
        BeanIntrospection<?> introspection = introspection(type);
        if (introspection == null) {
            return false;
        }
        for (Argument<?> argument : introspection.getConstructorArguments()) {
            if (argument.getAnnotationMetadata().hasAnnotation(FormParam.class)) {
                return true;
            }
        }
        for (BeanProperty<?, ?> property : introspection.getBeanProperties()) {
            AnnotationMetadata metadata = property.getAnnotationMetadata();
            if (metadata.hasAnnotation(FormParam.class)
                || metadata.hasAnnotation(BeanParam.class) && usesForm(property.getType(), depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create a resource for a request: a resource whose constructor reads values of the request
     * is a prototype, and gets them as its {@code @Parameter}s.
     *
     * @param type   The resource class
     * @param names  The names of the constructor parameters read from the request
     * @param values Their values
     * @param <T>    The type of the resource
     * @return The resource
     */
    private <T> T createBean(Class<T> type, String[] names, @Nullable Object[] values) {
        if (!beanContext.containsBean(type)) {
            // a sub-resource or a bean parameter that is not a bean
            return JaxRsReflection.get().instantiate(type);
        }
        Map<String, Object> arguments = new java.util.HashMap<>(names.length * 2);
        for (int i = 0; i < names.length; i++) {
            arguments.put(names[i], values[i]);
        }
        return beanContext.createBean(type, arguments);
    }

    private static <T> @Nullable BeanIntrospection<T> introspection(Class<T> type) {
        return JaxRsReflection.get().introspection(type);
    }

    /**
     * Creates an instance of a class for a request.
     *
     * @param <T> The class
     */
    @FunctionalInterface
    private interface Creator<T> {
        T create(HttpRequest<?> request, PathVariables pathVariables, @Nullable FormData form);
    }

    /**
     * A field or setter of a type created per request.
     *
     * @param property The property
     * @param reader   How to read its value
     * @param <T>      The type that has it
     */
    record Member<T>(BeanProperty<T, Object> property, JaxRsParameterBinding.@Nullable ValueReader reader) {
    }

    /**
     * A type created per request.
     *
     * @param type               The type
     * @param bean               Whether it is a bean
     * @param introspection      Its introspection
     * @param constructorReaders How to read the values of the parameters of its constructor
     * @param requestIndexes     The indexes of the parameters of its constructor read from the request
     * @param requestNames       Their names
     * @param members            Its fields and setters with values of the request
     * @param usesForm           Whether it reads the form
     * @param encodedForm        Whether it reads an {@code @Encoded} form field, which needs the raw values of the form
     * @param <T>                The type
     */
    record RequestType<T>(Class<T> type, boolean bean, @Nullable BeanIntrospection<T> introspection,
                               JaxRsParameterBinding.@Nullable ValueReader[] constructorReaders, int[] requestIndexes, String[] requestNames,
                                  List<Member<T>> members, boolean usesForm, boolean encodedForm) {
    }
}
