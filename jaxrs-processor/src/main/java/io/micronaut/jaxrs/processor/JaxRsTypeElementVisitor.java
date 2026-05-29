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

import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NextMajorVersion;
import io.micronaut.core.annotation.ReflectiveAccess;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.CookieValue;
import io.micronaut.http.annotation.CustomHttpMethod;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Head;
import io.micronaut.http.annotation.HttpMethodMapping;
import io.micronaut.http.annotation.Options;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.annotation.RequestBean;
import io.micronaut.http.annotation.Trace;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.jaxrs.common.JaxRsBindableMetadata;
import io.micronaut.jaxrs.common.JaxRsMessageBodyProvider;
import io.micronaut.jaxrs.common.JaxRsRouteScore;
import io.micronaut.jaxrs.common.JaxRsResourceTemplateMetadata;
import io.micronaut.jaxrs.common.JaxRsSubResourceLocatorMetadata;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.GenericPlaceholderElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.sourcegen.generator.SourceGenerator;
import io.micronaut.sourcegen.generator.SourceGenerators;
import io.micronaut.sourcegen.model.AnnotationDef;
import io.micronaut.sourcegen.model.ClassDef;
import io.micronaut.sourcegen.model.ClassTypeDef;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.ConstrainedTo;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A type element visitor that turns a JAX-RS path into a controller.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class JaxRsTypeElementVisitor implements TypeElementVisitor<Object, Object> {

    public static final int POSITION = 200;
    public static final String OPTION_FAIL_ON_UNSUPPORTED = "micronaut.jaxrs.fail.on.unsupported";
    static final String SUB_RESOURCE_LOCATOR_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsSubResourceLocator";
    static final String RECURSIVE_REMAINING_ROUTE_VARIABLE = "jaxrsRecursiveRemaining";
    static final String MATRIX_PARAMETER_ROUTE_PATTERN = ":;[^/]*|";
    static final String DYNAMIC_SUB_RESOURCE_LOCATOR_HTTP_METHOD = "JAXRS_SUB_RESOURCE_LOCATOR";
    private static final String CLIENT_ANNOTATION = "io.micronaut.http.client.annotation.Client";
    private static final String CONSTRUCTOR_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsConstructorInjection";
    private static final String REQUEST_FIELD_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsRequestFieldInjection";
    private static final String PATH_PARAM_BINDING_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsPathParamBinding";
    private static final String ENTITY_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsEntity";
    private static final String RESOURCE_TEMPLATE_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsResourceTemplate";
    private static final String BEAN_PARAM_INTROSPECTION_PREFIX = "$JaxRsBeanParamIntrospection";
    private static final String JAKARTA_CONSUMES_ANNOTATION = "jakarta.ws.rs.Consumes";
    private static final String JAKARTA_PRODUCES_ANNOTATION = "jakarta.ws.rs.Produces";
    private static final Set<String> JAX_RS_HTTP_METHOD_ANNOTATIONS = Set.of(
        "jakarta.ws.rs.GET",
        "jakarta.ws.rs.POST",
        "jakarta.ws.rs.PUT",
        "jakarta.ws.rs.PATCH",
        "jakarta.ws.rs.DELETE",
        "jakarta.ws.rs.HEAD",
        "jakarta.ws.rs.OPTIONS"
    );
    private static final List<Class<? extends Annotation>> MICRONAUT_ROUTE_ANNOTATIONS = List.of(
        Get.class,
        Post.class,
        Put.class,
        Patch.class,
        Delete.class,
        Trace.class,
        Options.class,
        Head.class,
        CustomHttpMethod.class
    );
    private static final Map<String, String> HTTP_METHOD_NAMES = Map.of(
        Get.class.getName(), "GET",
        Post.class.getName(), "POST",
        Put.class.getName(), "PUT",
        Patch.class.getName(), "PATCH",
        Delete.class.getName(), "DELETE",
        Trace.class.getName(), "TRACE",
        Options.class.getName(), "OPTIONS",
        Head.class.getName(), "HEAD"
    );
    private static final Class<?>[] BINDABLE_TYPES = new Class<?>[] {Context.class, SecurityContext.class, UriInfo.class};
    private ClassElement currentClassElement;
    private final Set<String> generatedBeanParamIntrospections = new HashSet<>();

    private final List<Class<? extends Annotation>> JAX_RS_BINDING_ANNOTATIONS = List.of(
        HeaderParam.class,
        QueryParam.class,
        FormParam.class,
        MatrixParam.class,
        PathParam.class,
        CookieParam.class,
        BeanParam.class,
        Context.class
    );

    // Backwards compatibility
    @NextMajorVersion("Allow only inject values annotated with @Context")
    private final List<String> JAX_RS_BINDING_TYPES = List.of(
        HttpHeaders.class.getName(),
        Cookie.class.getName(),
        SecurityContext.class.getName(),
        UriInfo.class.getName(),
        "jakarta.servlet.ServletContext",
        "jakarta.servlet.ServletRequest",
        "jakarta.servlet.http.HttpServletRequest",
        "jakarta.servlet.ServletResponse",
        "jakarta.servlet.http.HttpServletResponse",
        "jakarta.servlet.ServletConfig"
    );

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
        annotateMessageBodyProviderMetadata(element);
        if (element.hasStereotype(Provider.class)) {
            if (element.isEnum()) {
                return;
            }
            element.annotate(Singleton.class);
            element.annotate(Named.class);
            return;
        }
        currentClassElement = element;
        if (!failOnUnsupported(context) && hasRequestParamField(element)) {
            annotateBeanParamType(element, new HashSet<>(), context);
        }
        if (element.hasAnnotation(Path.class) && !element.isAbstract()) {
            jaxRsPath(element).ifPresent(p -> {
                element.annotate(Controller.class, builder -> builder.value(p));
                element.annotate(UriMapping.class, builder -> builder.value(p));
            });
            List<String> rootMatrixParameterNames = rootMatrixParameterNames(element);
            if (!rootMatrixParameterNames.isEmpty()) {
                markMatrixAwareClassPath(rootMatrixParameterNames.get(0));
            } else {
                matrixFieldNames(element).stream()
                    .findFirst()
                    .ifPresent(this::markMatrixAwareClassPath);
            }
            if (hasRequestParamField(element)) {
                markRequestFieldInjection();
            }
        }
    }

    private static void annotateMessageBodyProviderMetadata(ClassElement element) {
        boolean reader = element.isAssignable(MessageBodyReader.class);
        boolean writer = element.isAssignable(MessageBodyWriter.class);
        if (!reader && !writer) {
            return;
        }
        element.annotate(JaxRsMessageBodyProvider.class, builder -> {
            builder.member(JaxRsMessageBodyProvider.MEMBER_SERVER, isServerMessageBodyProvider(element));
            if (reader) {
                providerType(element, MessageBodyReader.class).ifPresent(providerType -> {
                    builder.member(JaxRsMessageBodyProvider.MEMBER_READER_TYPE, new AnnotationClassValue<>(providerTypeName(providerType.type())));
                    builder.member(JaxRsMessageBodyProvider.MEMBER_READER_TYPE_VARIABLE, providerType.typeVariable());
                });
                builder.member(JaxRsMessageBodyProvider.MEMBER_CONSUMES, providerMediaTypes(element, Consumes.class));
            }
            if (writer) {
                providerType(element, MessageBodyWriter.class).ifPresent(providerType -> {
                    builder.member(JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE, new AnnotationClassValue<>(providerTypeName(providerType.type())));
                    builder.member(JaxRsMessageBodyProvider.MEMBER_WRITER_TYPE_VARIABLE, providerType.typeVariable());
                });
                builder.member(JaxRsMessageBodyProvider.MEMBER_PRODUCES, providerMediaTypes(element, Produces.class));
            }
        });
    }

    private static Optional<ProviderTypeMetadata> providerType(ClassElement element, Class<?> providerType) {
        return element.getTypeArguments(providerType)
            .values()
            .stream()
            .findFirst()
            .map(type -> new ProviderTypeMetadata(providerRawType(type), type.isTypeVariable()));
    }

    private static ClassElement providerRawType(ClassElement type) {
        if (type instanceof GenericPlaceholderElement genericPlaceholderElement) {
            return genericPlaceholderElement.getBounds()
                .stream()
                .findFirst()
                .map(JaxRsTypeElementVisitor::rawProviderClassElement)
                .orElseGet(() -> ClassElement.of(Object.class));
        }
        return rawProviderClassElement(type);
    }

    private static ClassElement rawProviderClassElement(ClassElement type) {
        if (type.isArray() || type.isPrimitive()) {
            return type;
        }
        return type.getRawClassElement();
    }

    private static String providerTypeName(ClassElement type) {
        if (!type.isArray()) {
            return type.getName();
        }
        ClassElement componentType = type;
        StringBuilder name = new StringBuilder(type.getArrayDimensions());
        while (componentType.isArray()) {
            name.append('[');
            componentType = componentType.fromArray();
        }
        if (componentType.isPrimitive()) {
            name.append(primitiveDescriptor(componentType.getName()));
        } else {
            name.append('L').append(componentType.getName()).append(';');
        }
        return name.toString();
    }

    private static char primitiveDescriptor(String primitiveName) {
        return switch (primitiveName) {
            case "boolean" -> 'Z';
            case "byte" -> 'B';
            case "char" -> 'C';
            case "double" -> 'D';
            case "float" -> 'F';
            case "int" -> 'I';
            case "long" -> 'J';
            case "short" -> 'S';
            default -> throw new IllegalArgumentException("Unsupported primitive provider type: " + primitiveName);
        };
    }

    private static boolean isServerMessageBodyProvider(ClassElement element) {
        return element.enumValue(ConstrainedTo.class, RuntimeType.class)
            .map(runtimeType -> runtimeType == RuntimeType.SERVER)
            .orElse(true);
    }

    private static String[] providerMediaTypes(ClassElement element, Class<? extends Annotation> annotationType) {
        AnnotationValue<?> annotation = element.getAnnotation(annotationType);
        if (annotation == null) {
            annotation = element.getAnnotationMetadata().getAnnotation(jakartaMediaAnnotationName(annotationType));
        }
        if (annotation == null) {
            return new String[] { MediaType.ALL };
        }
        String[] values = ProducesMapper.splitMediaTypes(annotation.stringValues());
        return values.length == 0 ? new String[] { MediaType.ALL } : values;
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (!isCurrentClassConstructor(element) || !isServerResourceClass()) {
            return;
        }
        ConstructorElement jaxRsConstructor = findPreferredJaxRsConstructor();
        if (jaxRsConstructor != null && sameConstructorSignature(element, jaxRsConstructor)) {
            element.annotate(Inject.class);
            visitConstructorParameters(element, context);
            if (hasRequestConstructorParameter(element)) {
                markConstructorInjection();
                List<String> matrixParameterNames = matrixParameterNames(element);
                if (!matrixParameterNames.isEmpty()) {
                    markMatrixAwareClassPath(matrixParameterNames.get(0));
                }
            }
        }
    }

    @Override
    public void visitMethod(MethodElement element, VisitorContext context) {
        if (isNonPublicServerResourceCandidate(element)) {
            removeMicronautRouteAnnotations(element);
            return;
        }
        if (isSubResourceLocator(element)) {
            visitSubResourceLocator(element, context);
            return;
        }
        if (element.hasStereotype(HttpMethod.class)) {
            if (isUnrootedSubResourceTarget(element)) {
                if (!currentClassElement.hasStereotype(Scope.class)) {
                    currentClassElement.annotate(Prototype.class);
                }
                element.annotate(Executable.class);
                visitMethodParameters(element, context, false);
                annotateResourceTemplate(element, jaxRsMethodPath(element).orElse(UriMapping.DEFAULT_URI));
                httpMethodRoutePath(element)
                    .map(path -> validationRoutePath(path, element))
                    .ifPresent(path -> annotateHttpRoute(element, path));
                return;
            }
            if (currentClassElement != null
                && (currentClassElement.hasAnnotation(Path.class) || currentClassElement.isPublic())
                && !currentClassElement.hasAnnotation(Controller.class)
                && !currentClassElement.isAbstract()) {
                currentClassElement.annotate(Controller.class);
            }
            removeInheritedParameterAnnotations(element);
            annotateDefaultMediaTypes(element);
            visitMethodParameters(element, context, true);
            if (isServerResourceClass()) {
                annotateRequestFieldInjection(element);
                List<String> matrixParameterNames = matrixParameterNames(element);
                if (!matrixParameterNames.isEmpty() && isDefaultRouteMethod(element)) {
                    markMatrixAwareClassPath(matrixParameterNames.get(0));
                }
                annotateResourceTemplate(element, jaxRsMethodPath(element).orElse(UriMapping.DEFAULT_URI), matrixParameterNames);
                httpMethodRoutePath(element)
                    .map(path -> toServerRoutePath(element, path, matrixParameterNames))
                    .ifPresent(path -> annotateHttpRoute(element, path));
            }
        }
    }

    private void visitSubResourceLocator(MethodElement element, VisitorContext context) {
        if (!isServerResourceClass()) {
            return;
        }
        if (isUnrootedRecursiveSubResourceLocator(element)) {
            element.annotate(Executable.class);
            removeMicronautRouteAnnotations(element);
            return;
        }
        List<String> matrixParameterNames = matrixParameterNames(element);
        if (!matrixParameterNames.isEmpty()) {
            markMatrixAwareClassPath(matrixParameterNames.get(0));
        }
        List<SubResourceTargetMethod> targetMethods = findSubResourceTargetMethods(element, !failOnUnsupported(context));
        if (!targetMethods.isEmpty()) {
            annotateRequestFieldInjection(element);
            visitMethodParameters(element, context, false);
            SubResourceTargetMethod targetMethod = targetMethods.get(0);
            MethodElement method = targetMethod.method();
            List<SubResourceTargetMethod> exposedTargetMethods = targetMethods;
            String locatorPath = httpMethodRoutePath(element).orElse(UriMapping.DEFAULT_URI);
            String targetPath = exposedSubResourceRoutePath(exposedTargetMethods);
            String routePath = prependRoutePath(locatorPath, targetPath);
            String resourceTemplatePath = prependRoutePath(
                jaxRsMethodPath(element).orElse(UriMapping.DEFAULT_URI),
                exposedSubResourceResourceTemplatePath(exposedTargetMethods)
            );
            RecursiveSubResourceLocator recursiveLocator = findRecursiveSubResourceLocator(element);
            if (!matrixParameterNames.isEmpty()) {
                routePath = toMatrixParameterAwareRoute(routePath, matrixParameterNames);
            }
            String finalRoutePath = routePath;
            annotateSubResourceMediaTypes(element, exposedTargetMethods);
            annotateHttpRoute(element, targetMethod.routeAnnotation(), validationRoutePath(locatorPath, element));
            annotateResourceTemplate(element, resourceTemplatePath, matrixParameterNames);
            element.annotate(SUB_RESOURCE_LOCATOR_ANNOTATION, builder -> builder
                .value(method.getName())
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TYPE, new AnnotationClassValue<>(method.getDeclaringType().getName()))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_ROUTE_PATH, finalRoutePath)
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES, targetMethodArgumentTypes(method))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_RECURSIVE, recursiveLocator == null ? "" : recursiveLocator.method().getName())
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_REMAINING, recursiveLocator == null ? "" : RECURSIVE_REMAINING_ROUTE_VARIABLE)
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_METHODS, targetMethodNames(exposedTargetMethods))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_HTTP_METHODS, targetHttpMethods(exposedTargetMethods))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_RESOURCE_TEMPLATES, targetResourceTemplates(currentClassElement, element, exposedTargetMethods))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_ARGUMENT_TYPES, targetArgumentTypes(exposedTargetMethods))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_ARGUMENT_TYPE_COUNTS, targetArgumentTypeCounts(exposedTargetMethods))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_CONSUMES, targetMediaTypes(exposedTargetMethods, Consumes.class))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_CONSUMES_COUNTS, targetMediaTypeCounts(exposedTargetMethods, Consumes.class))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_PRODUCES, targetMediaTypes(exposedTargetMethods, Produces.class))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TARGET_PRODUCES_COUNTS, targetMediaTypeCounts(exposedTargetMethods, Produces.class)));
        } else if (!failOnUnsupported(context) && isDynamicSubResourceLocator(element)) {
            annotateRequestFieldInjection(element);
            visitMethodParameters(element, context, false);
            annotateDefaultMediaTypes(element);
            String locatorPath = httpMethodRoutePath(element).orElse(UriMapping.DEFAULT_URI);
            if (!matrixParameterNames.isEmpty()) {
                locatorPath = toMatrixParameterAwareRoute(locatorPath, matrixParameterNames);
            }
            String finalLocatorPath = locatorPath;
            String routePath = appendDynamicRemainingRoute(locatorPath);
            element.annotate(Executable.class);
            annotateDynamicHttpRoute(element, routePath);
            annotateResourceTemplate(element, routePath, matrixParameterNames);
            element.annotate(SUB_RESOURCE_LOCATOR_ANNOTATION, builder -> builder
                .value("")
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_TYPE, new AnnotationClassValue<>(subResourceType(element).getName()))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_ROUTE_PATH, finalLocatorPath)
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_ARGUMENT_TYPES, targetMethodArgumentTypes(element))
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_DYNAMIC, true)
                .member(JaxRsSubResourceLocatorMetadata.MEMBER_REMAINING, JaxRsSubResourceLocatorMetadata.DYNAMIC_REMAINING_ROUTE_VARIABLE));
        } else if (isTerminalResponseLocator(element)) {
            annotateRequestFieldInjection(element);
            visitMethodParameters(element, context, true);
            annotateDefaultMediaTypes(element);
            String locatorPath = httpMethodRoutePath(element).orElse(UriMapping.DEFAULT_URI);
            if (!matrixParameterNames.isEmpty()) {
                locatorPath = toMatrixParameterAwareRoute(locatorPath, matrixParameterNames);
            }
            annotateHttpRoute(element, Get.class, validationRoutePath(locatorPath, element));
            annotateResourceTemplate(element, jaxRsMethodPath(element).orElse(UriMapping.DEFAULT_URI), matrixParameterNames);
        }
    }

    private void annotateDefaultMediaTypes(MethodElement element) {
        if ((currentClassElement == null || !hasDeclaredMediaAnnotation(currentClassElement, Produces.class)) &&
            !hasDeclaredMediaAnnotation(element, Produces.class)) {
            element.annotate(Produces.class, b -> b.values(MediaType.ALL));
        }
        if ((currentClassElement == null || !hasDeclaredMediaAnnotation(currentClassElement, Consumes.class)) &&
            !hasDeclaredMediaAnnotation(element, Consumes.class)) {
            element.annotate(Consumes.class, b -> b.values(MediaType.ALL));
        }
    }

    private void annotateSubResourceMediaTypes(MethodElement locator, List<SubResourceTargetMethod> targetMethods) {
        if (targetMethods.size() == 1) {
            annotateSubResourceMediaTypes(locator, targetMethods.get(0).method());
            return;
        }
        if (!hasSourceMediaAnnotation(locator, Produces.class)) {
            locator.annotate(Produces.class, b -> b.values(MediaType.ALL));
        }
        if (!hasSourceMediaAnnotation(locator, Consumes.class)) {
            locator.annotate(Consumes.class, b -> b.values(MediaType.ALL));
        }
    }

    private void annotateSubResourceMediaTypes(MethodElement locator, MethodElement target) {
        if (!hasSourceMediaAnnotation(locator, Produces.class)) {
            String[] produces = sourceMediaTypes(target, Produces.class);
            if (produces.length == 0) {
                if (currentClassElement == null || !hasSourceMediaAnnotation(currentClassElement, Produces.class)) {
                    locator.annotate(Produces.class, b -> b.values(MediaType.ALL));
                }
            } else {
                locator.annotate(Produces.class, b -> b.values(produces));
            }
        }
        if (!hasSourceMediaAnnotation(locator, Consumes.class)) {
            String[] consumes = sourceMediaTypes(target, Consumes.class);
            if (consumes.length == 0) {
                if (currentClassElement == null || !hasSourceMediaAnnotation(currentClassElement, Consumes.class)) {
                    locator.annotate(Consumes.class, b -> b.values(MediaType.ALL));
                }
            } else {
                locator.annotate(Consumes.class, b -> b.values(consumes));
            }
        }
    }

    private void annotateResourceTemplate(MethodElement method, String path) {
        annotateResourceTemplate(method, path, List.of());
    }

    private void annotateResourceTemplate(MethodElement method, String path, List<String> matrixRouteVariableNames) {
        if (currentClassElement == null) {
            return;
        }
        String classPath = jaxRsPath(currentClassElement).orElse(UriMapping.DEFAULT_URI);
        String rootTemplate = normalizeRoutePath(classPath);
        String resourceTemplate = prependRoutePath(classPath, path);
        JaxRsRouteScore routeScore = JaxRsRouteScore.of(resourceTemplate);
        JaxRsRouteScore rootRouteScore = JaxRsRouteScore.of(rootTemplate);
        method.annotate(RESOURCE_TEMPLATE_ANNOTATION, builder -> {
            builder
                .value(resourceTemplate)
                .member(JaxRsResourceTemplateMetadata.MEMBER_ROOT_PATH_SEGMENT_COUNT, pathSegmentCount(classPath))
                .member(JaxRsResourceTemplateMetadata.MEMBER_ROOT_TEMPLATE, rootTemplate)
                .member(JaxRsResourceTemplateMetadata.MEMBER_ROOT_CLASS_NAME, currentClassElement.getName())
                .member(JaxRsResourceTemplateMetadata.MEMBER_LITERAL_CHARACTERS, routeScore.literalCharacters())
                .member(JaxRsResourceTemplateMetadata.MEMBER_CAPTURING_GROUPS, routeScore.capturingGroups())
                .member(JaxRsResourceTemplateMetadata.MEMBER_NON_DEFAULT_CAPTURING_GROUPS, routeScore.nonDefaultCapturingGroups())
                .member(JaxRsResourceTemplateMetadata.MEMBER_ROOT_LITERAL_CHARACTERS, rootRouteScore.literalCharacters())
                .member(JaxRsResourceTemplateMetadata.MEMBER_ROOT_CAPTURING_GROUPS, rootRouteScore.capturingGroups())
                .member(JaxRsResourceTemplateMetadata.MEMBER_ROOT_NON_DEFAULT_CAPTURING_GROUPS, rootRouteScore.nonDefaultCapturingGroups());
            httpMethodName(method)
                .filter(value -> !value.isEmpty())
                .ifPresent(value -> builder.member(JaxRsResourceTemplateMetadata.MEMBER_HTTP_METHOD, value));
            if (!matrixRouteVariableNames.isEmpty()) {
                builder.member(JaxRsResourceTemplateMetadata.MEMBER_MATRIX_ROUTE_VARIABLE_NAMES, matrixRouteVariableNames.toArray(String[]::new));
            }
        });
    }

    private void visitMethodParameters(MethodElement element, VisitorContext context, boolean bindUnannotatedBody) {
        final ParameterElement[] parameters = element.getParameters();
        boolean encoded = isEncoded(element);
        boolean hasPathParam = false;
        for (ParameterElement parameter : parameters) {
            final List<Class<? extends Annotation>> unsupported = getUnsupportedParameterAnnotations(context);
            for (Class<? extends Annotation> annType : unsupported) {
                if (parameter.hasAnnotation(annType)) {
                    context.fail("Unsupported JAX-RS annotation used on method: " + annType.getName(), parameter);
                }
            }
            if (encoded && (parameter.hasAnnotation(MatrixParam.class) || parameter.hasAnnotation(PathParam.class)) && !parameter.hasAnnotation(Encoded.class)) {
                parameter.annotate(Encoded.class);
            }
            hasPathParam |= parameter.hasAnnotation(PathParam.class);
            visitParamOrField(parameter, context);
            String parameterTypeName = parameter.getType().getName();
            if (bindUnannotatedBody
                && JAX_RS_BINDING_ANNOTATIONS.stream().noneMatch(parameter::hasAnnotation)
                && JAX_RS_BINDING_TYPES.stream().noneMatch(cl -> cl.equals(parameterTypeName))) {
                // unannotated, implicit Jakarta REST entity
                if (isClientClass()) {
                    parameter.annotate(Body.class);
                } else {
                    parameter.annotate(ENTITY_ANNOTATION);
                    parameter.annotate(Nullable.class); // JAX-RS controller bodies are nullable by default
                }
            }
        }
        if (isServerResourceClass() && hasPathParam) {
            element.annotate(PATH_PARAM_BINDING_ANNOTATION);
        }
    }

    private void removeInheritedParameterAnnotations(MethodElement element) {
        if (!hasDeclaredJaxRsMethodAnnotation(element) && !hasDeclaredJaxRsParameterAnnotation(element)) {
            return;
        }
        ParameterElement[] parameters = element.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            ParameterElement parameter = parameters[i];
            for (Class<? extends Annotation> annotation : JAX_RS_BINDING_ANNOTATIONS) {
                AnnotationMetadata annotationMetadata = parameter.getAnnotationMetadata();
                if (annotationMetadata.hasAnnotation(annotation) &&
                    hasInheritedParameterAnnotation(element, i, annotation) &&
                    !hasDeclaredParameterAnnotation(parameter, annotation)) {
                    parameter.removeAnnotation(annotation);
                }
            }
        }
    }

    private static boolean hasDeclaredJaxRsMethodAnnotation(MethodElement element) {
        AnnotationMetadata annotationMetadata = element.getMethodAnnotationMetadata().getAnnotationMetadata();
        return annotationMetadata.hasDeclaredStereotype(HttpMethod.class)
            || annotationMetadata.hasDeclaredAnnotation(Path.class)
            || annotationMetadata.hasDeclaredAnnotation(Consumes.class)
            || annotationMetadata.hasDeclaredAnnotation(Produces.class)
            || annotationMetadata.hasDeclaredAnnotation(Encoded.class);
    }

    private static boolean hasInheritedParameterAnnotation(MethodElement element, int parameterIndex, Class<? extends Annotation> annotation) {
        for (MethodElement overriddenMethod : element.getOverriddenMethods()) {
            ParameterElement[] inheritedParameters = overriddenMethod.getParameters();
            if (inheritedParameters.length > parameterIndex && inheritedParameters[parameterIndex].hasAnnotation(annotation)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasDeclaredJaxRsParameterAnnotation(MethodElement element) {
        return Arrays.stream(element.getParameters()).anyMatch(parameter ->
            JAX_RS_BINDING_ANNOTATIONS.stream().anyMatch(annotation ->
                hasDeclaredParameterAnnotation(parameter, annotation)));
    }

    private static boolean hasDeclaredParameterAnnotation(ParameterElement parameter, Class<? extends Annotation> annotation) {
        return parameter.getAnnotationMetadata().hasDeclaredAnnotation(annotation);
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        if (failOnUnsupported(context) && (element.hasAnnotation(FormParam.class) || element.hasAnnotation(BeanParam.class))) {
            context.fail("Request scoped bean parameters are currently not supported", element);
            return;
        }
        visitParamOrField(element, context);
        if (element.hasAnnotation(MatrixParam.class)) {
            markRequestFieldInjection();
            markMatrixAwareClassPath(getMatrixParameterName(element));
        } else if (element.hasAnnotation(QueryParam.class)) {
            markRequestFieldInjection();
        } else if (element.hasAnnotation(HeaderParam.class)) {
            markRequestFieldInjection();
        } else if (element.hasAnnotation(CookieParam.class)) {
            element.removeAnnotation(CookieValue.class);
            markRequestFieldInjection();
        } else if (element.hasAnnotation(PathParam.class)) {
            markRequestFieldInjection();
        } else if (element.hasAnnotation(FormParam.class)) {
            markRequestFieldInjection();
        } else if (element.hasAnnotation(BeanParam.class)) {
            markRequestFieldInjection();
            beanMatrixParameterNames(element.getType(), new HashSet<>()).stream()
                .findFirst()
                .ifPresent(this::markMatrixAwareClassPath);
        }
    }

    private void markRequestFieldInjection() {
        if (currentClassElement != null) {
            if (!currentClassElement.hasStereotype(Scope.class)) {
                currentClassElement.annotate(Prototype.class);
            }
        }
    }

    private void annotateRequestFieldInjection(MethodElement element) {
        if (currentClassElement != null && hasRequestParamField(currentClassElement)) {
            element.annotate(REQUEST_FIELD_INJECTION_ANNOTATION);
            annotateNonPublicMethodsForAop(currentClassElement);
            markRequestFieldInjection();
        }
    }

    private static void annotateNonPublicMethodsForAop(ClassElement element) {
        element.getMethods().stream()
            .filter(method -> !method.isPublic())
            .forEach(method -> method.annotate(ReflectiveAccess.class));
    }

    private static boolean hasRequestParamField(ClassElement element) {
        return element.getFields().stream().anyMatch(JaxRsTypeElementVisitor::isRequestParamField);
    }

    private static boolean isRequestParamField(FieldElement field) {
        return field.hasAnnotation(MatrixParam.class)
            || field.hasAnnotation(QueryParam.class)
            || field.hasAnnotation(HeaderParam.class)
            || field.hasAnnotation(CookieParam.class)
            || field.hasAnnotation(PathParam.class)
            || field.hasAnnotation(FormParam.class)
            || field.hasAnnotation(BeanParam.class);
    }

    private void markMatrixAwareClassPath(String matrixParameterName) {
        if (currentClassElement == null) {
            return;
        }
        currentClassElement.stringValue(Controller.class)
            .filter(path -> !path.contains(MATRIX_PARAMETER_ROUTE_PATTERN))
            .ifPresent(path -> {
                currentClassElement.removeAnnotation(Controller.class);
                currentClassElement.annotate(Controller.class, builder -> builder.value(toMatrixParameterAwareRoute(path, List.of(matrixParameterName))));
            });
        currentClassElement.stringValue(UriMapping.class)
            .filter(path -> !path.contains(MATRIX_PARAMETER_ROUTE_PATTERN))
            .ifPresent(path -> {
                currentClassElement.removeAnnotation(UriMapping.class);
                currentClassElement.annotate(UriMapping.class, builder -> builder.value(toMatrixParameterAwareRoute(path, List.of(matrixParameterName))));
            });
    }

    private void visitParamOrField(TypedElement parameter, VisitorContext context) {
        if (parameter.hasAnnotation(HeaderParam.class)) {
            if (isClientClass()) {
                mapParam(parameter, HeaderParam.class, Header.class);
            } else {
                annotateHeaderParam(parameter);
            }
        }
        if (parameter.hasAnnotation(FormParam.class)) {
            if (isClientClass()) {
                mapParam(parameter, FormParam.class, Body.class);
            } else {
                parameter.removeAnnotation(Body.class);
                parameter.removeAnnotation(QueryValue.class);
                annotateFormParam(parameter);
            }
        }
        if (parameter.hasAnnotation(CookieParam.class)) {
            if (isClientClass()) {
                mapParam(parameter, CookieParam.class, CookieValue.class);
            } else {
                parameter.removeAnnotation(CookieValue.class);
                annotateCookieParam(parameter);
            }
        }
        if (parameter.hasAnnotation(PathParam.class)) {
            if (isClientClass()) {
                mapParam(parameter, PathParam.class, PathVariable.class);
                parameter.removeAnnotation(PathParam.class);
            } else {
                annotatePathParam(parameter);
            }
        }
        if (parameter.hasAnnotation(QueryParam.class)) {
            annotateQueryParam(parameter);
        }
        if (parameter.hasAnnotation(MatrixParam.class)) {
            annotateMatrixParam(parameter);
        }
        if (parameter.hasAnnotation(BeanParam.class)) {
            annotateBeanParam(parameter, context);
        }
    }

    private static void annotateHeaderParam(TypedElement parameter) {
        AnnotationValueBuilder<HeaderParam> builder = AnnotationValue.builder(HeaderParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(bindable(parameter)).build()
        );
    }

    private static void annotateQueryParam(TypedElement parameter) {
        AnnotationValueBuilder<QueryParam> builder = AnnotationValue.builder(QueryParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
            .stereotype(bindable(parameter)).build()
        );
    }

    private static void annotateMatrixParam(TypedElement parameter) {
        AnnotationValueBuilder<MatrixParam> builder = AnnotationValue.builder(MatrixParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(bindable(parameter)).build()
        );
    }

    private static void annotateFormParam(TypedElement parameter) {
        AnnotationValueBuilder<FormParam> builder = AnnotationValue.builder(FormParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(bindable(parameter)).build()
        );
    }

    private static void annotatePathParam(TypedElement parameter) {
        AnnotationValueBuilder<PathParam> builder = AnnotationValue.builder(PathParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(bindable(parameter)).build()
        );
    }

    private static void annotateCookieParam(TypedElement parameter) {
        AnnotationValueBuilder<CookieParam> builder = AnnotationValue.builder(CookieParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(bindable(parameter)).build()
        );
    }

    private void annotateBeanParam(TypedElement parameter, VisitorContext context) {
        parameter.annotate(RequestBean.class);
        annotateBeanParamType(parameter.getType(), new HashSet<>(), context);
    }

    private void annotateBeanParamType(ClassElement type, Set<String> visitedTypes, VisitorContext context) {
        if (!visitedTypes.add(type.getName())) {
            return;
        }
        type.annotate(Introspected.class, builder -> {
            builder.member("accessKind", new Introspected.AccessKind[] {
                Introspected.AccessKind.FIELD,
                Introspected.AccessKind.METHOD
            });
            builder.member("visibility", Introspected.Visibility.ANY);
        });
        generateBeanParamIntrospection(type, context);
        type.getFields().stream()
            .filter(field -> field.hasAnnotation(BeanParam.class))
            .forEach(field -> {
                field.annotate(RequestBean.class);
                annotateBeanParamType(field.getType(), visitedTypes, context);
            });
    }

    private void generateBeanParamIntrospection(ClassElement type, VisitorContext context) {
        String typeName = type.getName();
        if (!generatedBeanParamIntrospections.add(typeName)) {
            return;
        }
        SourceGenerator sourceGenerator = SourceGenerators.findByLanguage(context.getLanguage()).orElse(null);
        if (sourceGenerator == null) {
            return;
        }
        String packageName = type.getPackageName();
        String helperName = BEAN_PARAM_INTROSPECTION_PREFIX + Integer.toUnsignedString(typeName.hashCode(), 36);
        String helperClassName = packageName.isEmpty() ? helperName : packageName + "." + helperName;
        sourceGenerator.write(
            ClassDef.builder(helperClassName)
                .addAnnotation(beanParamIntrospectionAnnotation(typeName))
                .build(),
            context,
            type
        );
    }

    private static AnnotationDef beanParamIntrospectionAnnotation(String typeName) {
        ClassTypeDef accessKindType = ClassTypeDef.of(Introspected.AccessKind.class);
        ClassTypeDef visibilityType = ClassTypeDef.of(Introspected.Visibility.class);
        return AnnotationDef.builder(Introspected.class)
            .addMember("classNames", typeName)
            .addMember("accessKind", List.of(
                accessKindType.getStaticField("FIELD", accessKindType),
                accessKindType.getStaticField("METHOD", accessKindType)
            ))
            .addMember("visibility", visibilityType.getStaticField("ANY", visibilityType))
            .build();
    }

    private static AnnotationValue<Bindable> bindable(TypedElement parameter) {
        AnnotationValueBuilder<Bindable> builder = AnnotationValue.builder(Bindable.class);
        primitiveDefaultValue(parameter).ifPresent(defaultValue -> builder.member(JaxRsBindableMetadata.MEMBER_DEFAULT_VALUE, defaultValue));
        return builder.build();
    }

    private static void annotateDefaultAndNullable(TypedElement parameter, AnnotationValueBuilder<?> builder) {
        if (parameter.isPrimitive()) {
            primitiveDefaultValue(parameter).ifPresent(defaultValue -> annotateDefaultValue(parameter, builder, defaultValue));
        } else if (!parameter.isNonNull()) {
            parameter.annotate(Nullable.class);
        }
    }

    private static java.util.Optional<String> primitiveDefaultValue(TypedElement parameter) {
        if (!parameter.isPrimitive()) {
            return java.util.Optional.empty();
        }
        if (parameter.getType().isAssignable(boolean.class)) {
            return java.util.Optional.of("false");
        }
        return java.util.Optional.of("0");
    }

    private static void annotateDefaultValue(TypedElement parameter, AnnotationValueBuilder<?> builder, String defaultValue) {
        builder.member(JaxRsBindableMetadata.MEMBER_DEFAULT_VALUE, defaultValue);
        if (!parameter.hasAnnotation(Bindable.class)) {
            parameter.annotate(Bindable.class, bindable -> bindable.member(JaxRsBindableMetadata.MEMBER_DEFAULT_VALUE, defaultValue));
        }
    }

    private static <P extends Annotation> void mapParam(TypedElement parameter, Class<P> jakartaAnnotation, Class<? extends Annotation> mnAnnotation) {
        AnnotationValue<P> ann = parameter.getAnnotation(jakartaAnnotation);
        if (ann != null) {
            parameter.annotate(mnAnnotation, builder -> {
                ann.stringValue().ifPresent(builder::value);
                annotateDefaultAndNullable(parameter, builder);
            });
        }
    }

    private boolean isServerResourceClass() {
        return currentClassElement != null && !isClientClass();
    }

    private boolean isClientClass() {
        return currentClassElement != null && (currentClassElement.hasStereotype(CLIENT_ANNOTATION) || currentClassElement.hasAnnotation(CLIENT_ANNOTATION));
    }

    private boolean isCurrentClassConstructor(ConstructorElement element) {
        return currentClassElement != null && element.getDeclaringType().getName().equals(currentClassElement.getName());
    }

    private @Nullable ConstructorElement findPreferredJaxRsConstructor() {
        if (currentClassElement == null) {
            return null;
        }
        ConstructorElement selected = null;
        for (ConstructorElement constructor : currentClassElement.getAccessibleConstructors()) {
            if (constructor.isPublic() && isJaxRsConstructor(constructor) &&
                (selected == null || constructor.getParameters().length > selected.getParameters().length)) {
                selected = constructor;
            }
        }
        return selected;
    }

    private static boolean isJaxRsConstructor(ConstructorElement constructor) {
        ParameterElement[] parameters = constructor.getParameters();
        return parameters.length > 0 && Arrays.stream(parameters).allMatch(JaxRsTypeElementVisitor::isSupportedConstructorParameter);
    }

    private void visitConstructorParameters(ConstructorElement element, VisitorContext context) {
        for (ParameterElement parameter : element.getParameters()) {
            visitParamOrField(parameter, context);
            if (isRequestConstructorParameter(parameter)) {
                parameter.annotate(Parameter.class);
            }
        }
    }

    private void markConstructorInjection() {
        if (currentClassElement != null) {
            currentClassElement.annotate(CONSTRUCTOR_INJECTION_ANNOTATION);
            if (!currentClassElement.hasStereotype(Scope.class)) {
                currentClassElement.annotate(Prototype.class);
            }
        }
    }

    private static boolean hasRequestConstructorParameter(ConstructorElement element) {
        return Arrays.stream(element.getParameters()).anyMatch(JaxRsTypeElementVisitor::isRequestConstructorParameter);
    }

    private static boolean isSupportedConstructorParameter(ParameterElement parameter) {
        return parameter.hasAnnotation(Context.class) || isRequestConstructorParameter(parameter);
    }

    private static boolean isRequestConstructorParameter(ParameterElement parameter) {
        return parameter.hasAnnotation(MatrixParam.class)
            || parameter.hasAnnotation(QueryParam.class)
            || parameter.hasAnnotation(HeaderParam.class)
            || parameter.hasAnnotation(CookieParam.class)
            || parameter.hasAnnotation(PathParam.class);
    }

    private static boolean sameConstructorSignature(ConstructorElement left, ConstructorElement right) {
        if (!left.getDeclaringType().getName().equals(right.getDeclaringType().getName())) {
            return false;
        }
        ParameterElement[] leftParameters = left.getParameters();
        ParameterElement[] rightParameters = right.getParameters();
        if (leftParameters.length != rightParameters.length) {
            return false;
        }
        for (int i = 0; i < leftParameters.length; i++) {
            if (!leftParameters[i].getType().getName().equals(rightParameters[i].getType().getName())) {
                return false;
            }
        }
        return true;
    }

    private boolean isEncoded(MethodElement element) {
        return element.hasAnnotation(Encoded.class) || currentClassElement != null && currentClassElement.hasAnnotation(Encoded.class);
    }

    static List<String> matrixParameterNames(MethodElement element) {
        return Arrays.stream(element.getParameters())
            .flatMap(parameter -> matrixParameterNames(parameter).stream())
            .toList();
    }

    private static List<String> rootMatrixParameterNames(ClassElement element) {
        return element.getMethods().stream()
            .filter(JaxRsTypeElementVisitor::isPublicResourceMethod)
            .filter(JaxRsTypeElementVisitor::hasHttpMethodAnnotation)
            .filter(JaxRsTypeElementVisitor::isDefaultRouteMethod)
            .flatMap(method -> matrixParameterNames(method).stream())
            .toList();
    }

    private static boolean hasHttpMethodAnnotation(MethodElement method) {
        return method.hasStereotype(HttpMethod.class) || JAX_RS_HTTP_METHOD_ANNOTATIONS.stream().anyMatch(method::hasAnnotation);
    }

    private static boolean hasAnnotation(TypedElement element, Class<? extends Annotation> annotation) {
        return element.hasAnnotation(annotation);
    }

    private static boolean isDefaultRouteMethod(MethodElement method) {
        return "/".equals(normalizeRoutePath(httpMethodRoutePath(method).orElse(UriMapping.DEFAULT_URI)));
    }

    private static Optional<String> httpMethodRoutePath(MethodElement method) {
        return method.stringValue(HttpMethodMapping.class)
            .filter(path -> !"null".equals(path));
    }

    private static Optional<String> jaxRsPath(io.micronaut.inject.ast.Element element) {
        if (!element.hasAnnotation(Path.class)) {
            return Optional.empty();
        }
        return Optional.of(rootIfEmpty(element.stringValue(Path.class).orElse(UriMapping.DEFAULT_URI)));
    }

    private static Optional<String> jaxRsMethodPath(MethodElement method) {
        if (method.getAnnotationMetadata().hasDeclaredAnnotation(Path.class)) {
            return jaxRsPath(method);
        }
        for (MethodElement overriddenMethod : method.getOverriddenMethods()) {
            if (overriddenMethod.getAnnotationMetadata().hasDeclaredAnnotation(Path.class)) {
                return jaxRsPath(overriddenMethod);
            }
        }
        return Optional.empty();
    }

    private static String rootIfEmpty(String path) {
        return path.isEmpty() || "null".equals(path) ? UriMapping.DEFAULT_URI : path;
    }

    private static List<String> matrixParameterNames(ParameterElement parameter) {
        if (hasAnnotation(parameter, MatrixParam.class) || isPathSegmentPathParam(parameter)) {
            return List.of(parameter.getName());
        }
        if (parameter.hasAnnotation(BeanParam.class)) {
            return beanMatrixParameterNames(parameter.getType(), new HashSet<>());
        }
        return List.of();
    }

    static List<String> matrixFieldNames(ClassElement element) {
        return element.getFields().stream()
            .flatMap(field -> {
                if (field.hasAnnotation(MatrixParam.class)) {
                    return List.of(getMatrixParameterName(field)).stream();
                }
                if (field.hasAnnotation(BeanParam.class)) {
                    return beanMatrixParameterNames(field.getType(), new HashSet<>()).stream();
                }
                return List.<String>of().stream();
            })
            .toList();
    }

    private static List<String> beanMatrixParameterNames(ClassElement beanType, Set<String> visitedTypes) {
        if (!visitedTypes.add(beanType.getName())) {
            return List.of();
        }
        return beanType.getFields().stream()
            .flatMap(field -> {
                if (field.hasAnnotation(MatrixParam.class)) {
                    return List.of(getMatrixParameterName(field)).stream();
                }
                if (field.hasAnnotation(BeanParam.class)) {
                    return beanMatrixParameterNames(field.getType(), visitedTypes).stream();
                }
                return List.<String>of().stream();
            })
            .toList();
    }

    private static boolean isPathSegmentPathParam(ParameterElement parameter) {
        return parameter.hasAnnotation(PathParam.class) && parameter.getType().isAssignable(PathSegment.class);
    }

    private boolean isSubResourceLocator(MethodElement method) {
        return isServerResourceClass() && jaxRsMethodPath(method).isPresent() && !method.hasStereotype(HttpMethod.class);
    }

    private static boolean isTerminalResponseLocator(MethodElement method) {
        return method.getReturnType().isAssignable(Response.class);
    }

    private static boolean isDynamicSubResourceLocator(MethodElement method) {
        return subResourceType(method).getName().equals(Object.class.getName());
    }

    private static boolean isSubResourceTargetMethod(MethodElement method) {
        return isPublicResourceMethod(method) &&
            method.hasStereotype(HttpMethod.class) &&
            Arrays.stream(method.getParameters()).allMatch(JaxRsTypeElementVisitor::isSupportedSubResourceTargetParameter);
    }

    private static boolean isSupportedSubResourceTargetParameter(ParameterElement parameter) {
        return isContextUriInfoParameter(parameter) || isRequestBoundParameter(parameter);
    }

    private static boolean isContextUriInfoParameter(ParameterElement parameter) {
        return parameter.hasAnnotation(Context.class) && parameter.getType().isAssignable(UriInfo.class);
    }

    private static boolean isRequestBoundParameter(ParameterElement parameter) {
        return parameter.hasAnnotation(MatrixParam.class)
            || parameter.hasAnnotation(QueryParam.class)
            || parameter.hasAnnotation(HeaderParam.class)
            || parameter.hasAnnotation(CookieParam.class)
            || parameter.hasAnnotation(PathParam.class)
            || parameter.hasAnnotation(FormParam.class)
            || parameter.hasAnnotation(BeanParam.class);
    }

    private boolean isUnrootedRecursiveSubResourceLocator(MethodElement method) {
        return currentClassElement != null &&
            !currentClassElement.hasAnnotation(Path.class) &&
            method.getParameters().length == 0 &&
            method.getReturnType().getName().equals(method.getDeclaringType().getName()) &&
            singlePathVariableName(jaxRsMethodPath(method).orElse("")) != null;
    }

    private static List<SubResourceTargetMethod> findSubResourceTargetMethods(MethodElement locator, boolean allowLocatorTemplateVariables) {
        ClassElement subResourceType = subResourceType(locator);
        String returnTypeName = subResourceType.getName();
        String locatorFirstSegment = firstPathSegment(jaxRsMethodPath(locator).orElse(UriMapping.DEFAULT_URI));
        List<SubResourceTargetMethod> declaredMatchingTargetMethods = new ArrayList<>();
        List<SubResourceTargetMethod> inheritedMatchingTargetMethods = new ArrayList<>();
        List<SubResourceTargetMethod> declaredTargetMethods = new ArrayList<>();
        List<SubResourceTargetMethod> inheritedTargetMethods = new ArrayList<>();
        for (MethodElement method : subResourceType.getMethods()) {
            if (isSubResourceTargetMethod(method) && isCompatibleSubResourceRoute(locator, method, allowLocatorTemplateVariables)) {
                List<AnnotationValue<Annotation>> routeAnnotations = method.getAnnotationValuesByStereotype(HttpMethodMapping.class.getName());
                if (!routeAnnotations.isEmpty()) {
                    AnnotationValue<Annotation> routeAnnotation = routeAnnotations.get(0);
                    SubResourceTargetMethod targetMethod = new SubResourceTargetMethod(
                        method,
                        routeAnnotation,
                        httpMethodName(routeAnnotation)
                    );
                    if (method.getDeclaringType().getName().equals(returnTypeName)) {
                        if (locatorFirstSegment.equals(firstPathSegment(declaredMethodPath(method)))) {
                            declaredMatchingTargetMethods.add(targetMethod);
                        } else {
                            declaredTargetMethods.add(targetMethod);
                        }
                    } else if (locatorFirstSegment.equals(firstPathSegment(declaredMethodPath(method)))) {
                        inheritedMatchingTargetMethods.add(targetMethod);
                    } else {
                        inheritedTargetMethods.add(targetMethod);
                    }
                }
            }
        }
        if (!declaredMatchingTargetMethods.isEmpty()) {
            return declaredMatchingTargetMethods;
        }
        if (!inheritedMatchingTargetMethods.isEmpty()) {
            return inheritedMatchingTargetMethods;
        }
        if (!declaredTargetMethods.isEmpty()) {
            return declaredTargetMethods;
        }
        if (!inheritedTargetMethods.isEmpty()) {
            return inheritedTargetMethods;
        }
        return List.of();
    }

    private static ClassElement subResourceType(MethodElement locator) {
        ClassElement genericReturnType = locator.getGenericReturnType();
        if (genericReturnType.isAssignable(Class.class)) {
            return genericReturnType.getFirstTypeArgument().orElse(locator.getReturnType());
        }
        return locator.getReturnType();
    }

    private static boolean isCompatibleSubResourceRoute(MethodElement locator, MethodElement target, boolean allowLocatorTemplateVariables) {
        List<String> targetVariables = routeTemplateVariableNames(declaredMethodPath(target));
        if (targetVariables.isEmpty()) {
            return true;
        }
        Set<String> targetPathParameters = Arrays.stream(target.getParameters())
            .filter(parameter -> parameter.hasAnnotation(PathParam.class))
            .map(JaxRsTypeElementVisitor::getPathParameterName)
            .collect(Collectors.toSet());
        if (targetPathParameters.stream().noneMatch(targetVariables::contains)) {
            return true;
        }
        if (allowLocatorTemplateVariables) {
            return true;
        }
        Set<String> locatorVariables = Arrays.stream(locator.getParameters())
            .filter(parameter -> parameter.hasAnnotation(PathParam.class))
            .map(JaxRsTypeElementVisitor::getPathParameterName)
            .collect(Collectors.toSet());
        return targetPathParameters.stream()
            .filter(targetVariables::contains)
            .allMatch(locatorVariables::contains);
    }

    private static @Nullable RecursiveSubResourceLocator findRecursiveSubResourceLocator(MethodElement locator) {
        String returnTypeName = locator.getReturnType().getName();
        for (MethodElement method : locator.getReturnType().getMethods()) {
            if (jaxRsMethodPath(method).isPresent() &&
                !method.hasStereotype(HttpMethod.class) &&
                method.getParameters().length == 0 &&
                method.getReturnType().getName().equals(returnTypeName)) {
                String path = jaxRsMethodPath(method).orElse("");
                if (singlePathVariableName(path) != null) {
                    return new RecursiveSubResourceLocator(method);
                }
            }
        }
        return null;
    }

    private static @Nullable String singlePathVariableName(String path) {
        String normalizedPath = normalizeRoutePath(path);
        if (normalizedPath.indexOf('/', 1) > -1) {
            return null;
        }
        return routeTemplateVariableName(normalizedPath.substring(1));
    }

    private static String firstPathSegment(String path) {
        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return "";
        }
        int slash = normalizedPath.indexOf('/', 1);
        return slash > -1 ? normalizedPath.substring(1, slash) : normalizedPath.substring(1);
    }

    private static List<String> routeTemplateVariableNames(String path) {
        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return List.of();
        }
        List<String> variables = new ArrayList<>();
        int segmentStart = 1;
        int braceDepth = 0;
        for (int i = 1; i <= normalizedPath.length(); i++) {
            boolean end = i == normalizedPath.length();
            char c = end ? '\0' : normalizedPath.charAt(i);
            if (!end) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
            if (end || (c == '/' && braceDepth == 0)) {
                String variableName = routeTemplateVariableName(normalizedPath.substring(segmentStart, i));
                if (variableName != null) {
                    variables.add(variableName);
                }
                segmentStart = i + 1;
            }
        }
        return variables;
    }

    private static @Nullable String routeTemplateVariableName(String templateSegment) {
        if (!templateSegment.startsWith("{")) {
            return null;
        }
        int end = templateSegment.indexOf('}');
        if (end < 0 || end != templateSegment.length() - 1) {
            return null;
        }
        String variableName = templateSegment.substring(1, end);
        int colon = variableName.indexOf(':');
        if (colon > -1) {
            variableName = variableName.substring(0, colon);
        }
        return variableName.isEmpty() ? null : variableName;
    }

    private static String[] targetMethodArgumentTypes(MethodElement method) {
        return Arrays.stream(method.getParameters())
            .map(parameter -> parameter.getType().getName())
            .toArray(String[]::new);
    }

    private static String exposedSubResourceRoutePath(List<SubResourceTargetMethod> targetMethods) {
        return generalizedRoutePath(targetMethods.stream()
            .map(targetMethod -> declaredMethodPath(targetMethod.method()))
            .toList());
    }

    private static String exposedSubResourceResourceTemplatePath(List<SubResourceTargetMethod> targetMethods) {
        return generalizedRoutePath(targetMethods.stream()
            .map(targetMethod -> declaredMethodPath(targetMethod.method()))
            .toList());
    }

    private static String[] targetMethodNames(List<SubResourceTargetMethod> targetMethods) {
        return targetMethods.stream()
            .map(targetMethod -> targetMethod.method().getName())
            .toArray(String[]::new);
    }

    private static String[] targetHttpMethods(List<SubResourceTargetMethod> targetMethods) {
        return targetMethods.stream()
            .map(SubResourceTargetMethod::httpMethod)
            .toArray(String[]::new);
    }

    private static Optional<AnnotationValue<Annotation>> routeAnnotation(MethodElement method) {
        return method.getAnnotationValuesByStereotype(HttpMethodMapping.class.getName())
            .stream()
            .findFirst();
    }

    private static Optional<String> httpMethodName(MethodElement method) {
        return routeAnnotation(method).map(JaxRsTypeElementVisitor::httpMethodName);
    }

    private static String httpMethodName(AnnotationValue<Annotation> routeAnnotation) {
        String annotationName = routeAnnotation.getAnnotationName();
        if (annotationName.equals(CustomHttpMethod.class.getName())) {
            return routeAnnotation.stringValue("method").orElse("CUSTOM");
        }
        String httpMethodName = HTTP_METHOD_NAMES.get(annotationName);
        if (httpMethodName != null) {
            return httpMethodName;
        }
        int packageEnd = annotationName.lastIndexOf('.');
        return annotationName.substring(packageEnd + 1).toUpperCase();
    }

    private static String[] targetResourceTemplates(@Nullable ClassElement resourceClass,
                                                    MethodElement locator,
                                                    List<SubResourceTargetMethod> targetMethods) {
        String classPath = resourceClass == null ? UriMapping.DEFAULT_URI : jaxRsPath(resourceClass).orElse(UriMapping.DEFAULT_URI);
        String locatorPath = jaxRsMethodPath(locator).orElse(UriMapping.DEFAULT_URI);
        return targetMethods.stream()
            .map(targetMethod -> prependRoutePath(
                prependRoutePath(classPath, locatorPath),
                declaredMethodPath(targetMethod.method())
            ))
            .toArray(String[]::new);
    }

    private static String declaredMethodPath(MethodElement method) {
        return jaxRsMethodPath(method).orElse(UriMapping.DEFAULT_URI);
    }

    private static String[] targetArgumentTypes(List<SubResourceTargetMethod> targetMethods) {
        return targetMethods.stream()
            .flatMap(targetMethod -> Arrays.stream(targetMethodArgumentTypes(targetMethod.method())))
            .toArray(String[]::new);
    }

    private static int[] targetArgumentTypeCounts(List<SubResourceTargetMethod> targetMethods) {
        return targetMethods.stream()
            .mapToInt(targetMethod -> targetMethod.method().getParameters().length)
            .toArray();
    }

    private static String[] targetMediaTypes(List<SubResourceTargetMethod> targetMethods,
                                             Class<? extends Annotation> annotationType) {
        return targetMethods.stream()
            .flatMap(targetMethod -> Arrays.stream(sourceMediaTypes(targetMethod.method(), annotationType)))
            .toArray(String[]::new);
    }

    private static int[] targetMediaTypeCounts(List<SubResourceTargetMethod> targetMethods,
                                               Class<? extends Annotation> annotationType) {
        return targetMethods.stream()
            .mapToInt(targetMethod -> sourceMediaTypes(targetMethod.method(), annotationType).length)
            .toArray();
    }

    private static String[] sourceMediaTypes(MethodElement method, Class<? extends Annotation> annotationType) {
        AnnotationValue<?> annotation = sourceMediaAnnotation(method, annotationType);
        if (annotation == null) {
            annotation = sourceMediaAnnotation(method.getDeclaringType(), annotationType);
        }
        if (annotation == null) {
            return new String[0];
        }
        return ProducesMapper.splitMediaTypes(annotation.stringValues());
    }

    private static @Nullable AnnotationValue<?> sourceMediaAnnotation(io.micronaut.inject.ast.Element element,
                                                                      Class<? extends Annotation> annotationType) {
        if (!hasSourceMediaAnnotation(element, annotationType)) {
            return null;
        }
        AnnotationValue<?> annotation = element.getAnnotation(annotationType);
        if (annotation != null) {
            return annotation;
        }
        return element.getAnnotationMetadata().getAnnotation(jakartaMediaAnnotationName(annotationType));
    }

    private static boolean hasSourceMediaAnnotation(io.micronaut.inject.ast.Element element,
                                                   Class<? extends Annotation> annotationType) {
        return element.getAnnotationMetadata().hasDeclaredAnnotation(jakartaMediaAnnotationName(annotationType));
    }

    private static boolean hasDeclaredMediaAnnotation(io.micronaut.inject.ast.Element element,
                                                     Class<? extends Annotation> annotationType) {
        return hasSourceMediaAnnotation(element, annotationType) ||
            element.getAnnotationMetadata().hasDeclaredAnnotation(annotationType);
    }

    private static String jakartaMediaAnnotationName(Class<? extends Annotation> annotationType) {
        if (annotationType == Produces.class) {
            return JAKARTA_PRODUCES_ANNOTATION;
        }
        if (annotationType == Consumes.class) {
            return JAKARTA_CONSUMES_ANNOTATION;
        }
        throw new IllegalArgumentException("Unsupported media annotation: " + annotationType.getName());
    }

    private static String generalizedRoutePath(List<String> routePaths) {
        if (routePaths.isEmpty()) {
            return UriMapping.DEFAULT_URI;
        }
        if (routePaths.size() == 1) {
            return routePaths.get(0);
        }
        List<List<String>> routeSegments = routePaths.stream()
            .map(JaxRsTypeElementVisitor::routePathSegments)
            .toList();
        int segmentCount = routeSegments.get(0).size();
        if (routeSegments.stream().anyMatch(segments -> segments.size() != segmentCount)) {
            return routePaths.get(0);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < segmentCount; i++) {
            String segment = routeSegments.get(0).get(i);
            int segmentIndex = i;
            if (routeSegments.stream().allMatch(segments -> segments.get(segmentIndex).equals(segment))) {
                result.append('/').append(segment);
            } else {
                result.append('/').append(generalizedRouteSegment(routeSegments, segmentIndex));
            }
        }
        return result.isEmpty() ? UriMapping.DEFAULT_URI : result.toString();
    }

    private static String generalizedRouteSegment(List<List<String>> routeSegments, int segmentIndex) {
        List<String> segments = routeSegments.stream()
            .map(pathSegments -> pathSegments.get(segmentIndex))
            .toList();
        if (segments.stream().allMatch(segment -> routeTemplateVariableName(segment) != null)) {
            return segments.get(0);
        }
        return "{jaxrsSubResourcePath" + segmentIndex + "}";
    }

    private static List<String> routePathSegments(String path) {
        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return List.of();
        }
        List<String> segments = new ArrayList<>();
        int segmentStart = 1;
        int braceDepth = 0;
        for (int i = 1; i <= normalizedPath.length(); i++) {
            boolean end = i == normalizedPath.length();
            char c = end ? '\0' : normalizedPath.charAt(i);
            if (!end) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
            if (end || (c == '/' && braceDepth == 0)) {
                segments.add(normalizedPath.substring(segmentStart, i));
                segmentStart = i + 1;
            }
        }
        return segments;
    }

    private static String validationRoutePath(String path, MethodElement method) {
        Set<String> pathParameters = Arrays.stream(method.getParameters())
            .filter(parameter -> parameter.hasAnnotation(PathParam.class))
            .map(JaxRsTypeElementVisitor::getPathParameterName)
            .collect(Collectors.toSet());
        StringBuilder result = new StringBuilder();
        for (String segment : routePathSegments(path)) {
            String variable = routeTemplateVariableName(segment);
            if (variable != null && !pathParameters.contains(variable)) {
                break;
            }
            result.append('/').append(segment);
        }
        return result.isEmpty() ? UriMapping.DEFAULT_URI : result.toString();
    }

    private String toServerRoutePath(MethodElement method, String path, List<String> matrixParameterNames) {
        String routePath = path;
        if (isInheritedResourceMethod(method)) {
            List<String> subResourceLocatorPaths = subResourceLocatorPaths();
            if (!subResourceLocatorPaths.isEmpty()) {
                routePath = prependRoutePath(subResourceLocatorPaths.get(0), routePath);
            }
        }
        if (!matrixParameterNames.isEmpty()) {
            routePath = toMatrixParameterAwareRoute(routePath, matrixParameterNames);
        }
        return routePath;
    }

    static void annotateHttpRoute(MethodElement method, String path) {
        method.removeAnnotation(HttpMethodMapping.class);
        method.annotate(HttpMethodMapping.class, builder -> builder.value(path));
    }

    private static void annotateHttpRoute(MethodElement method, AnnotationValue<Annotation> routeAnnotation, String path) {
        method.removeAnnotation(HttpMethodMapping.class);
        method.annotate(routeAnnotation.mutate().value(path).build());
    }

    private static void annotateHttpRoute(MethodElement method, Class<? extends Annotation> routeAnnotation, String path) {
        method.removeAnnotation(HttpMethodMapping.class);
        method.annotate(routeAnnotation, builder -> builder.value(path));
    }

    private static void annotateDynamicHttpRoute(MethodElement method, String path) {
        method.removeAnnotation(HttpMethodMapping.class);
        method.annotate(CustomHttpMethod.class, builder -> builder
            .value(path)
            .member("method", DYNAMIC_SUB_RESOURCE_LOCATOR_HTTP_METHOD));
    }

    private boolean isInheritedResourceMethod(MethodElement method) {
        return currentClassElement != null && !method.getDeclaringType().getName().equals(currentClassElement.getName());
    }

    private boolean isUnrootedSubResourceTarget(MethodElement method) {
        return currentClassElement != null
            && !isClientClass()
            && !currentClassElement.isPublic()
            && !currentClassElement.hasAnnotation(Path.class)
            && !currentClassElement.hasAnnotation(Controller.class)
            && isPublicResourceMethod(method);
    }

    private List<String> subResourceLocatorPaths() {
        if (currentClassElement == null) {
            return List.of();
        }
        return currentClassElement.getMethods().stream()
            .filter(method -> !currentClassElement.isPublic() || method.isPublic())
            .filter(method -> jaxRsMethodPath(method).isPresent())
            .filter(method -> !method.hasStereotype(HttpMethod.class))
            .filter(method -> method.getReturnType().isAssignable(currentClassElement))
            .map(method -> jaxRsMethodPath(method).orElse(""))
            .filter(path -> !path.isEmpty())
            .toList();
    }

    private static boolean isPublicResourceMethod(MethodElement method) {
        return !method.getDeclaringType().isPublic() || method.isPublic();
    }

    private boolean isNonPublicServerResourceCandidate(MethodElement method) {
        return isServerResourceClass()
            && currentClassElement.isPublic()
            && !method.isPublic()
            && (method.hasStereotype(HttpMethod.class) || jaxRsMethodPath(method).isPresent());
    }

    private static void removeMicronautRouteAnnotations(MethodElement method) {
        for (Class<? extends Annotation> routeAnnotation : MICRONAUT_ROUTE_ANNOTATIONS) {
            method.removeAnnotation(routeAnnotation);
        }
        method.removeAnnotation(HttpMethodMapping.class);
    }

    static String getMatrixParameterName(TypedElement parameter) {
        return parameter.stringValue(MatrixParam.class).orElse(parameter.getName());
    }

    private static String getPathParameterName(TypedElement parameter) {
        return parameter.stringValue(PathParam.class).orElse(parameter.getName());
    }

    private static String prependRoutePath(String prefix, String path) {
        String normalizedPrefix = normalizeRoutePath(prefix);
        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return normalizedPrefix;
        }
        if ("/".equals(normalizedPrefix)) {
            return normalizedPath;
        }
        return normalizedPrefix + normalizedPath;
    }

    private static String appendDynamicRemainingRoute(String path) {
        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return "{" + '/' + JaxRsSubResourceLocatorMetadata.DYNAMIC_REMAINING_ROUTE_VARIABLE + ":.*}";
        }
        return normalizedPath + "{" + '/' + JaxRsSubResourceLocatorMetadata.DYNAMIC_REMAINING_ROUTE_VARIABLE + ":.*}";
    }

    private static String normalizeRoutePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        return path.charAt(0) == '/' ? path : '/' + path;
    }

    private static int pathSegmentCount(String path) {
        String normalizedPath = normalizeRoutePath(path);
        if ("/".equals(normalizedPath)) {
            return 0;
        }
        int count = 0;
        int segmentStart = 0;
        int braceDepth = 0;
        for (int i = 0; i <= normalizedPath.length(); i++) {
            boolean end = i == normalizedPath.length();
            char c = end ? '\0' : normalizedPath.charAt(i);
            if (!end) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
            if (end || (c == '/' && braceDepth == 0)) {
                if (i > segmentStart) {
                    count++;
                }
                segmentStart = i + 1;
            }
        }
        return count;
    }

    static String toMatrixParameterAwareRoute(String path, List<String> matrixParameterNames) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return path;
        }
        StringBuilder route = new StringBuilder(path.length() + 32);
        int segmentStart = 0;
        int segmentIndex = 0;
        int braceDepth = 0;
        for (int i = 0; i <= path.length(); i++) {
            boolean end = i == path.length();
            char c = end ? '\0' : path.charAt(i);
            if (!end) {
                if (c == '{') {
                    braceDepth++;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
            if (end || (c == '/' && braceDepth == 0)) {
                if (i > segmentStart) {
                    route.append(path, segmentStart, i);
                    appendMatrixParameterRoute(route, matrixParameterNames.get(Math.min(segmentIndex++, matrixParameterNames.size() - 1)));
                }
                if (!end) {
                    route.append(c);
                }
                segmentStart = i + 1;
            }
        }
        return route.toString();
    }

    private static void appendMatrixParameterRoute(StringBuilder route, String parameterName) {
        route.append('{')
            .append(parameterName)
            .append(MATRIX_PARAMETER_ROUTE_PATTERN)
            .append('}');
    }

    private List<Class<? extends Annotation>> getUnsupportedParameterAnnotations(VisitorContext context) {
        if (failOnUnsupported(context)) {
            return Collections.singletonList(BeanParam.class);
        }
        return Collections.emptyList();
    }

    private static boolean failOnUnsupported(VisitorContext context) {
        return !"false".equalsIgnoreCase(context.getOptions().getOrDefault(OPTION_FAIL_ON_UNSUPPORTED, "true"));
    }

    @Override
    public void start(VisitorContext visitorContext) {
        for (Class<?> type : BINDABLE_TYPES) {
            visitorContext.getClassElement(type).ifPresent(bindable -> bindable.annotate(Bindable.class));
        }
    }

    private record SubResourceTargetMethod(MethodElement method,
                                           AnnotationValue<Annotation> routeAnnotation,
                                           String httpMethod) {
    }

    private record RecursiveSubResourceLocator(MethodElement method) {
    }

    private record ProviderTypeMetadata(ClassElement type, boolean typeVariable) {
    }

}
