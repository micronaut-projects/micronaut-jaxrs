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

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.inject.annotation.MutableAnnotationMetadata;
import io.micronaut.jaxrs.common.JaxRsArgumentUtil;
import io.micronaut.jaxrs.common.JaxRsGenericEntity;
import io.micronaut.jaxrs.common.JaxRsMutableResponse;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteInfo;
import jakarta.ws.rs.core.GenericEntity;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The entity of a response, prepared for the response filters and the writers: the response of
 * the application unwrapped, the entity of a {@link GenericEntity}, the raw and generic types the
 * writers see (JAX-RS 3.3.3, 4.2.2), and the annotations given with the entity followed by the
 * ones of the resource method.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
final class JaxRsResponseEntity {

    private static final Annotation[] NONE = new Annotation[0];

    // the Java annotations of the resource methods, by resource method, see #methodAnnotations
    private final Map<Object, Annotation[]> methodAnnotations = new ConcurrentHashMap<>();

    /**
     * Prepare the entity of a response.
     *
     * @param routeInfo The route of a resource method, {@code null} if no route matched
     * @param request   The request
     * @param response  The response
     * @return The prepared entity
     */
    Prepared prepare(@Nullable RouteInfo<?> routeInfo, HttpRequest<?> request, MutableHttpResponse<?> response) {
        MutableHttpResponse<?> mutableHttpResponse = response;
        Object body;
        if (request.getMethod() == HttpMethod.HEAD) {
            body = RouteAttributes.getHeadBody(mutableHttpResponse).orElse(null);
        } else {
            body = mutableHttpResponse.getBody().orElse(null);
        }
        if (body instanceof JaxRsMutableResponse jrs) {
            final MutableHttpResponse<?> jaxRsResponse = jrs.getResponse();
            mutableHttpResponse.getAttributes().forEach(jaxRsResponse::setAttribute);
            mutableHttpResponse.getHeaders().forEach((name, value) -> {
                for (String val : value) {
                    jaxRsResponse.header(name, val);
                }
            });
            mutableHttpResponse = jaxRsResponse;
            body = mutableHttpResponse.getBody().orElse(null);
        }
        Argument<?> bodyArgument;
        // the annotations given with the entity
        Annotation[] entityAnnotations = null;
        // the type of a GenericEntity of the application is the one the writers see (JAX-RS 4.2.2)
        boolean declaredType = false;
        if (body instanceof JaxRsGenericEntity<?> genericEntity) {
            bodyArgument = genericEntity.asArgument();
            entityAnnotations = genericEntity.getAnnotations();
            mutableHttpResponse.body(genericEntity.getEntity());
            body = genericEntity.getEntity();
            // e.g. the GenericEntity a resource method returns, with its type arguments: the
            // class of the entity otherwise, like for the writers of JAX-RS
            declaredType = bodyArgument.getTypeParameters().length > 0 && bodyArgument.getType().isInstance(body);
        } else if (body instanceof GenericEntity<?> genericEntity) {
            body = genericEntity.getEntity();
            bodyArgument = JaxRsArgumentUtil.from(genericEntity);
            mutableHttpResponse.body(genericEntity.getEntity());
            declaredType = true;
        } else if (body != null && routeInfo != null) {
            bodyArgument = routeInfo.getResponseBodyType();
            // the declared type of the route, with its type arguments, e.g. List<Item> for an
            // ArrayList, is the generic type the writers see (JAX-RS 4.2.2)
            declaredType = bodyArgument.getTypeParameters().length > 0 && bodyArgument.getType().isInstance(body);
        } else {
            bodyArgument = Argument.OBJECT_ARGUMENT;
        }
        // the declared type is the generic type of the entity, see below
        Argument<?> declared = declaredType ? bodyArgument : null;
        if (body != null && !declaredType && !bodyArgument.getType().equals(body.getClass())) {
            bodyArgument = Argument.of(body.getClass(), bodyArgument.getAnnotationMetadata());
        }

        Annotation[] methodAnnotations = methodAnnotations(routeInfo);
        if (methodAnnotations != null) {
            // the writers see the annotations given with the entity, then the Java annotations of
            // the resource method, like JAX-RS passes them
            if (entityAnnotations == null) {
                entityAnnotations = methodAnnotations;
            } else {
                Annotation[] all = Arrays.copyOf(entityAnnotations, entityAnnotations.length + methodAnnotations.length);
                System.arraycopy(methodAnnotations, 0, all, entityAnnotations.length, methodAnnotations.length);
                entityAnnotations = all;
            }
            bodyArgument = Argument.of(bodyArgument.getType(), JaxRsArgumentUtil.createAnnotationMetadata(entityAnnotations), bodyArgument.getTypeParameters());
        } else {
            Argument<?> returnType = routeInfo == null ? Argument.VOID : routeInfo.getReturnType().asArgument();
            MutableAnnotationMetadata mutableAnnotationMetadata = new MutableAnnotationMetadata();
            mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(returnType.getAnnotationMetadata()));
            mutableAnnotationMetadata.addAnnotationMetadata(MutableAnnotationMetadata.of(bodyArgument.getAnnotationMetadata()));
            bodyArgument = Argument.of(bodyArgument.getType(), mutableAnnotationMetadata, bodyArgument.getTypeParameters());
        }
        if (declared != null && body != null && body.getClass() != declared.asType()) {
            // the class of the entity is the raw type, the declared type the generic type
            // (JAX-RS 3.3.3), e.g. ArrayList and List<Item>
            bodyArgument = JaxRsArgumentUtil.of(body.getClass(), declared, bodyArgument.getAnnotationMetadata());
        }
        return new Prepared(mutableHttpResponse, body, bodyArgument, entityAnnotations);
    }

    /**
     * The Java annotations of the resource method of a route, read once per resource method: a
     * fresh array, which the filters and the writers may change, {@code null} for a route that
     * is not a JAX-RS resource method.
     */
    private Annotation @Nullable [] methodAnnotations(@Nullable RouteInfo<?> routeInfo) {
        Object key = JaxRsRouteSupport.resourceMethodKey(routeInfo);
        if (key == null) {
            return JaxRsRouteSupport.resourceMethodAnnotationObjects(routeInfo);
        }
        Annotation[] annotations = methodAnnotations.computeIfAbsent(key, k -> {
            Annotation[] read = JaxRsRouteSupport.resourceMethodAnnotationObjects(routeInfo);
            return read == null ? NONE : read;
        });
        return annotations == NONE ? null : annotations.clone();
    }

    /**
     * The prepared entity of a response.
     *
     * @param response          The response, the one of the application unwrapped
     * @param body              The entity, {@code null} for none
     * @param bodyArgument      The type the writers see, with the annotations of the entity
     * @param entityAnnotations The annotations given with the entity, then the ones of the
     *                          resource method, {@code null} for none
     */
    record Prepared(MutableHttpResponse<?> response, @Nullable Object body, Argument<?> bodyArgument,
                    Annotation @Nullable [] entityAnnotations) {
    }
}
