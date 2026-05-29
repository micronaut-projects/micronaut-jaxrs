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

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.core.annotation.Internal;
import io.micronaut.jaxrs.common.JaxRsApplicationResources;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.visitor.TypeElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import io.micronaut.sourcegen.model.MethodDef;
import jakarta.inject.Named;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates bean and resource metadata for unannotated Jakarta REST application classes.
 */
@Internal
public final class JaxRsApplicationProcessor implements TypeElementVisitor<Object, Object> {

    private static final String APPLICATION_TYPE = Application.class.getName();
    private static final String APPLICATION_PATH_TYPE = ApplicationPath.class.getName();
    private static final String MICRONAUT_CONTEXT_ANNOTATION_PREFIX = "io.micronaut.context.annotation.";
    private static final String SCOPE_ANNOTATION = Scope.class.getName();
    private static final String[] EMPTY_APPLICATION_RESOURCES = new String[0];
    private static final Set<String> SUPPORTED_ANNOTATION_NAMES = Set.of(
        APPLICATION_PATH_TYPE,
        Path.class.getName(),
        Provider.class.getName(),
        Context.class.getName()
    );

    private final Set<String> generatedFactories = new LinkedHashSet<>();

    @Override
    public int getOrder() {
        return JaxRsTypeElementVisitor.POSITION;
    }

    @NonNull
    @Override
    public VisitorKind getVisitorKind() {
        return VisitorKind.ISOLATING;
    }

    @Override
    public TypeElementQuery query() {
        return TypeElementQuery.onlyClass();
    }

    @Override
    public Set<String> getSupportedAnnotationNames() {
        return SUPPORTED_ANNOTATION_NAMES;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        if (!shouldGenerateFactory(element)) {
            return;
        }
        if (hasAssignableContextField(element)) {
            generatedFactories.add(element.getName());
            annotateApplicationBean(element);
            return;
        }
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        generateFactory(sourceGenerator, element, context);
    }

    private boolean shouldGenerateFactory(ClassElement element) {
        String applicationClassName = element.getName();
        return !generatedFactories.contains(applicationClassName)
            && !applicationClassName.equals(APPLICATION_TYPE)
            && !element.isAbstract()
            && !element.isInterface()
            && !element.isEnum()
            && element.isAssignable(APPLICATION_TYPE)
            && !hasBeanDefiningAnnotation(element)
            && hasAccessibleNoArgumentConstructor(element);
    }

    private static boolean hasBeanDefiningAnnotation(ClassElement element) {
        if (element.hasAnnotation(APPLICATION_PATH_TYPE) || element.hasStereotype(SCOPE_ANNOTATION)) {
            return true;
        }
        return element.getAnnotationMetadata()
            .getAnnotationNames()
            .stream()
            .anyMatch(annotationName -> annotationName.startsWith(MICRONAUT_CONTEXT_ANNOTATION_PREFIX));
    }

    private static boolean hasAccessibleNoArgumentConstructor(ClassElement element) {
        List<ConstructorElement> constructors = element.getEnclosedElements(ElementQuery.CONSTRUCTORS.onlyDeclared());
        if (constructors.isEmpty()) {
            return true;
        }
        return constructors.stream()
            .anyMatch(constructor -> constructor.getParameters().length == 0 && !constructor.isPrivate());
    }

    private static void annotateApplicationBean(ClassElement element) {
        element.annotate(Singleton.class);
        element.annotate(Primary.class);
        element.annotate(Named.class);
        if (!element.hasAnnotation(JaxRsApplicationResources.class)) {
            element.annotate(JaxRsApplicationResources.class, builder -> builder.values(EMPTY_APPLICATION_RESOURCES));
        }
    }

    private void generateFactory(SourceGenerator sourceGenerator,
                                 ClassElement applicationType,
                                 VisitorContext context) {
        String applicationClassName = applicationType.getName();
        if (!generatedFactories.add(applicationClassName)) {
            return;
        }
        String packageName = applicationType.getPackageName();
        String factorySimpleName = applicationType.getSimpleName() + "$JaxRsApplicationFactory";
        String factoryClassName = packageName.isEmpty() ? factorySimpleName : packageName + "." + factorySimpleName;
        sourceGenerator.write(
            ClassDef.builder(factoryClassName)
                .addAnnotation(Factory.class)
                .addMethod(applicationFactoryMethod(applicationType))
                .build(),
            context,
            applicationType
        );
    }

    private static MethodDef applicationFactoryMethod(ClassElement applicationType) {
        ClassTypeDef applicationTypeDef = ClassTypeDef.of(applicationType);
        MethodDef.MethodDefBuilder method = MethodDef.builder("application")
            .returns(applicationTypeDef)
            .addAnnotation(Singleton.class)
            .addAnnotation(Primary.class)
            .addAnnotation(Named.class);
        applicationResourcesAnnotation(applicationType).forEach(method::addAnnotation);
        return method.build((ignored, parameters) -> applicationTypeDef.instantiate().returning());
    }

    private static List<AnnotationDef> applicationResourcesAnnotation(ClassElement applicationType) {
        if (!applicationType.hasAnnotation(JaxRsApplicationResources.class)) {
            return List.of();
        }
        String[] resourceNames = applicationType.stringValues(JaxRsApplicationResources.class);
        return List.of(
            AnnotationDef.builder(JaxRsApplicationResources.class)
                .addMember("value", Arrays.stream(resourceNames).map(Object.class::cast).toList())
                .build()
        );
    }

    private static boolean hasAssignableContextField(ClassElement applicationType) {
        return applicationType.getEnclosedElements(ElementQuery.ALL_FIELDS.onlyDeclared())
            .stream()
            .anyMatch(JaxRsApplicationProcessor::isAssignableContextField);
    }

    private static boolean isAssignableContextField(FieldElement field) {
        return field.hasAnnotation(Context.class)
            && !field.isPrivate()
            && !field.isStatic()
            && !field.isFinal();
    }
}
