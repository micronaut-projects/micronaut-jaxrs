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
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import org.jspecify.annotations.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Applies field-only matrix parameter route tolerance after core route validation.
 */
@Internal
public final class JaxRsMatrixFieldRouteVisitor implements TypeElementVisitor<Object, Object> {

    private static final int POSITION = JaxRsTypeElementVisitor.POSITION - 400;
    private static final String CLIENT_ANNOTATION = "io.micronaut.http.client.annotation.Client";

    private ClassElement currentClassElement;

    @Override
    public int getOrder() {
        return POSITION;
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
        if (!element.hasStereotype(Provider.class)) {
            currentClassElement = element;
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (currentClassElement == null || isClientClass() || !isRouteCandidate(element)) {
            return;
        }
        if (!JaxRsTypeElementVisitor.matrixParameterNames(element).isEmpty()) {
            return;
        }
        List<String> matrixFieldNames = JaxRsTypeElementVisitor.matrixFieldNames(currentClassElement);
        if (matrixFieldNames.isEmpty()) {
            return;
        }
        element.stringValue(HttpMethodMapping.class)
            .filter(path -> !path.contains(JaxRsTypeElementVisitor.MATRIX_PARAMETER_ROUTE_PATTERN))
            .map(path -> JaxRsTypeElementVisitor.toMatrixParameterAwareRoute(path, matrixFieldNames))
            .ifPresent(path -> JaxRsTypeElementVisitor.annotateHttpRoute(element, path));
    }

    private boolean isClientClass() {
        return currentClassElement.hasStereotype(CLIENT_ANNOTATION) || currentClassElement.hasAnnotation(CLIENT_ANNOTATION);
    }

    private static boolean isRouteCandidate(MethodElement element) {
        return element.hasStereotype(HttpMethod.class) || element.hasAnnotation(Path.class);
    }
}
