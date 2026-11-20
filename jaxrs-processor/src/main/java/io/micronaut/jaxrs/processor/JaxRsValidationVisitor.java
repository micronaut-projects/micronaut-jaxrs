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
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;

import java.util.Set;

/**
 * Leaves the resource methods and sub-resource locators of the runtime routes out of the
 * validation advice of Micronaut Validation: the JAX-RS runtime validates them itself, in the
 * phases and with the responses of JAX-RS (chapter 7). It runs after the visitor of Micronaut
 * Validation, which marks the methods with constraints for the advice.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
@Internal
public final class JaxRsValidationVisitor implements TypeElementVisitor<Object, Object> {

    /**
     * The position of the visitor: after the one of Micronaut Validation, whose position is 10.
     */
    public static final int POSITION = -100;

    private static final String REQUIRES_VALIDATION = "io.micronaut.inject.validation.RequiresValidation";

    private boolean supported;

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return Set.of("jakarta.ws.rs.*");
    }

    @Override
    public int getOrder() {
        return POSITION;
    }

    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public void start(VisitorContext visitorContext) {
        supported = JaxRsResources.isSupported(visitorContext);
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (!supported || !element.hasDeclaredAnnotation(REQUIRES_VALIDATION)) {
            return;
        }
        if (element.hasStereotype(PathAnnotationMapper.RESOURCE_METHOD) || element.hasDeclaredAnnotation(Path.class)
            || JaxRsAnnotationInheritance.effectiveMetadata(element).hasStereotype(HttpMethod.class)) {
            // validated by the JAX-RS runtime, see JaxRsBeanValidation
            element.removeAnnotation(REQUIRES_VALIDATION);
        }
    }
}
