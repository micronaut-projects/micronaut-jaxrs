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
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.AnnotationValueBuilder;
import io.micronaut.core.annotation.Internal;
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
import io.micronaut.http.annotation.Trace;
import io.micronaut.http.annotation.UriMapping;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ConstructorElement;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.ParameterElement;
import io.micronaut.inject.ast.TypedElement;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
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
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A type element visitor that turns a JAX-RS path into a controller.
 *
 * @author graemerocher
 * @since 1.0
 */
@Internal
public class JaxRsTypeElementVisitor implements TypeElementVisitor<Object, Object> {

    public static final int POSITION = 200;
    private static final String CLIENT_ANNOTATION = "io.micronaut.http.client.annotation.Client";
    private static final String CONSTRUCTOR_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsConstructorInjection";
    private static final String REQUEST_FIELD_INJECTION_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsRequestFieldInjection";
    private static final String PATH_PARAM_BINDING_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsPathParamBinding";
    static final String SUB_RESOURCE_LOCATOR_ANNOTATION = "io.micronaut.jaxrs.container.JaxRsSubResourceLocator";
    static final String RECURSIVE_REMAINING_ROUTE_VARIABLE = "jaxrsRecursiveRemaining";
    static final String MATRIX_PARAMETER_ROUTE_PATTERN = ":;[^/]*|";
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
    private static final Class<?>[] BINDABLE_TYPES = new Class<?>[] {Context.class, SecurityContext.class, UriInfo.class};
    private ClassElement currentClassElement;

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
        if (element.hasStereotype(Provider.class)) {
            if (element.isEnum()) {
                return;
            }
            element.annotate(Singleton.class);
            element.annotate(Named.class);
            return;
        }
        currentClassElement = element;
        if (element.hasAnnotation(Path.class) && !element.isAbstract()) {
            element.stringValue(Path.class).ifPresent(p -> {
                element.annotate(Controller.class, builder -> builder.value(p));
                element.annotate(UriMapping.class, builder -> builder.value(p));
            });
            if (hasRequestParamField(element)) {
                markRequestFieldInjection();
            }
        }
    }

    @Override
    public void visitConstructor(ConstructorElement element, VisitorContext context) {
        if (!isCurrentClassConstructor(element) || !isServerResourceClass()) {
            return;
        }
        ConstructorElement jaxRsConstructor = findPreferredJaxRsConstructor();
        if (jaxRsConstructor != null && sameConstructorSignature(element, jaxRsConstructor)) {
            element.annotate(Inject.class);
            visitConstructorParameters(element);
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
            if (currentClassElement != null && !currentClassElement.hasAnnotation(Controller.class) && !currentClassElement.isAbstract()) {
                currentClassElement.annotate(Controller.class);
            }
            annotateDefaultMediaTypes(element);
            if (isServerResourceClass()) {
                annotateRequestFieldInjection(element);
                List<String> matrixParameterNames = matrixParameterNames(element);
                if (!matrixParameterNames.isEmpty()) {
                    markMatrixAwareClassPath(matrixParameterNames.get(0));
                }
                element.stringValue(HttpMethodMapping.class)
                    .map(path -> toServerRoutePath(element, path, matrixParameterNames))
                    .ifPresent(path -> annotateHttpRoute(element, path));
            }
            visitMethodParameters(element, context, true);
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
        SubResourceTargetMethod targetMethod = findSubResourceTargetMethod(element);
        if (targetMethod != null) {
            annotateRequestFieldInjection(element);
            MethodElement method = targetMethod.method();
            String locatorPath = element.stringValue(HttpMethodMapping.class).orElse(UriMapping.DEFAULT_URI);
            String targetPath = method.stringValue(HttpMethodMapping.class).orElse(UriMapping.DEFAULT_URI);
            String routePath = prependRoutePath(locatorPath, targetPath);
            RecursiveSubResourceLocator recursiveLocator = findRecursiveSubResourceLocator(element);
            if (!matrixParameterNames.isEmpty()) {
                routePath = toMatrixParameterAwareRoute(routePath, matrixParameterNames);
            }
            annotateSubResourceMediaTypes(element, method);
            annotateHttpRoute(element, targetMethod.routeAnnotation(), routePath);
            element.annotate(SUB_RESOURCE_LOCATOR_ANNOTATION, builder -> builder
                .value(method.getName())
                .member("type", new AnnotationClassValue<>(method.getDeclaringType().getName()))
                .member("recursive", recursiveLocator == null ? "" : recursiveLocator.method().getName())
                .member("remaining", recursiveLocator == null ? "" : RECURSIVE_REMAINING_ROUTE_VARIABLE));
            visitMethodParameters(element, context, false);
        }
    }

    private void annotateDefaultMediaTypes(MethodElement element) {
        if ((currentClassElement == null || !currentClassElement.hasAnnotation(Produces.class)) &&
            !element.hasAnnotation(Produces.class)) {
            element.annotate(Produces.class, b -> b.values(MediaType.ALL));
        }
        if ((currentClassElement == null || !currentClassElement.hasAnnotation(Consumes.class)) &&
            !element.hasAnnotation(Consumes.class)) {
            element.annotate(Consumes.class, b -> b.values(MediaType.ALL));
        }
    }

    private void annotateSubResourceMediaTypes(MethodElement locator, MethodElement target) {
        if (!locator.hasAnnotation(Produces.class)) {
            AnnotationValue<Produces> produces = target.getAnnotation(Produces.class);
            if (produces == null) {
                produces = target.getDeclaringType().getAnnotation(Produces.class);
            }
            if (produces == null) {
                if (currentClassElement == null || !currentClassElement.hasAnnotation(Produces.class)) {
                    locator.annotate(Produces.class, b -> b.values(MediaType.ALL));
                }
            } else {
                locator.annotate(produces);
            }
        }
        if (!locator.hasAnnotation(Consumes.class)) {
            AnnotationValue<Consumes> consumes = target.getAnnotation(Consumes.class);
            if (consumes == null) {
                consumes = target.getDeclaringType().getAnnotation(Consumes.class);
            }
            if (consumes == null) {
                if (currentClassElement == null || !currentClassElement.hasAnnotation(Consumes.class)) {
                    locator.annotate(Consumes.class, b -> b.values(MediaType.ALL));
                }
            } else {
                locator.annotate(consumes);
            }
        }
    }

    private void visitMethodParameters(MethodElement element, VisitorContext context, boolean bindUnannotatedBody) {
        final ParameterElement[] parameters = element.getParameters();
        boolean encoded = isEncoded(element);
        boolean hasPathParam = false;
        for (ParameterElement parameter : parameters) {
            final List<Class<? extends Annotation>> unsupported = getUnsupportedParameterAnnotations();
            for (Class<? extends Annotation> annType : unsupported) {
                if (parameter.hasAnnotation(annType)) {
                    context.fail("Unsupported JAX-RS annotation used on method: " + annType.getName(), parameter);
                }
            }
            if (encoded && (parameter.hasAnnotation(MatrixParam.class) || parameter.hasAnnotation(PathParam.class)) && !parameter.hasAnnotation(Encoded.class)) {
                parameter.annotate(Encoded.class);
            }
            hasPathParam |= parameter.hasAnnotation(PathParam.class);
            visitParamOrField(parameter);
            String parameterTypeName = parameter.getType().getName();
            if (bindUnannotatedBody
                && JAX_RS_BINDING_ANNOTATIONS.stream().noneMatch(parameter::hasAnnotation)
                && JAX_RS_BINDING_TYPES.stream().noneMatch(cl -> cl.equals(parameterTypeName))) {
                // unannotated, implicit @Body
                parameter.annotate(Body.class);
                parameter.annotate(Nullable.class); // JAX-RS controller bodies are nullable by default
            }
        }
        if (isServerResourceClass() && hasPathParam) {
            element.annotate(PATH_PARAM_BINDING_ANNOTATION);
        }
    }

    @Override
    public void visitField(FieldElement element, VisitorContext context) {
        visitParamOrField(element);
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
        } else if (element.hasAnnotation(FormParam.class) || element.hasAnnotation(BeanParam.class)) {
            context.fail("Request scoped bean parameters are currently not supported", element); // todo
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
            || field.hasAnnotation(PathParam.class);
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

    private void visitParamOrField(TypedElement parameter) {
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
    }

    private static void annotateHeaderParam(TypedElement parameter) {
        AnnotationValueBuilder<HeaderParam> builder = AnnotationValue.builder(HeaderParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateQueryParam(TypedElement parameter) {
        AnnotationValueBuilder<QueryParam> builder = AnnotationValue.builder(QueryParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
            .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateMatrixParam(TypedElement parameter) {
        AnnotationValueBuilder<MatrixParam> builder = AnnotationValue.builder(MatrixParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateFormParam(TypedElement parameter) {
        AnnotationValueBuilder<FormParam> builder = AnnotationValue.builder(FormParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotatePathParam(TypedElement parameter) {
        AnnotationValueBuilder<PathParam> builder = AnnotationValue.builder(PathParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateCookieParam(TypedElement parameter) {
        AnnotationValueBuilder<CookieParam> builder = AnnotationValue.builder(CookieParam.class);
        annotateDefaultAndNullable(parameter, builder);
        parameter.annotate(
            builder
                .stereotype(AnnotationValue.builder(Bindable.class).build()).build()
        );
    }

    private static void annotateDefaultAndNullable(TypedElement parameter, AnnotationValueBuilder<?> builder) {
        if (parameter.isPrimitive()) {
            if (parameter.getType().isAssignable(boolean.class)) {
                annotateDefaultValue(parameter, builder, "false");
            } else {
                annotateDefaultValue(parameter, builder, "0");
            }
        } else if (!parameter.isNonNull()) {
            parameter.annotate(Nullable.class);
        }
    }

    private static void annotateDefaultValue(TypedElement parameter, AnnotationValueBuilder<?> builder, String defaultValue) {
        builder.member("defaultValue", defaultValue);
        if (!parameter.hasAnnotation(Bindable.class)) {
            parameter.annotate(Bindable.class, bindable -> bindable.member("defaultValue", defaultValue));
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

    private void visitConstructorParameters(ConstructorElement element) {
        for (ParameterElement parameter : element.getParameters()) {
            visitParamOrField(parameter);
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
            .filter(parameter -> parameter.hasAnnotation(MatrixParam.class) || isPathSegmentPathParam(parameter))
            .map(parameter -> parameter.hasAnnotation(MatrixParam.class) ? getMatrixParameterName(parameter) : getPathParameterName(parameter))
            .toList();
    }

    static List<String> matrixFieldNames(ClassElement element) {
        return element.getFields().stream()
            .filter(field -> field.hasAnnotation(MatrixParam.class))
            .map(JaxRsTypeElementVisitor::getMatrixParameterName)
            .toList();
    }

    private static boolean isPathSegmentPathParam(ParameterElement parameter) {
        return parameter.hasAnnotation(PathParam.class) && parameter.getType().isAssignable(PathSegment.class);
    }

    private boolean isSubResourceLocator(MethodElement method) {
        return isServerResourceClass() && method.hasAnnotation(Path.class) && !method.hasStereotype(HttpMethod.class);
    }

    private static boolean isSubResourceTargetMethod(MethodElement method) {
        return isPublicResourceMethod(method) && method.hasStereotype(HttpMethod.class) && method.getParameters().length == 0;
    }

    private boolean isUnrootedRecursiveSubResourceLocator(MethodElement method) {
        return currentClassElement != null &&
            !currentClassElement.hasAnnotation(Path.class) &&
            method.getParameters().length == 0 &&
            method.getReturnType().getName().equals(method.getDeclaringType().getName()) &&
            singlePathVariableName(method.stringValue(Path.class).orElse("")) != null;
    }

    private static @Nullable SubResourceTargetMethod findSubResourceTargetMethod(MethodElement locator) {
        String returnTypeName = locator.getReturnType().getName();
        SubResourceTargetMethod inheritedTargetMethod = null;
        for (MethodElement method : locator.getReturnType().getMethods()) {
            if (isSubResourceTargetMethod(method)) {
                List<AnnotationValue<Annotation>> routeAnnotations = method.getAnnotationValuesByStereotype(HttpMethodMapping.class.getName());
                if (!routeAnnotations.isEmpty()) {
                    SubResourceTargetMethod targetMethod = new SubResourceTargetMethod(method, routeAnnotations.get(0));
                    if (method.getDeclaringType().getName().equals(returnTypeName)) {
                        return targetMethod;
                    }
                    if (inheritedTargetMethod == null) {
                        inheritedTargetMethod = targetMethod;
                    }
                }
            }
        }
        return inheritedTargetMethod;
    }

    private static @Nullable RecursiveSubResourceLocator findRecursiveSubResourceLocator(MethodElement locator) {
        String returnTypeName = locator.getReturnType().getName();
        for (MethodElement method : locator.getReturnType().getMethods()) {
            if (method.hasAnnotation(Path.class) &&
                !method.hasStereotype(HttpMethod.class) &&
                method.getParameters().length == 0 &&
                method.getReturnType().getName().equals(returnTypeName)) {
                String path = method.stringValue(Path.class).orElse("");
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

    private record SubResourceTargetMethod(MethodElement method, AnnotationValue<Annotation> routeAnnotation) {
    }

    private record RecursiveSubResourceLocator(MethodElement method) {
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

    private boolean isInheritedResourceMethod(MethodElement method) {
        return currentClassElement != null && !method.getDeclaringType().getName().equals(currentClassElement.getName());
    }

    private List<String> subResourceLocatorPaths() {
        if (currentClassElement == null) {
            return List.of();
        }
        return currentClassElement.getMethods().stream()
            .filter(method -> !currentClassElement.isPublic() || method.isPublic())
            .filter(method -> method.hasAnnotation(Path.class))
            .filter(method -> !method.hasStereotype(HttpMethod.class))
            .filter(method -> method.getReturnType().isAssignable(currentClassElement))
            .map(method -> method.stringValue(Path.class).orElse(""))
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
            && (method.hasStereotype(HttpMethod.class) || method.hasAnnotation(Path.class));
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

    private static String normalizeRoutePath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }
        return path.charAt(0) == '/' ? path : '/' + path;
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

    private List<Class<? extends Annotation>> getUnsupportedParameterAnnotations() {
        return Collections.singletonList(BeanParam.class);
    }

    @Override
    public void start(VisitorContext visitorContext) {
        for (Class<?> type : BINDABLE_TYPES) {
            visitorContext.getClassElement(type).ifPresent(bindable -> bindable.annotate(Bindable.class));
        }
    }
}
