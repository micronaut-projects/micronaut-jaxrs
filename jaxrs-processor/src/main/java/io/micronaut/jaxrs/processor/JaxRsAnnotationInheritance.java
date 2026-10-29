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

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.annotation.MutableAnnotationMetadataDelegate;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The annotation inheritance of JAX-RS (JAX-RS 3.6) applied to the elements the processor visits:
 * the inherited annotations a class or a method does not keep are removed.
 *
 * @author Denis Stepanov
 * @since 5.2.0
 */
final class JaxRsAnnotationInheritance {

    private static final String JAX_RS_PACKAGE = "jakarta.ws.rs.";
    private static final String HTTP_ANNOTATION_PACKAGE = "io.micronaut.http.annotation.";
    // the class annotations that stay inherited: the path a subclass is routed with, and the ones
    // that are not annotations of a resource
    private static final Set<String> KEPT_CLASS_ANNOTATIONS = Set.of(Path.class.getName(), Provider.class.getName(),
        "jakarta.ws.rs.ApplicationPath");
    private static final Map<String, String> MAPPED_CLASS_ANNOTATIONS = Map.of(
        jakarta.ws.rs.Produces.class.getName(), Produces.class.getName(),
        jakarta.ws.rs.Consumes.class.getName(), Consumes.class.getName());

    private JaxRsAnnotationInheritance() {
    }

    /**
     * The JAX-RS annotations of a class are not inherited by its subclasses (JAX-RS 3.6), e.g. its
     * {@code @Produces}, nor the Micronaut ones they are mapped to. The {@code @Path} of a
     * superclass is kept: a subclass is routed with it.
     *
     * @param element The class
     */
    static void normalizeClass(ClassElement element) {
        AnnotationMetadata declared = element.getDeclaredMetadata();
        for (String name : element.getAnnotationNames()) {
            if (name.startsWith(JAX_RS_PACKAGE) && !KEPT_CLASS_ANNOTATIONS.contains(name) && !declared.hasDeclaredAnnotation(name)) {
                element.removeAnnotation(name);
                String mapped = MAPPED_CLASS_ANNOTATIONS.get(name);
                if (mapped != null && !declared.hasDeclaredAnnotation(mapped)) {
                    element.removeAnnotation(mapped);
                }
            }
        }
    }

    /**
     * The annotations of a super method are inherited only when the method declares no JAX-RS
     * annotation, on itself or on its parameters, otherwise they are all ignored (JAX-RS 3.6): the
     * method, and its parameters, lose the JAX-RS annotations they inherit, and the Micronaut ones
     * these are mapped to.
     *
     * @param element The method
     */
    static void normalizeMethod(MethodElement element) {
        if (element.getOverriddenMethods().isEmpty()) {
            return;
        }
        if (!declaresJaxRsAnnotation(element)) {
            inheritFromEffectiveMethod(element);
            return;
        }
        discardInherited(element, element.getMethodAnnotationMetadata(), element.getDeclaredMethodAnnotationMetadata());
        for (ParameterElement parameter : element.getParameters()) {
            discardInherited(parameter, parameter, parameter.getAnnotationMetadata().getDeclaredMetadata());
        }
    }

    /**
     * A method without JAX-RS annotations inherits those of one overridden method (JAX-RS 3.6): the
     * nearest one of a superclass that declares any, else one of an interface. The annotations of
     * the others, which are merged with them, are discarded.
     */
    private static void inheritFromEffectiveMethod(MethodElement element) {
        List<MethodElement> declaring = element.getOverriddenMethods().stream()
            .filter(JaxRsAnnotationInheritance::declaresJaxRsAnnotation)
            .toList();
        if (declaring.size() < 2) {
            // the merged annotations are the ones of the only method that declares any
            return;
        }
        MethodElement effective = null;
        for (ClassElement type = element.getOwningType().getSuperType().orElse(null); type != null && effective == null;
             type = type.getSuperType().orElse(null)) {
            String typeName = type.getName();
            effective = declaring.stream().filter(method -> method.getDeclaringType().getName().equals(typeName)).findFirst().orElse(null);
        }
        if (effective == null) {
            effective = declaring.stream().filter(method -> method.getDeclaringType().isInterface()).findFirst().orElse(declaring.get(0));
        }
        inherit(element, element.getMethodAnnotationMetadata(), element.getDeclaredMethodAnnotationMetadata(), effective.getDeclaredMethodAnnotationMetadata());
        ParameterElement[] parameters = element.getParameters();
        ParameterElement[] effectiveParameters = effective.getParameters();
        for (int i = 0; i < parameters.length && i < effectiveParameters.length; i++) {
            inherit(parameters[i], parameters[i], parameters[i].getAnnotationMetadata().getDeclaredMetadata(),
                effectiveParameters[i].getAnnotationMetadata().getDeclaredMetadata());
        }
    }

    /**
     * Replace the inherited JAX-RS annotations, and the Micronaut ones they are mapped to, with the
     * ones of the effective overridden method.
     */
    private static void inherit(MutableAnnotationMetadataDelegate<?> element, AnnotationMetadata metadata, AnnotationMetadata declared,
                                AnnotationMetadata source) {
        discardInherited(element, metadata, declared);
        for (String name : source.getDeclaredAnnotationNames()) {
            AnnotationValue<Annotation> value = source.getDeclaredAnnotation(name);
            if (value != null && isRequestAnnotation(source, name)) {
                element.annotate(value);
            }
        }
    }

    private static boolean declaresJaxRsAnnotation(MethodElement method) {
        boolean declares = declaresJaxRsAnnotation(method.getDeclaredMethodAnnotationMetadata());
        for (ParameterElement parameter : method.getParameters()) {
            declares |= declaresJaxRsAnnotation(parameter.getAnnotationMetadata().getDeclaredMetadata());
        }
        return declares;
    }

    private static boolean declaresJaxRsAnnotation(AnnotationMetadata declared) {
        // a custom request method designator too, e.g. a PATCH
        return declared.getDeclaredAnnotationNames().stream().anyMatch(name -> name.startsWith(JAX_RS_PACKAGE))
            || declared.hasDeclaredStereotype(HttpMethod.class);
    }

    private static boolean isRequestAnnotation(AnnotationMetadata metadata, String name) {
        return name.startsWith(JAX_RS_PACKAGE) || name.startsWith(HTTP_ANNOTATION_PACKAGE)
            || metadata.getAnnotationNamesByStereotype(HttpMethod.class.getName()).contains(name);
    }

    private static void discardInherited(MutableAnnotationMetadataDelegate<?> element, AnnotationMetadata metadata, AnnotationMetadata declared) {
        Set<String> declaredNames = declared.getDeclaredAnnotationNames();
        for (String name : metadata.getAnnotationNames()) {
            if (!declaredNames.contains(name) && isRequestAnnotation(metadata, name)) {
                element.removeAnnotation(name);
            }
        }
        for (String stereotype : List.of(HttpMethod.class.getName(), HttpMethodMapping.class.getName(), UriMapping.class.getName())) {
            if (!declared.hasStereotype(stereotype)) {
                element.removeStereotype(stereotype);
            }
        }
    }
}
