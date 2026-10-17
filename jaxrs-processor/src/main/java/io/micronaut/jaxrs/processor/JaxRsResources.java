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
package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.Internal;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the processor needs to know of the resources the runtime routes route: whether they are
 * created for every request and the members they read from the request.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
final class JaxRsResources {

    private static final String SUPPORT = "io.micronaut.jaxrs.container.JaxRsRouteSupport";

    private static final String ROUTER = "io.micronaut.web.router.builder.";

    private static final List<Class<? extends java.lang.annotation.Annotation>> REQUEST_ANNOTATIONS = List.of(
        PathParam.class, QueryParam.class, MatrixParam.class, HeaderParam.class, CookieParam.class,
        FormParam.class, BeanParam.class, Context.class
    );

    private JaxRsResources() {
    }

    /**
     * @param context The visitor context
     * @return Whether the compilation has the JAX-RS server and the route builder, which route
     * the resources at runtime
     */
    public static boolean isSupported(VisitorContext context) {
        return context.getClassElement(SUPPORT).isPresent()
            && context.getClassElement(ROUTER + "RequestHandler").isPresent();
    }

    /**
     * @param type A resource class
     * @return Whether it is created for every request: its constructor, fields or setters read
     * values of the request
     */
    public static boolean isPerRequest(ClassElement type) {
        return requestConstructor(type) != null || !requestMembers(type).isEmpty();
    }

    /**
     * @param type A class
     * @return Whether the runtime routes create it for every request and fill its members: a
     * resource or sub-resource, with values of the request in its constructor, fields or setters.
     * Other classes with {@code @Context} members, an {@code Application} or a provider, are beans
     * whose members are injected with proxies of the current request.
     */
    public static boolean isCreatedPerRequest(ClassElement type) {
        if (!isPerRequest(type)) {
            return false;
        }
        return type.hasAnnotation(Path.class) || !type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance()
            .annotated(metadata -> metadata.hasStereotype(HttpMethod.class) || metadata.hasDeclaredAnnotation(Path.class))).isEmpty();
    }

    /**
     * The constructor JAX-RS uses for an instance created per request: the public constructor
     * with the most parameters, when it has parameters read from the request.
     *
     * @param type The class
     * @return The constructor, or {@code null}
     */
    public static @Nullable ConstructorElement requestConstructor(ClassElement type) {
        ConstructorElement selected = null;
        for (ConstructorElement constructor : type.getEnclosedElements(ElementQuery.CONSTRUCTORS)) {
            if (constructor.isPublic() && (selected == null || constructor.getParameters().length > selected.getParameters().length)) {
                selected = constructor;
            }
        }
        if (selected == null) {
            return null;
        }
        for (ParameterElement parameter : selected.getParameters()) {
            if (isRequestAnnotated(parameter)) {
                return selected;
            }
        }
        return null;
    }

    /**
     * @param element A parameter, field or setter
     * @return Whether its value is read from the request: annotated with a JAX-RS parameter
     * annotation or {@code @Context}
     */
    public static boolean isRequestAnnotated(Element element) {
        for (Class<? extends java.lang.annotation.Annotation> annotation : REQUEST_ANNOTATIONS) {
            if (element.hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The fields and setters of a class that read values of the request.
     *
     * @param type The class
     * @return The fields and setters
     */
    public static List<MemberElement> requestMembers(ClassElement type) {
        List<MemberElement> members = new ArrayList<>();
        for (FieldElement field : type.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyInstance())) {
            if (isRequestAnnotated(field)) {
                members.add(field);
            }
        }
        for (MethodElement method : type.getEnclosedElements(ElementQuery.ALL_METHODS.onlyInstance())) {
            if (method.getParameters().length == 1 && !method.hasStereotype(HttpMethod.class)
                && !method.getMethodAnnotationMetadata().hasAnnotation(Path.class) && isRequestAnnotated(method)) {
                members.add(method);
            }
        }
        return members;
    }
}
