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
package io.micronaut.jaxrs.processor;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.runtime.http.scope.RequestScope;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A type element visitor that turns a JAX-RS path into a controller.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class JaxRsTypeElementVisitor implements TypeElementVisitor<Object, Object> {

    public static final int POSITION = 200;
    private static final Class<?>[] BINDABLE_TYPES = new Class<?>[]{Context.class, SecurityContext.class, UriInfo.class};
    private ClassElement currentClassElement;

    @Override
    public int getOrder() {
        return POSITION; // higher priority to ensure mutations visible
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Collections.singleton("jakarta.ws.rs.*");
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        currentClassElement = element;
        if (element.hasAnnotation(Path.class) && !element.isAbstract()) {
            element.stringValue(Path.class).ifPresent(p -> {
                element.annotate(Controller.class, builder -> builder.value(p));
                element.annotate(UriMapping.class, builder -> builder.value(p));
            });
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (element.hasStereotype(HttpMethod.class)) {
            if (currentClassElement != null && !currentClassElement.hasAnnotation(Controller.class) && !currentClassElement.isAbstract()) {
                currentClassElement.annotate(Controller.class);
            }
            if ((currentClassElement == null || !currentClassElement.hasAnnotation(Produces.class)) &&
                !element.hasAnnotation(Produces.class)) {
                element.annotate(Produces.class, b -> b.values(MediaType.ALL));
            }
            if ((currentClassElement == null || !currentClassElement.hasAnnotation(Consumes.class)) &&
                !element.hasAnnotation(Consumes.class)) {
                element.annotate(Consumes.class, b -> b.values(MediaType.ALL));
            }
            final ParameterElement[] parameters = element.getParameters();
            for (ParameterElement parameter : parameters) {
                final List<Class<? extends Annotation>> unsupported = getUnsupportedParameterAnnotations();
                for (Class<? extends Annotation> annType : unsupported) {
                    if (parameter.hasAnnotation(annType)) {
                        context.fail("Unsupported JAX-RS annotation used on method: " + annType.getName(), parameter);
                    }
                }
                visitParamOrField(parameter);
                if (Stream.of(
                    HeaderParam.class,
                    QueryParam.class,
                    FormParam.class,
                    MatrixParam.class,
                    PathParam.class,
                    CookieParam.class,
                    BeanParam.class
                ).noneMatch(parameter::hasAnnotation)) {
                    // unannotated, implicit @Body
                    parameter.annotate(Body.class);
                }
            }
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        visitParamOrField(element);
        if (element.hasAnnotation(HeaderParam.class) ||
            element.hasAnnotation(QueryParam.class) ||
            element.hasAnnotation(FormParam.class) ||
            element.hasAnnotation(MatrixParam.class) ||
            element.hasAnnotation(PathParam.class) ||
            element.hasAnnotation(CookieParam.class) ||
            element.hasAnnotation(BeanParam.class)
        ) {
            context.fail("Request scoped bean parameters are currently not supported", element); // todo
        }
    }

    private static void visitParamOrField(TypedElement parameter) {
        mapParam(parameter, HeaderParam.class, Header.class);
        mapParam(parameter, FormParam.class, Body.class);
        mapParam(parameter, QueryParam.class, QueryValue.class);
        mapParam(parameter, CookieParam.class, CookieValue.class);
        mapParam(parameter, PathParam.class, PathVariable.class);
    }

    private static <P extends Annotation> void mapParam(TypedElement parameter, Class<P> jakartaAnnotation, Class<? extends Annotation> mnAnnotation) {
        AnnotationValue<P> ann = parameter.getAnnotation(jakartaAnnotation);
        if (ann != null) {
            parameter.annotate(mnAnnotation, builder -> {
                ann.stringValue().ifPresent(builder::value);
                if (!parameter.isNonNull()) {
                    if (parameter.isPrimitive()) {
                        if (parameter.getType().isAssignable(boolean.class)) {
                            builder.member("defaultValue", "false");
                        } else {
                            builder.member("defaultValue", "0");
                        }
                    } else {
                        parameter.annotate(Nullable.class);
                    }
                }
            });
        }
    }

    private List<Class<? extends Annotation>> getUnsupportedParameterAnnotations() {
        return Arrays.asList(MatrixParam.class, BeanParam.class, Encoded.class);
    }

    @Override
    public void start(VisitorContext visitorContext) {
        for (Class<?> type : BINDABLE_TYPES) {
            visitorContext.getClassElement(type).ifPresent(bindable -> bindable.annotate(Bindable.class));
        }
    }
}
