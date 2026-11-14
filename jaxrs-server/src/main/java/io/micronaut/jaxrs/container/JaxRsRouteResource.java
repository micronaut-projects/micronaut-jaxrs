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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.SupplierUtil;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.jaxrs.common.reflect.JaxRsReflection;
import io.micronaut.web.router.MethodBasedRouteInfo;
import io.micronaut.web.router.RouteInfo;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The resource method a route implements, kept on the route as an attribute: its resource class,
 * its method and annotations, read when first used, and the components the dynamic features
 * register for it.
 *
 * @author Denis Stepanov
 * @since 5.3.0
 */
@Internal
final class JaxRsRouteResource {

    /**
     * The name of the route attribute.
     */
    static final String ATTRIBUTE = JaxRsRouteResource.class.getName();

    private final Class<?> resourceClass;
    private final Supplier<@Nullable Method> method;
    private final Supplier<AnnotationMetadata> annotations;
    private final Supplier<Annotation[]> annotationObjects;
    private volatile JaxRsFeatures.@Nullable Components components;
    // what it is equal by: itself, or the method of a controller route
    private Object identity = this;

    /**
     * @param resourceClass     The resource class
     * @param method            Reads the Java method
     * @param annotations       Reads the annotations of the method, not merged with the ones of its class
     * @param annotationObjects Reads the annotations of the method as objects, as JAX-RS hands them to the providers
     */
    JaxRsRouteResource(Class<?> resourceClass, Supplier<@Nullable Method> method, Supplier<AnnotationMetadata> annotations,
                       Supplier<Annotation[]> annotationObjects) {
        this.resourceClass = resourceClass;
        this.method = SupplierUtil.memoized(method);
        this.annotations = SupplierUtil.memoized(annotations);
        this.annotationObjects = SupplierUtil.memoized(annotationObjects);
    }

    /**
     * @param route The route
     * @return The resource method it implements, {@code null} for a route that is not a JAX-RS resource method
     */
    static @Nullable JaxRsRouteResource of(@Nullable RouteInfo<?> route) {
        if (route == null) {
            return null;
        }
        JaxRsRouteResource resource = route.getAttribute(ATTRIBUTE, JaxRsRouteResource.class).orElse(null);
        if (resource == null && route instanceof MethodBasedRouteInfo<?, ?> methodRoute
            && methodRoute.getAnnotationMetadataProvider().orElse(null) instanceof ExecutableMethod<?, ?> method) {
            // a controller route, of a resource compiled as a controller: equal for the same method
            resource = new JaxRsRouteResource(route.getDeclaringType(), () -> JaxRsReflection.get().targetMethod(method),
                () -> JaxRsReflection.get().methodAnnotations(method), () -> JaxRsReflection.get().annotations(method));
            resource.identity = method;
        }
        return resource;
    }

    /**
     * @return Whether it is the resource method of a controller route, created for each request
     */
    boolean isController() {
        return identity != this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JaxRsRouteResource other && identity.equals(other.identity);
    }

    @Override
    public int hashCode() {
        return identity == this ? System.identityHashCode(this) : identity.hashCode();
    }

    Class<?> resourceClass() {
        return resourceClass;
    }

    @Nullable Method method() {
        return method.get();
    }

    AnnotationMetadata annotations() {
        return annotations.get();
    }

    /**
     * @return The annotations of the method as objects: a fresh array, which the filters and the writers may change
     */
    Annotation[] annotationObjects() {
        return annotationObjects.get().clone();
    }

    /**
     * @param build Builds the components of the resource method
     * @return The components the dynamic features register for the resource method, built once
     */
    JaxRsFeatures.Components components(Function<JaxRsRouteResource, JaxRsFeatures.Components> build) {
        JaxRsFeatures.Components c = components;
        if (c == null) {
            synchronized (this) {
                c = components;
                if (c == null) {
                    c = build.apply(this);
                    components = c;
                }
            }
        }
        return c;
    }
}
