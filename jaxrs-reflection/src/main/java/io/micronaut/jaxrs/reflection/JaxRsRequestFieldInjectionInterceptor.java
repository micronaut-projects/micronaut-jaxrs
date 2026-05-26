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
package io.micronaut.jaxrs.reflection;

import io.micronaut.aop.InterceptorBean;
import io.micronaut.aop.MethodInterceptor;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.bind.ArgumentBinder;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.RequestBinderRegistry;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.container.JaxRsRequestFieldInjection;
import jakarta.inject.Singleton;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reflection fallback for Jakarta REST request-scoped field injection.
 */
@Internal
@Singleton
@InterceptorBean(JaxRsRequestFieldInjection.class)
final class JaxRsRequestFieldInjectionInterceptor implements MethodInterceptor<Object, Object> {

    private final RequestBinderRegistry requestBinderRegistry;
    private final ConcurrentMap<Class<?>, List<RequestField>> requestFields = new ConcurrentHashMap<>();

    JaxRsRequestFieldInjectionInterceptor(RequestBinderRegistry requestBinderRegistry) {
        this.requestBinderRegistry = requestBinderRegistry;
    }

    @Override
    public @Nullable Object intercept(MethodInvocationContext<Object, Object> context) {
        Optional<HttpRequest<Object>> currentRequest = ServerRequestContext.currentRequest();
        currentRequest.ifPresent(request -> injectFields(context.getTarget(), request));
        return context.proceed();
    }

    private void injectFields(Object target, HttpRequest<?> request) {
        List<RequestField> fields = requestFields.computeIfAbsent(target.getClass(), JaxRsRequestFieldInjectionInterceptor::findRequestFields);
        for (RequestField requestField : fields) {
            injectField(target, request, requestField);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void injectField(Object target, HttpRequest<?> request, RequestField requestField) {
        Argument<T> argument = (Argument<T>) requestField.argument();
        ArgumentBinder<T, HttpRequest<?>> binder = requestBinderRegistry.findArgumentBinder(argument)
            .orElseThrow(() -> new IllegalStateException("No request argument binder for Jakarta REST field [" + argument + "]"));
        ArgumentBinder.BindingResult<T> result = binder.bind(ConversionContext.of(argument), request);
        if (!result.getConversionErrors().isEmpty()) {
            throw new ConversionErrorException(argument, result.getConversionErrors().get(0));
        }
        Optional<T> value = result.getValue();
        Field field = requestField.field();
        try {
            if (value.isPresent()) {
                field.set(target, value.get());
            } else if (!field.getType().isPrimitive()) {
                field.set(target, null);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot inject Jakarta REST field [" + field + "]", e);
        }
    }

    private static List<RequestField> findRequestFields(Class<?> resourceClass) {
        List<RequestField> fields = new ArrayList<>();
        Class<?> current = resourceClass;
        boolean encodedResource = resourceClass.isAnnotationPresent(Encoded.class);
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (isInjectableRequestField(field)) {
                    field.setAccessible(true);
                    fields.add(new RequestField(field, fieldArgument(field, encodedResource || current.isAnnotationPresent(Encoded.class))));
                }
            }
            current = current.getSuperclass();
        }
        return List.copyOf(fields);
    }

    private static boolean isInjectableRequestField(Field field) {
        int modifiers = field.getModifiers();
        return (field.isAnnotationPresent(MatrixParam.class)
            || field.isAnnotationPresent(QueryParam.class)
            || field.isAnnotationPresent(HeaderParam.class)
            || field.isAnnotationPresent(CookieParam.class)
            || field.isAnnotationPresent(PathParam.class))
            && !Modifier.isStatic(modifiers)
            && !Modifier.isFinal(modifiers);
    }

    @SuppressWarnings("unchecked")
    private static Argument<Object> fieldArgument(Field field, boolean encodedResource) {
        Argument<?> rawArgument = Argument.of(field.getGenericType());
        MutableAnnotationMetadata annotationMetadata = new MutableAnnotationMetadata();
        MatrixParam matrixParam = field.getAnnotation(MatrixParam.class);
        QueryParam queryParam = field.getAnnotation(QueryParam.class);
        HeaderParam headerParam = field.getAnnotation(HeaderParam.class);
        CookieParam cookieParam = field.getAnnotation(CookieParam.class);
        PathParam pathParam = field.getAnnotation(PathParam.class);
        if (matrixParam != null) {
            addRequestParam(annotationMetadata, MatrixParam.class, matrixParam.value());
            addBindable(annotationMetadata, MatrixParam.class, matrixParam.value(), fieldDefaultValue(field));
        } else if (queryParam != null) {
            addRequestParam(annotationMetadata, QueryParam.class, queryParam.value());
            addBindable(annotationMetadata, QueryParam.class, queryParam.value(), fieldDefaultValue(field));
        } else if (headerParam != null) {
            addRequestParam(annotationMetadata, HeaderParam.class, headerParam.value());
            addBindable(annotationMetadata, HeaderParam.class, headerParam.value(), fieldDefaultValue(field));
        } else if (cookieParam != null) {
            addRequestParam(annotationMetadata, CookieParam.class, cookieParam.value());
            addBindable(annotationMetadata, CookieParam.class, cookieParam.value(), fieldDefaultValue(field));
        } else if (pathParam != null) {
            addRequestParam(annotationMetadata, PathParam.class, pathParam.value());
            addBindable(annotationMetadata, PathParam.class, pathParam.value(), fieldDefaultValue(field));
        } else {
            throw new IllegalStateException("Unsupported Jakarta REST request field [" + field + "]");
        }
        if (encodedResource || field.isAnnotationPresent(Encoded.class)) {
            annotationMetadata.addAnnotation(Encoded.class.getName(), Map.of());
        }
        return (Argument<Object>) Argument.of(
            (Class<Object>) rawArgument.getType(),
            field.getName(),
            annotationMetadata,
            rawArgument.getTypeParameters()
        );
    }

    private static void addRequestParam(MutableAnnotationMetadata annotationMetadata,
                                        Class<? extends Annotation> annotationType,
                                        String name) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        values.put(AnnotationMetadata.VALUE_MEMBER, name);
        annotationMetadata.addAnnotation(annotationType.getName(), values);
    }

    private static void addBindable(MutableAnnotationMetadata annotationMetadata,
                                    Class<? extends Annotation> annotationType,
                                    String name,
                                    @Nullable DefaultValue defaultValue) {
        Map<CharSequence, Object> values = new LinkedHashMap<>();
        values.put(AnnotationMetadata.VALUE_MEMBER, name);
        if (defaultValue != null) {
            values.put("defaultValue", defaultValue.value());
        }
        annotationMetadata.addStereotype(List.of(annotationType.getName()), Bindable.class.getName(), values);
        annotationMetadata.addAnnotation(Bindable.class.getName(), values);
    }

    private static @Nullable DefaultValue fieldDefaultValue(Field field) {
        DefaultValue defaultValue = field.getAnnotation(DefaultValue.class);
        if (defaultValue != null) {
            return defaultValue;
        }
        if (!field.getType().isPrimitive()) {
            return null;
        }
        return new PrimitiveDefaultValue(field.getType() == boolean.class ? "false" : "0");
    }

    private record PrimitiveDefaultValue(String value) implements DefaultValue {

        @Override
        public Class<DefaultValue> annotationType() {
            return DefaultValue.class;
        }
    }

    private record RequestField(Field field, Argument<?> argument) {
    }
}
